/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.DocumentRef;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkSink;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.schedule.DocumentStatusHelper;
import com.nageoffer.ai.ragent.knowledge.sink.RelationalChunkSink;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreService;
import com.nageoffer.ai.ragent.rag.core.vector.sink.VectorChunkSink;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.test.LocalPostgresTestDatabase;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentVersionConcurrencyTest {

    private static LocalPostgresTestDatabase database;
    private static JdbcTemplate jdbc;
    private static KnowledgeDocumentMapper documentMapper;
    private static KnowledgeChunkMapper chunkMapper;
    private static DocumentStatusHelper statusHelper;
    private static TransactionTemplate transactions;
    private static RelationalChunkSink relationalSink;
    private static VectorChunkSink vectorSink;
    private static ChunkIndexWriter writer;

    @BeforeAll
    static void startDatabase() throws Exception {
        database = new LocalPostgresTestDatabase();
        try {
            var dataSource = database.start();

            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(KnowledgeDocumentMapper.class);
            configuration.addMapper(KnowledgeChunkMapper.class);
            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            factoryBean.afterPropertiesSet();
            SqlSessionFactory sqlSessionFactory = factoryBean.getObject();
            SqlSessionTemplate sqlSession = new SqlSessionTemplate(sqlSessionFactory);

            documentMapper = sqlSession.getMapper(KnowledgeDocumentMapper.class);
            chunkMapper = sqlSession.getMapper(KnowledgeChunkMapper.class);
            statusHelper = new DocumentStatusHelper(documentMapper);
            JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
            transactions = new TransactionTemplate(transactionManager);
            jdbc = new JdbcTemplate(dataSource);

            TokenCounterService tokenCounter = text -> text == null ? 0 : text.length();
            relationalSink = new RelationalChunkSink(chunkMapper, tokenCounter);
            PgVectorStoreService pgVector = new PgVectorStoreService(jdbc, new ObjectMapper());
            vectorSink = new VectorChunkSink(pgVector);
            writer = new ChunkIndexWriter(List.of(relationalSink, vectorSink), transactions);
        } catch (Exception e) {
            database.close();
            throw e;
        }
    }

    @AfterAll
    static void stopDatabase() {
        database.close();
    }

    @BeforeEach
    void resetData() {
        database.cleanBusinessTables();
    }

    // 验证 A 超时后 B 重启，A 的失败写回无法覆盖 B
    @Test
    void staleFailureCannotOverwriteRestartedExecution() {
        insertDocument("running", "version-a");
        makeDocumentStuck();

        assertThat(statusHelper.recoverStuckRunning(10).actualRecovered()).isOne();
        String recoveryVersion = documentMapper.selectById("doc-1").getDocumentVersion();
        assertThat(statusHelper.tryStartChunk("doc-1", recoveryVersion, "version-b", "system")).isTrue();

        assertThat(statusHelper.markFailedIfRunning("doc-1", "version-a", "worker-a")).isFalse();
        assertThat(documentMapper.selectById("doc-1").getStatus()).isEqualTo("running");
        assertThat(documentMapper.selectById("doc-1").getDocumentVersion()).isEqualTo("version-b");
    }

    // 验证 A 的旧结果在首个 Sink 前被拒绝且不影响 B 的数据
    @Test
    void staleWriterIsRejectedBeforeAnySinkAndPreservesNewData() {
        insertDocument("running", "version-b");
        seedChunkAndVector("chunk-b", "new content");
        DocumentRef stale = new DocumentRef("doc-1", "kb-1", "old.md", "version-a");

        assertThatThrownBy(() -> writer.replaceDocument(
                target(), stale, List.of(chunk("chunk-a", "old content")),
                () -> statusHelper.lockRunning("doc-1", "version-a"),
                () -> statusHelper.markSucceeded("doc-1", "version-a", 1, "text/markdown", null, "worker-a")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("文档操作版本已失效");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_knowledge_chunk WHERE id = 'chunk-b'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_knowledge_vector WHERE id = 'chunk-b'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_knowledge_chunk WHERE id = 'chunk-a'", Integer.class)).isZero();
    }

    // 验证当前 writer 持锁时恢复任务等待，writer 成功后恢复 CAS 失败
    @Test
    void successfulWriterMakesWaitingRecoveryFail() throws Exception {
        insertDocument("running", "version-a");
        makeDocumentStuck();
        CountDownLatch sinksWritten = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ChunkSink blocker = blockingSink(sinksWritten, allowCommit);
        ChunkIndexWriter blockingWriter = new ChunkIndexWriter(
                List.of(relationalSink, vectorSink, blocker), transactions);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> write = executor.submit(() -> blockingWriter.replaceDocument(
                    target(), documentRef("version-a"), List.of(chunk("chunk-a", "content-a")),
                    () -> statusHelper.lockRunning("doc-1", "version-a"),
                    () -> assertThat(statusHelper.markSucceeded(
                            "doc-1", "version-a", 1, "text/markdown", null, "worker-a")).isTrue()));
            assertThat(sinksWritten.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Integer> recovery = executor.submit(() ->
                    statusHelper.recoverStuckRunning(10).actualRecovered());
            Thread.sleep(200);
            assertThat(recovery.isDone()).isFalse();

            allowCommit.countDown();
            write.get(5, TimeUnit.SECONDS);
            assertThat(recovery.get(5, TimeUnit.SECONDS)).isZero();
            assertThat(documentMapper.selectById("doc-1").getStatus()).isEqualTo("success");
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }
    }

    // 验证分块和删除并发领取只有一个成功
    @Test
    void chunkAndDeleteClaimsHaveExactlyOneWinner() throws Exception {
        insertDocument("success", "stable-version");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> chunk = executor.submit(() -> claimConcurrently(ready, start, true));
            Future<Integer> delete = executor.submit(() -> claimConcurrently(ready, start, false));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(chunk.get(5, TimeUnit.SECONDS) + delete.get(5, TimeUnit.SECONDS)).isOne();
            assertThat(documentMapper.selectById("doc-1").getStatus()).isIn("running", "deleting");
        } finally {
            executor.shutdownNow();
        }
    }

    // 验证两个单 Chunk 修改基于同一快照时只有一个能推进文档版本
    @Test
    void concurrentChunkMutationsHaveExactlyOneWinner() throws Exception {
        insertDocument("success", "stable-version");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> advanceVersionConcurrently(
                    ready, start, "chunk-version-a"));
            Future<Integer> second = executor.submit(() -> advanceVersionConcurrently(
                    ready, start, "chunk-version-b"));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS)).isOne();
            assertThat(documentMapper.selectById("doc-1").getDocumentVersion())
                    .isIn("chunk-version-a", "chunk-version-b");
        } finally {
            executor.shutdownNow();
        }
    }

    // 验证单 Chunk 修改和文档删除基于同一快照时只有一个能领取
    @Test
    void chunkMutationAndDeleteHaveExactlyOneWinner() throws Exception {
        insertDocument("success", "stable-version");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> mutation = executor.submit(() -> advanceVersionConcurrently(
                    ready, start, "chunk-version"));
            Future<Integer> deletion = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return statusHelper.tryMarkDeleting("doc-1", "stable-version", "deleter") != null ? 1 : 0;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(mutation.get(5, TimeUnit.SECONDS) + deletion.get(5, TimeUnit.SECONDS)).isOne();
            KnowledgeDocumentDO actual = documentMapper.selectById("doc-1");
            if (DocumentStatus.DELETING.getCode().equals(actual.getStatus())) {
                assertThat(actual.getDocumentVersion()).isNotEqualTo("stable-version");
            } else {
                assertThat(actual.getStatus()).isEqualTo(DocumentStatus.SUCCESS.getCode());
                assertThat(actual.getDocumentVersion()).isEqualTo("chunk-version");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // 验证删除进入 DELETING 后旧 worker 无法进入任何 Sink
    @Test
    void deletingFenceRejectsOldWorkerBeforeWrite() {
        insertDocument("success", "stable-version");
        assertThat(statusHelper.tryMarkDeleting("doc-1", "stable-version", "deleter")).isNotNull();
        AtomicBoolean sinkCalled = new AtomicBoolean();
        ChunkSink observingSink = observingSink(sinkCalled);
        ChunkIndexWriter observingWriter = new ChunkIndexWriter(List.of(observingSink), transactions);

        assertThatThrownBy(() -> observingWriter.replaceDocument(
                target(), documentRef("stable-version"), List.of(chunk("chunk-a", "old")),
                () -> statusHelper.lockRunning("doc-1", "stable-version"), () -> { }))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("文档操作版本已失效");
        assertThat(sinkCalled).isFalse();
    }

    // 验证任一 Sink 失败时文档状态、Chunk 和 PgVector 一起回滚
    @Test
    void sinkFailureRollsBackAllPostgresWritesAndSuccessState() {
        insertDocument("running", "version-a");
        ChunkSink failingSink = failingSink();
        ChunkIndexWriter failingWriter = new ChunkIndexWriter(
                List.of(relationalSink, vectorSink, failingSink), transactions);

        assertThatThrownBy(() -> failingWriter.replaceDocument(
                target(), documentRef("version-a"), List.of(chunk("chunk-a", "content")),
                () -> statusHelper.lockRunning("doc-1", "version-a"),
                () -> statusHelper.markSucceeded("doc-1", "version-a", 1, "text/markdown", null, "worker-a")))
                .hasMessage("sink failed");

        assertThat(documentMapper.selectById("doc-1").getStatus()).isEqualTo("running");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_knowledge_chunk", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_knowledge_vector", Integer.class)).isZero();
    }

    // 验证文件元数据、MIME、chunkCount 和 SUCCESS 随 Sink 在同一事务提交
    @Test
    void refreshedMetadataAndSuccessCommitWithIndexes() {
        insertDocument("running", "version-a");
        StoredFileDTO refreshed = StoredFileDTO.builder()
                .originalFilename("new.md")
                .url("new-url")
                .detectedType("md")
                .size(321L)
                .build();

        writer.replaceDocument(
                target(), documentRef("version-a"), List.of(chunk("chunk-a", "content")),
                () -> statusHelper.lockRunning("doc-1", "version-a"),
                () -> assertThat(statusHelper.markSucceeded(
                        "doc-1", "version-a", 1, "text/markdown", refreshed, "system")).isTrue());

        KnowledgeDocumentDO actual = documentMapper.selectById("doc-1");
        assertThat(actual.getStatus()).isEqualTo("success");
        assertThat(actual.getChunkCount()).isOne();
        assertThat(actual.getMimeType()).isEqualTo("text/markdown");
        assertThat(actual.getDocName()).isEqualTo("new.md");
        assertThat(actual.getFileUrl()).isEqualTo("new-url");
        assertThat(actual.getFileSize()).isEqualTo(321L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_knowledge_vector", Integer.class)).isOne();
    }

    // 验证 Chunk 修改推进版本后旧 enable 快照失效
    @Test
    void chunkMutationInvalidatesEnableSnapshot() {
        insertDocument("success", "snapshot-version");
        assertThat(statusHelper.advanceStableVersion(
                "doc-1", "snapshot-version", "chunk-version", "editor")).isTrue();

        KnowledgeDocumentDO actual = documentMapper.selectById("doc-1");
        assertThat(actual.getDocumentVersion()).isEqualTo("chunk-version");
        assertThat(actual.getDocumentVersion()).isNotEqualTo("snapshot-version");
    }

    // 验证 enable 预计算期间删除先完成时旧快照不能重建向量
    @Test
    void deletionCompletedDuringEnablePrecomputationInvalidatesEnableSnapshot() {
        insertDocument("success", "snapshot-version");
        seedChunkAndVector("chunk-a", "content");
        String deletingVersion = statusHelper.tryMarkDeleting("doc-1", "snapshot-version", "deleter");
        assertThat(deletingVersion).isNotNull();
        transactions.executeWithoutResult(status -> {
            assertThat(softDeleteIfOwner(
                    "doc-1", deletingVersion, "deleter")).isOne();
            writer.deleteDocument(target(), documentRef(deletingVersion));
        });

        assertThat(documentMapper.selectById("doc-1")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_knowledge_vector", Integer.class)).isZero();
    }

    // 验证刷新运行期间删除 CAS 失败，刷新提交后使用最新版本重试可完整清理
    @Test
    void deletionDuringRefreshRequiresRetryAfterCommit() throws Exception {
        insertDocument("running", "refresh-version");
        CountDownLatch sinksWritten = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ChunkIndexWriter blockingWriter = new ChunkIndexWriter(
                List.of(relationalSink, vectorSink, blockingSink(sinksWritten, allowCommit)), transactions);
        StoredFileDTO refreshed = StoredFileDTO.builder()
                .originalFilename("new.md")
                .url("new-url")
                .detectedType("md")
                .size(456L)
                .build();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<String> deletedFileUrl = new AtomicReference<>();
        try {
            Future<?> refresh = executor.submit(() -> blockingWriter.replaceDocument(
                    target(), documentRef("refresh-version"), List.of(chunk("chunk-new", "new content")),
                    () -> statusHelper.lockRunning("doc-1", "refresh-version"),
                    () -> assertThat(statusHelper.markSucceeded(
                            "doc-1", "refresh-version", 1, "text/markdown", refreshed, "system")).isTrue()));
            assertThat(sinksWritten.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(statusHelper.tryMarkDeleting(
                    "doc-1", "refresh-version", "deleter")).isNull();

            allowCommit.countDown();
            refresh.get(5, TimeUnit.SECONDS);
            deleteByCas("refresh-version", deletedFileUrl);

            assertThat(deletedFileUrl).hasValue("new-url");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_knowledge_chunk WHERE deleted = 0", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_knowledge_vector", Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT deleted FROM t_knowledge_document WHERE id = 'doc-1'", Integer.class)).isOne();
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }
    }

    // 验证 enable 先更新版本时删除旧版本 CAS 失败，使用最新版本重试可完整清理
    @Test
    void deletionAfterLockedEnableRequiresLatestVersionRetry() throws Exception {
        insertDocument("success", "snapshot-version");
        seedChunkAndVector("chunk-a", "old content");
        CountDownLatch enableLocked = new CountDownLatch(1);
        CountDownLatch allowEnableCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<String> deletedFileUrl = new AtomicReference<>();
        try {
            Future<?> enable = executor.submit(() -> transactions.executeWithoutResult(status -> {
                assertThat(statusHelper.advanceStableVersion(
                        "doc-1", "snapshot-version", "enable-version", "enabler")).isTrue();
                vectorSink.replaceDocument(
                        target(), documentRef("enable-version"), List.of(chunk("chunk-a", "enabled content")));
                enableLocked.countDown();
                try {
                    assertThat(allowEnableCommit.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(enableLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<String> deletion = executor.submit(() ->
                    statusHelper.tryMarkDeleting("doc-1", "snapshot-version", "deleter"));
            Thread.sleep(200);
            assertThat(deletion.isDone()).isFalse();

            allowEnableCommit.countDown();
            enable.get(5, TimeUnit.SECONDS);
            assertThat(deletion.get(5, TimeUnit.SECONDS)).isNull();
            deleteByCas("enable-version", deletedFileUrl);

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_knowledge_chunk WHERE deleted = 0", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_knowledge_vector", Integer.class)).isZero();
        } finally {
            allowEnableCommit.countDown();
            executor.shutdownNow();
        }
    }

    private int claimConcurrently(CountDownLatch ready, CountDownLatch start, boolean chunk) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        if (chunk) {
            return statusHelper.tryStartChunk(
                    "doc-1", "stable-version", "chunk-version", "worker") ? 1 : 0;
        }
        return statusHelper.tryMarkDeleting("doc-1", "stable-version", "deleter") != null ? 1 : 0;
    }

    private int advanceVersionConcurrently(CountDownLatch ready,
                                           CountDownLatch start,
                                           String newVersion) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return statusHelper.advanceStableVersion(
                "doc-1", "stable-version", newVersion, "chunk-editor") ? 1 : 0;
    }

    private void deleteByCas(String expectedVersion, AtomicReference<String> deletedFileUrl) {
        transactions.executeWithoutResult(status -> {
            String deletingVersion = statusHelper.tryMarkDeleting(
                    "doc-1", expectedVersion, "deleter");
            assertThat(deletingVersion).isNotNull();
            KnowledgeDocumentDO latest = documentMapper.selectById("doc-1");
            deletedFileUrl.set(latest.getFileUrl());
            assertThat(softDeleteIfOwner(
                    "doc-1", deletingVersion, "deleter")).isOne();
            writer.deleteDocument(target(), documentRef(deletingVersion));
        });
    }

    private int softDeleteIfOwner(String docId, String ownerVersion, String updatedBy) {
        return documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getDeleted, 1)
                        .set(KnowledgeDocumentDO::getUpdatedBy, updatedBy)
                        .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.DELETING.getCode())
                        .eq(KnowledgeDocumentDO::getDocumentVersion, ownerVersion));
    }

    private void insertDocument(String status, String version) {
        jdbc.update("INSERT INTO t_knowledge_document "
                        + "(id, kb_id, doc_name, enabled, chunk_count, file_url, file_type, status, document_version, "
                        + "ingestion_spec, created_by, deleted) "
                        + "VALUES (?, ?, ?, 1, 0, ?, ?, ?, ?, '{}'::jsonb, ?, 0)",
                "doc-1", "kb-1", "old.md", "old-url", "md", status, version, "tester");
    }

    private void makeDocumentStuck() {
        jdbc.update("UPDATE t_knowledge_document SET update_time = NOW() - INTERVAL '20 minutes' WHERE id = 'doc-1'");
    }

    private void seedChunkAndVector(String id, String content) {
        jdbc.update("INSERT INTO t_knowledge_chunk "
                        + "(id, kb_id, doc_id, chunk_index, content, embedding_text, enabled, deleted) "
                        + "VALUES (?, 'kb-1', 'doc-1', 0, ?, ?, 1, 0)", id, content, content);
        jdbc.update("INSERT INTO t_knowledge_vector (id, collection_name, content, metadata, embedding) "
                + "VALUES (?, 'collection-1', ?, '{\"doc_id\":\"doc-1\"}'::jsonb, '[1,2,3]'::vector)", id, content);
    }

    private DocumentRef documentRef(String version) {
        return new DocumentRef("doc-1", "kb-1", "old.md", version);
    }

    private VectorTarget target() {
        return new VectorTarget("collection-1", "embedding-model", 3);
    }

    private EmbeddedChunk chunk(String id, String content) {
        return new EmbeddedChunk(new Chunk(id, 0, content, content, ChunkMetadata.empty()), new float[]{1, 2, 3});
    }

    private ChunkSink blockingSink(CountDownLatch entered, CountDownLatch release) {
        return new ChunkSink() {
            @Override
            public void replaceDocument(VectorTarget target, DocumentRef doc, List<EmbeddedChunk> chunks) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to commit");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void deleteDocument(VectorTarget target, DocumentRef doc) {
            }
        };
    }

    private ChunkSink observingSink(AtomicBoolean called) {
        return new ChunkSink() {
            @Override
            public void replaceDocument(VectorTarget target, DocumentRef doc, List<EmbeddedChunk> chunks) {
                called.set(true);
            }

            @Override
            public void deleteDocument(VectorTarget target, DocumentRef doc) {
                called.set(true);
            }
        };
    }

    private ChunkSink failingSink() {
        return new ChunkSink() {
            @Override
            public void replaceDocument(VectorTarget target, DocumentRef doc, List<EmbeddedChunk> chunks) {
                throw new IllegalStateException("sink failed");
            }

            @Override
            public void deleteDocument(VectorTarget target, DocumentRef doc) {
                throw new IllegalStateException("sink failed");
            }
        };
    }
}
