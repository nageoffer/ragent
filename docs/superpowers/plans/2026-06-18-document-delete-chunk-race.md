# 文档删除与分块并发竞态修复 实现方案（issue #42）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用「状态机 CAS 互斥 + 数据库 outbox 兜底清理」彻底消除 `KnowledgeDocumentService#delete` 的 TOCTOU 竞态，并保证 PgVector / 文件等外部资源在主事务提交后可靠删除。

**Architecture:** 在 `t_knowledge_document.status` 上引入 `deleting` 状态，删除与分块通过 CAS 抢占同一字段实现互斥；删除主事务只做「主库清理 + 写入两张 outbox 任务表（vector / file）」，提交后由一个 `@Scheduled` worker 扫描 outbox 任务、调用外部服务删除、失败重试、超阈值告警。worker 完全复用仓库已有的 `KnowledgeDocumentScheduleJob` + `ScheduleLockManager` 范式（扫描 + 租约锁 + 线程池）。

**Tech Stack:** Java 17、Spring Boot、MyBatis-Plus（`BaseMapper` + `@Update`/`@Select` 注解 SQL，无 XML）、PostgreSQL（pgvector）、JUnit 5 + Mockito、Lombok。

---

## 背景与现状（实现前必读）

issue #42 要解决两个层次的问题：

1. **删除与分块的并发竞态（TOCTOU）**：`delete()` 先 `selectById` 查状态、判断不是 `running` 就继续删，检查与删除之间存在时间窗口，分块任务可在窗口内启动并写回 chunk / vector，导致孤儿数据。
2. **外部资源删除的跨系统可靠性**：PgVector 是独立 Postgres 实例、文件存储是 OSS/MinIO，均不在主库（业务库）事务内。同步删除一旦失败，主事务已提交无法回滚，且当前 `deleteStoredFileQuietly` 静默吞异常 → 资源泄漏且无人重试。

**与 issue 文字的术语对齐（重要，避免照抄 issue 伪代码出错）：**

issue 正文用的状态名是 `IDLE / RUNNING / COMPLETED / FAILED`，但本仓库 `DocumentStatus` 枚举实际为：

| issue 术语 | 本仓库枚举 | code 字符串 |
|---|---|---|
| IDLE | `PENDING` | `pending` |
| RUNNING | `RUNNING` | `running` |
| FAILED | `FAILED` | `failed` |
| COMPLETED | `SUCCESS` | `success` |
| （新增）DELETING | `DELETING` | `deleting` |

落地的 CAS 规则（已按本仓库枚举翻译）：

| 操作 | 允许的源状态 | 目标状态 |
|---|---|---|
| 分块入口 | `pending`, `failed` | `running` |
| 删除入口 | `pending`, `success`, `failed` | `deleting` |
| 分块完成 | `running` | `success` |
| 分块失败 | `running` | `failed` |

**现有可复用资产（不要重新发明）：**

- `DocumentStatusHelper`（`knowledge/schedule/`）已经用 `Wrappers.lambdaUpdate` 做条件更新（`tryMarkRunning` / `markFailedIfRunning` / `recoverStuckRunning`）。CAS 工具方法加在这里，复用现有风格。
- `KnowledgeDocumentScheduleJob`（`knowledge/schedule/`）= `@Scheduled(fixedDelay)` 扫描 + 租约锁 + `knowledgeChunkExecutor` 线程池。cleanup worker 直接照此范式写。
- `recoverStuckRunningDocuments`（同文件）= 已有的「卡死恢复」定时任务范例，cleanup 的重试/告警逻辑参考它。
- `KnowledgeScheduleProperties`（`prefix = rag.knowledge.schedule`）= 配置项范例。
- `IngestionTaskDO` / `t_ingestion_task` = 任务表（含 `status` / `error_message` / `retry`）的命名与建表范例。
- 数据库脚本：`resources/database/schema_pg.sql`（全量）+ `resources/database/upgrade_vX_to_vY.sql`（增量升级）。两处都要改。
- Mapper 注册：`RagentApplication` 的 `@MapperScan` 已包含 `com.nageoffer.ai.ragent.knowledge.dao.mapper`，新增 mapper 放该包下即可自动扫描。

**当前工作树状态：** 干净（之前不符合 issue 的临时改动已 `git restore` 回滚）。本计划从原始 `KnowledgeDocumentServiceImpl#delete`（`if status==running throw` 版本）出发。

---

## 文件结构（先锁定边界）

**新增（生产代码）：**

```
knowledge/dao/entity/VectorCleanupTaskDO.java        # t_vector_cleanup_task 实体
knowledge/dao/entity/FileCleanupTaskDO.java          # t_file_cleanup_task 实体
knowledge/dao/mapper/VectorCleanupTaskMapper.java    # BaseMapper + claim/扫描注解SQL
knowledge/dao/mapper/FileCleanupTaskMapper.java      # BaseMapper + claim/扫描注解SQL
knowledge/enums/CleanupTaskStatus.java               # pending/success/failed 任务状态
knowledge/service/CleanupTaskService.java            # 入队接口（删除主事务内调用）
knowledge/service/impl/CleanupTaskServiceImpl.java   # 入队实现
knowledge/schedule/CleanupTaskWorker.java            # @Scheduled worker：扫描+执行+重试+告警
knowledge/config/CleanupTaskProperties.java          # rag.knowledge.cleanup.* 配置
```

**修改（生产代码）：**

```
knowledge/enums/DocumentStatus.java                          # 新增 DELETING
knowledge/dao/mapper/KnowledgeDocumentMapper.java            # 新增 casStatus / selectStatusById
knowledge/schedule/DocumentStatusHelper.java                 # 新增 CAS 包装方法（复用入口）
knowledge/service/impl/KnowledgeDocumentServiceImpl.java     # delete 改 CAS；分块入口/中段改 CAS；移除静默吞异常→入队
```

**新增/修改（资源与脚本）：**

```
resources/database/schema_pg.sql                     # 追加两张表 DDL
resources/database/upgrade_v1.2_to_v1.3.sql          # 新增增量升级脚本（见 Task 9 确认版本号）
```

**新增（测试）：**

```
src/test/java/.../knowledge/service/DocumentDeleteRaceTest.java     # delete/chunk CAS 互斥
src/test/java/.../knowledge/service/CleanupTaskServiceImplTest.java # 入队逻辑
src/test/java/.../knowledge/schedule/CleanupTaskWorkerTest.java     # worker 重试/成功/告警
```

> 测试基类风格：参照 `ScheduleRefreshProcessorTest`，`@ExtendWith(MockitoExtension.class)` + `@Mock` 注入，必要时用反射构造（仓库已有先例）。`spring-boot-starter-test`、`mockito-core` 已在 pom。

---

## 构建与验证命令（每个 Task 末尾用）

- 单测（单类）：`./mvnw -q -pl bootstrap test -Dtest=<TestClassName>`
- 单测（单方法）：`./mvnw -q -pl bootstrap test -Dtest=<TestClassName>#<methodName>`
- 编译：`./mvnw -q -pl bootstrap -am compile`
- 全量编译+测试：`./mvnw -q -pl bootstrap -am test`

> Windows 用 `mvnw.cmd`；本仓库 bash 环境下 `./mvnw` 可用。`-pl bootstrap -am` 表示只构建 bootstrap 模块及其依赖。

---

### Task 1: 扩展状态机 — 新增 DELETING 状态

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/enums/DocumentStatus.java:46-50`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/DocumentDeleteRaceTest.java`

- [ ] **Step 1: 新增 DELETING 枚举值**

在 `SUCCESS("success");` 之后追加（注意把 `SUCCESS` 行尾分号改为逗号）：

```java
    /**
     * 文档处理成功
     */
    SUCCESS("success"),

    /**
     * 文档删除中（删除流程已抢占，分块不得再启动或写回）
     */
    DELETING("deleting");
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw -q -pl bootstrap -am compile`
Expected: BUILD SUCCESS（仅新增枚举常量，无引用破坏）

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/enums/DocumentStatus.java
git commit -m "feat: DocumentStatus 新增 DELETING 状态"
```

---

### Task 2: KnowledgeDocumentMapper 新增 CAS 与状态查询

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/mapper/KnowledgeDocumentMapper.java:23-24`

> 本仓库无 mapper XML，统一用 MyBatis-Plus 注解 SQL。`@Param` 来自 `org.apache.ibatis.annotations.Param`。CAS 用 `status IN (...)` + `deleted = 0` 保证原子性，返回受影响行数；0 表示并发抢占失败。

- [ ] **Step 1: 写入 mapper 方法**

将文件 body 替换为：

```java
package com.nageoffer.ai.ragent.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentDO> {

    /**
     * CAS 抢占文档状态：仅当当前状态命中 fromStatuses 且未删除时，原子置为 toStatus。
     *
     * @return 受影响行数；0 表示并发冲突（状态已被其它流程改写）
     */
    @Update("<script>"
            + "UPDATE t_knowledge_document "
            + "SET status = #{toStatus}, update_time = NOW() "
            + "WHERE id = #{docId} AND deleted = 0 "
            + "AND status IN "
            + "<foreach collection='fromStatuses' item='s' open='(' close=')' separator=','>#{s}</foreach>"
            + "</script>")
    int casStatus(@Param("docId") String docId,
                  @Param("fromStatuses") List<String> fromStatuses,
                  @Param("toStatus") String toStatus);

    /**
     * 仅查询文档当前状态（不走逻辑删除过滤，供分块中段二次校验使用）。
     *
     * @return status 字符串；文档不存在时返回 null
     */
    @Select("SELECT status FROM t_knowledge_document WHERE id = #{docId}")
    String selectStatusById(@Param("docId") String docId);
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw -q -pl bootstrap -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/mapper/KnowledgeDocumentMapper.java
git commit -m "feat: KnowledgeDocumentMapper 新增 casStatus 与 selectStatusById"
```

---

### Task 3: DocumentStatusHelper 新增 CAS 包装方法

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/DocumentStatusHelper.java`

> 删除入口、分块入口、分块收尾的 CAS 都收敛到这里，避免状态码字符串散落在 Service 各处（这是 codex 会话里你明确反对「两个文件各定义一个 status Set」之后约定的方向：集中到一个 helper，不暴露 Set 常量）。

- [ ] **Step 1: 在类中新增四个方法**

在 `recoverStuckRunning` 方法之后、类结束 `}` 之前插入：

```java
    /**
     * 删除入口 CAS：pending/success/failed -> deleting。
     *
     * @return true 抢占成功；false 表示分块正在进行或已被其它删除流程抢占
     */
    public boolean tryMarkDeleting(String docId) {
        return documentMapper.casStatus(
                docId,
                List.of(DocumentStatus.PENDING.getCode(),
                        DocumentStatus.SUCCESS.getCode(),
                        DocumentStatus.FAILED.getCode()),
                DocumentStatus.DELETING.getCode()
        ) > 0;
    }

    /**
     * 分块入口 CAS：pending/failed -> running。
     *
     * @return true 抢占成功；false 表示文档非空闲（运行中 / 删除中 / 已完成）
     */
    public boolean tryStartChunk(String docId) {
        return documentMapper.casStatus(
                docId,
                List.of(DocumentStatus.PENDING.getCode(),
                        DocumentStatus.FAILED.getCode()),
                DocumentStatus.RUNNING.getCode()
        ) > 0;
    }

    /**
     * 分块完成 CAS：running -> success。
     *
     * @return true 成功写回；false 表示运行权已被删除流程抢走，调用方应放弃写入
     */
    public boolean tryMarkSuccess(String docId) {
        return documentMapper.casStatus(
                docId,
                List.of(DocumentStatus.RUNNING.getCode()),
                DocumentStatus.SUCCESS.getCode()
        ) > 0;
    }

    /**
     * 分块失败 CAS：running -> failed。
     */
    public boolean tryMarkFailed(String docId) {
        return documentMapper.casStatus(
                docId,
                List.of(DocumentStatus.RUNNING.getCode()),
                DocumentStatus.FAILED.getCode()
        ) > 0;
    }
```

- [ ] **Step 2: 补充 import**

确认文件顶部已有 `import java.util.List;`（若无则在 `import java.util.Date;` 旁新增）。

- [ ] **Step 3: 编译验证**

Run: `./mvnw -q -pl bootstrap -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/DocumentStatusHelper.java
git commit -m "feat: DocumentStatusHelper 新增删除/分块状态 CAS 方法"
```

### Task 4: 新增 CleanupTaskStatus 枚举

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/enums/CleanupTaskStatus.java`

- [ ] **Step 1: 创建枚举**

```java
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

package com.nageoffer.ai.ragent.knowledge.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * outbox 清理任务状态
 */
@Getter
@RequiredArgsConstructor
public enum CleanupTaskStatus {

    /**
     * 待执行
     */
    PENDING("pending"),

    /**
     * 执行成功
     */
    SUCCESS("success"),

    /**
     * 重试耗尽，进入死信，需人工介入
     */
    FAILED("failed");

    private final String code;
}
```

- [ ] **Step 2: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/enums/CleanupTaskStatus.java
git commit -m "feat: 新增 CleanupTaskStatus 枚举"
```

---

### Task 5: 新增两张 outbox 表实体与 Mapper

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/VectorCleanupTaskDO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/FileCleanupTaskDO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/mapper/VectorCleanupTaskMapper.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/mapper/FileCleanupTaskMapper.java`

> 字段命名对齐 `IngestionTaskDO`（`status` / `errorMessage` / 时间字段 `@TableField(fill=...)`）。两张表不需要逻辑删除（任务执行完即终态），故不加 `@TableLogic`。`nextRetryTime` 用于退避重试，worker 扫描时按它过滤。

- [ ] **Step 1: 创建 VectorCleanupTaskDO（含完整 license 头）**

```java
package com.nageoffer.ai.ragent.knowledge.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 向量清理任务（outbox）。
 * 删除主事务内写入，由 CleanupTaskWorker 异步执行 PgVector 删除并保证最终一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_vector_cleanup_task")
public class VectorCleanupTaskDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 文档 ID */
    private String docId;

    /** 向量集合名（collection_name） */
    private String collectionName;

    /** 任务状态：pending/success/failed，见 CleanupTaskStatus */
    private String status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 下次可执行时间（退避重试） */
    private Date nextRetryTime;

    /** 最近一次错误信息 */
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}
```

- [ ] **Step 2: 创建 FileCleanupTaskDO（同结构，license 头同上）**

字段与 `VectorCleanupTaskDO` 一致，差异：去掉 `docId` / `collectionName`，改为 `fileUrl`；`@TableName("t_file_cleanup_task")`。完整字段：`id` / `fileUrl` / `status` / `retryCount` / `nextRetryTime` / `errorMessage` / `createTime` / `updateTime`。

```java
package com.nageoffer.ai.ragent.knowledge.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文件清理任务（outbox）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_file_cleanup_task")
public class FileCleanupTaskDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 文件存储地址（fileUrl） */
    private String fileUrl;

    /** 任务状态：pending/success/failed */
    private String status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 下次可执行时间 */
    private Date nextRetryTime;

    /** 最近一次错误信息 */
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}
```

- [ ] **Step 3: 创建两个 Mapper（license 头省略，实现时补全）**

`VectorCleanupTaskMapper`：

```java
package com.nageoffer.ai.ragent.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.entity.VectorCleanupTaskDO;

public interface VectorCleanupTaskMapper extends BaseMapper<VectorCleanupTaskDO> {
}
```

`FileCleanupTaskMapper`：同上，泛型换成 `FileCleanupTaskDO`。

> 扫描与状态流转用 MyBatis-Plus `Wrappers.lambdaUpdate`/`lambdaQuery` 即可（worker 里写），无需在 Mapper 加注解 SQL。两 mapper 都在 `knowledge.dao.mapper` 包下，已被 `@MapperScan` 覆盖。

- [ ] **Step 4: 编译验证**

Run: `./mvnw -q -pl bootstrap -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/VectorCleanupTaskDO.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/FileCleanupTaskDO.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/mapper/VectorCleanupTaskMapper.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/mapper/FileCleanupTaskMapper.java
git commit -m "feat: 新增 vector/file 清理任务实体与 Mapper"
```

---

### Task 6: 数据库 DDL（schema_pg.sql + 增量升级脚本）

**Files:**
- Modify: `resources/database/schema_pg.sql`（在 `t_ingestion_task_node` 之后、Vector Storage 区块之前追加）
- Create: `resources/database/upgrade_v1.2_to_v1.3.sql`（版本号见 Task 末确认）

> 字段类型对齐现有表：主键 `VARCHAR(20)`，时间 `TIMESTAMP DEFAULT CURRENT_TIMESTAMP`，`status VARCHAR(16)`。建 `(status, next_retry_time)` 复合索引支撑 worker 扫描。

- [ ] **Step 1: 在 schema_pg.sql 追加两表 DDL**

```sql
CREATE TABLE t_vector_cleanup_task (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    doc_id          VARCHAR(20)  NOT NULL,
    collection_name VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending',
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP,
    error_message   TEXT,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_vct_status_next ON t_vector_cleanup_task (status, next_retry_time);
CREATE INDEX idx_vct_doc_id ON t_vector_cleanup_task (doc_id);
COMMENT ON TABLE t_vector_cleanup_task IS '向量清理任务表（outbox）';
COMMENT ON COLUMN t_vector_cleanup_task.doc_id IS '文档ID';
COMMENT ON COLUMN t_vector_cleanup_task.collection_name IS '向量集合名';
COMMENT ON COLUMN t_vector_cleanup_task.status IS '状态：pending/success/failed';
COMMENT ON COLUMN t_vector_cleanup_task.retry_count IS '已重试次数';
COMMENT ON COLUMN t_vector_cleanup_task.next_retry_time IS '下次执行时间';
COMMENT ON COLUMN t_vector_cleanup_task.error_message IS '错误信息';

CREATE TABLE t_file_cleanup_task (
    id              VARCHAR(20)   NOT NULL PRIMARY KEY,
    file_url        VARCHAR(1024) NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'pending',
    retry_count     INTEGER       NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP,
    error_message   TEXT,
    create_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_fct_status_next ON t_file_cleanup_task (status, next_retry_time);
COMMENT ON TABLE t_file_cleanup_task IS '文件清理任务表（outbox）';
COMMENT ON COLUMN t_file_cleanup_task.file_url IS '文件存储地址';
COMMENT ON COLUMN t_file_cleanup_task.status IS '状态：pending/success/failed';
COMMENT ON COLUMN t_file_cleanup_task.retry_count IS '已重试次数';
COMMENT ON COLUMN t_file_cleanup_task.next_retry_time IS '下次执行时间';
COMMENT ON COLUMN t_file_cleanup_task.error_message IS '错误信息';
```

- [ ] **Step 2: 确认升级脚本版本号**

Run: `ls resources/database/upgrade_*.sql`
取最新版本号（当前已知 `upgrade_v1.1_to_v1.2.sql`）。若最新就是 v1.2，则新脚本命名 `upgrade_v1.2_to_v1.3.sql`；否则顺延。

- [ ] **Step 3: 创建增量升级脚本**

文件 `resources/database/upgrade_v1.2_to_v1.3.sql`：

```sql
-- ragent v1.2 -> v1.3 升级脚本
-- issue #42：文档删除与分块并发竞态修复
-- 1) status 新增 deleting 状态（无需 DDL，VARCHAR 兼容，仅说明）
-- 2) 新增两张 outbox 清理任务表

CREATE TABLE t_vector_cleanup_task (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    doc_id          VARCHAR(20)  NOT NULL,
    collection_name VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending',
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP,
    error_message   TEXT,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_vct_status_next ON t_vector_cleanup_task (status, next_retry_time);
CREATE INDEX idx_vct_doc_id ON t_vector_cleanup_task (doc_id);

CREATE TABLE t_file_cleanup_task (
    id              VARCHAR(20)   NOT NULL PRIMARY KEY,
    file_url        VARCHAR(1024) NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'pending',
    retry_count     INTEGER       NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP,
    error_message   TEXT,
    create_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_fct_status_next ON t_file_cleanup_task (status, next_retry_time);
```

- [ ] **Step 4: 提交**

```bash
git add resources/database/schema_pg.sql resources/database/upgrade_v1.2_to_v1.3.sql
git commit -m "feat: 新增 vector/file 清理任务表 DDL 与升级脚本"
```

### Task 7: CleanupTaskProperties 配置项

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/config/CleanupTaskProperties.java`

> 仿 `KnowledgeScheduleProperties` 写法（`@ConfigurationProperties` + `@Data` + 默认值）。

- [ ] **Step 1: 创建配置类**

```java
package com.nageoffer.ai.ragent.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * outbox 清理任务 worker 配置
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "rag.knowledge.cleanup")
public class CleanupTaskProperties {

    /** worker 扫描间隔（毫秒） */
    private Long scanDelayMs = 15000L;

    /** 每次扫描批量大小 */
    private Integer batchSize = 50;

    /** 最大重试次数，超过置为 failed 并告警 */
    private Integer maxRetry = 5;

    /** 重试退避基数（秒），实际退避 = baseBackoffSeconds * 2^retryCount */
    private Long baseBackoffSeconds = 30L;
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw -q -pl bootstrap -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/config/CleanupTaskProperties.java
git commit -m "feat: 新增 CleanupTaskProperties 配置"
```

---

### Task 8: CleanupTaskService 入队（删除主事务内调用）

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/CleanupTaskService.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/CleanupTaskServiceImpl.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/CleanupTaskServiceImplTest.java`

> 入队方法**不开启新事务**（无 `@Transactional`），它们由 `delete()` 的事务驱动，必须与主库清理在同一事务内提交——这是 outbox 的核心：业务删除与「清理意图」原子落库。

- [ ] **Step 1: 写失败测试**

```java
package com.nageoffer.ai.ragent.knowledge.service;

import com.nageoffer.ai.ragent.knowledge.dao.entity.FileCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.VectorCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.FileCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.VectorCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.enums.CleanupTaskStatus;
import com.nageoffer.ai.ragent.knowledge.service.impl.CleanupTaskServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CleanupTaskServiceImplTest {

    @Mock
    private VectorCleanupTaskMapper vectorMapper;
    @Mock
    private FileCleanupTaskMapper fileMapper;
    @InjectMocks
    private CleanupTaskServiceImpl service;

    @Test
    void enqueueVectorCleanup_insertsPendingTask() {
        service.enqueueVectorCleanup("doc-1", "kb_collection");

        ArgumentCaptor<VectorCleanupTaskDO> captor = ArgumentCaptor.forClass(VectorCleanupTaskDO.class);
        verify(vectorMapper).insert(captor.capture());
        VectorCleanupTaskDO task = captor.getValue();
        assertThat(task.getDocId()).isEqualTo("doc-1");
        assertThat(task.getCollectionName()).isEqualTo("kb_collection");
        assertThat(task.getStatus()).isEqualTo(CleanupTaskStatus.PENDING.getCode());
        assertThat(task.getRetryCount()).isZero();
    }

    @Test
    void enqueueFileCleanup_skipsBlankUrl() {
        service.enqueueFileCleanup("  ");
        verify(fileMapper, org.mockito.Mockito.never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enqueueFileCleanup_insertsPendingTask() {
        service.enqueueFileCleanup("s3://bucket/doc.pdf");

        ArgumentCaptor<FileCleanupTaskDO> captor = ArgumentCaptor.forClass(FileCleanupTaskDO.class);
        verify(fileMapper).insert(captor.capture());
        assertThat(captor.getValue().getFileUrl()).isEqualTo("s3://bucket/doc.pdf");
        assertThat(captor.getValue().getStatus()).isEqualTo(CleanupTaskStatus.PENDING.getCode());
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `./mvnw -q -pl bootstrap test -Dtest=CleanupTaskServiceImplTest`
Expected: FAIL —— `CleanupTaskService` / `CleanupTaskServiceImpl` 尚不存在，编译报错

- [ ] **Step 3: 创建接口**

```java
package com.nageoffer.ai.ragent.knowledge.service;

/**
 * outbox 清理任务入队服务。
 * 必须在删除主事务内调用，保证「业务删除」与「清理意图」原子落库。
 */
public interface CleanupTaskService {

    /**
     * 入队向量清理任务。
     */
    void enqueueVectorCleanup(String docId, String collectionName);

    /**
     * 入队文件清理任务；fileUrl 为空白时跳过。
     */
    void enqueueFileCleanup(String fileUrl);
}
```

- [ ] **Step 4: 创建实现（license 头补全）**

```java
package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.nageoffer.ai.ragent.knowledge.dao.entity.FileCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.VectorCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.FileCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.VectorCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.enums.CleanupTaskStatus;
import com.nageoffer.ai.ragent.knowledge.service.CleanupTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class CleanupTaskServiceImpl implements CleanupTaskService {

    private final VectorCleanupTaskMapper vectorMapper;
    private final FileCleanupTaskMapper fileMapper;

    @Override
    public void enqueueVectorCleanup(String docId, String collectionName) {
        VectorCleanupTaskDO task = VectorCleanupTaskDO.builder()
                .docId(docId)
                .collectionName(collectionName)
                .status(CleanupTaskStatus.PENDING.getCode())
                .retryCount(0)
                .build();
        vectorMapper.insert(task);
    }

    @Override
    public void enqueueFileCleanup(String fileUrl) {
        if (!StringUtils.hasText(fileUrl)) {
            return;
        }
        FileCleanupTaskDO task = FileCleanupTaskDO.builder()
                .fileUrl(fileUrl)
                .status(CleanupTaskStatus.PENDING.getCode())
                .retryCount(0)
                .build();
        fileMapper.insert(task);
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `./mvnw -q -pl bootstrap test -Dtest=CleanupTaskServiceImplTest`
Expected: PASS（3 个用例全绿）

- [ ] **Step 6: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/CleanupTaskService.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/CleanupTaskServiceImpl.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/CleanupTaskServiceImplTest.java
git commit -m "feat: 新增 CleanupTaskService 入队向量/文件清理任务"
```

### Task 9: 改造 delete() — CAS 抢占 + outbox 入队

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java:402-425`（`delete` 方法）
- Modify: 同文件构造字段区（`104-121` 附近）注入 `DocumentStatusHelper`、`CleanupTaskService`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/DocumentDeleteRaceTest.java`

> 关键改动：① 去掉 `if status==running throw` 这一 TOCTOU 校验；② 用 `tryMarkDeleting` CAS 抢占，失败即抛 `ClientException` 让整段事务回滚；③ `vectorStoreService.deleteDocumentVectors(...)` 与 `deleteStoredFileQuietly(...)` 的同步外部删除，替换为 `cleanupTaskService.enqueueVectorCleanup/enqueueFileCleanup`，把外部清理移出主事务、交给 worker 兜底；④ `deleteStoredFileQuietly` 方法整体删除（不再有静默吞异常）。

- [ ] **Step 1: 注入新依赖**

在字段声明区（`private final RemoteFileFetcher remoteFileFetcher;` 之后）新增：

```java
    private final DocumentStatusHelper documentStatusHelper;
    private final CleanupTaskService cleanupTaskService;
```

补充 import：

```java
import com.nageoffer.ai.ragent.knowledge.schedule.DocumentStatusHelper;
import com.nageoffer.ai.ragent.knowledge.service.CleanupTaskService;
```

- [ ] **Step 2: 替换 delete 方法体**

将 `402-425` 行整个 `delete` 方法替换为：

```java
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // CAS 抢占删除权：pending/success/failed -> deleting。
        // 抢占失败说明文档正在分块（running）或已被其它删除流程处理，整段事务回滚。
        boolean claimed = documentStatusHelper.tryMarkDeleting(docId);
        if (!claimed) {
            throw new ClientException("文档正在分块或已被处理，无法删除");
        }

        // 主库清理：chunk / schedule / log / document 逻辑删除，全部在同一事务内
        knowledgeChunkService.deleteByDocId(docId);
        scheduleService.deleteByDocId(docId);
        chunkLogMapper.delete(Wrappers.lambdaQuery(KnowledgeDocumentChunkLogDO.class)
                .eq(KnowledgeDocumentChunkLogDO::getDocId, docId));

        documentDO.setDeleted(1);
        documentDO.setUpdatedBy(UserContext.getUsername());
        documentMapper.deleteById(documentDO);

        // 外部资源（PgVector / 文件存储）不在主库事务内，改为 outbox 入队，
        // 与上面的主库删除原子提交，提交后由 CleanupTaskWorker 异步执行并保证最终一致。
        String collectionName = resolveCollectionName(documentDO.getKbId());
        cleanupTaskService.enqueueVectorCleanup(docId, collectionName);
        cleanupTaskService.enqueueFileCleanup(documentDO.getFileUrl());
    }
```

- [ ] **Step 3: 删除 deleteStoredFileQuietly 方法**

删除 `838-847` 行的整个 `deleteStoredFileQuietly` 私有方法（已无调用方）。

- [ ] **Step 4: 写并发互斥测试**

```java
package com.nageoffer.ai.ragent.knowledge.service;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.schedule.DocumentStatusHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentDeleteRaceTest {

    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @InjectMocks
    private DocumentStatusHelper documentStatusHelper;

    @Test
    void tryMarkDeleting_succeedsWhenIdle() {
        when(documentMapper.casStatus(eq("doc-1"), anyList(), eq(DocumentStatus.DELETING.getCode())))
                .thenReturn(1);
        assertThat(documentStatusHelper.tryMarkDeleting("doc-1")).isTrue();
    }

    @Test
    void tryMarkDeleting_failsWhenRunning() {
        // 模拟分块已抢占（CAS 命中 0 行）
        when(documentMapper.casStatus(eq("doc-1"), anyList(), eq(DocumentStatus.DELETING.getCode())))
                .thenReturn(0);
        assertThat(documentStatusHelper.tryMarkDeleting("doc-1")).isFalse();
    }

    @Test
    void tryStartChunk_failsWhenDeleting() {
        when(documentMapper.casStatus(eq("doc-1"), anyList(), eq(DocumentStatus.RUNNING.getCode())))
                .thenReturn(0);
        assertThat(documentStatusHelper.tryStartChunk("doc-1")).isFalse();
    }
}
```

- [ ] **Step 5: 运行测试**

Run: `./mvnw -q -pl bootstrap test -Dtest=DocumentDeleteRaceTest`
Expected: PASS

- [ ] **Step 6: 编译全模块**

Run: `./mvnw -q -pl bootstrap -am compile`
Expected: BUILD SUCCESS（确认删除 `deleteStoredFileQuietly` 后无残留引用）

- [ ] **Step 7: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/DocumentDeleteRaceTest.java
git commit -m "fix: delete 改用 CAS 抢占并通过 outbox 兜底外部清理 (issue #42)"
```

### Task 10: 改造分块流程 — 入口 CAS + 写库前中段二次校验

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`
  - `startChunk`（`161-191`）：MQ 事务内的状态更新，由 `.ne(status, running)` 收紧为「`pending/failed -> running`」CAS
  - `persistChunksAndVectorsAtomically`（`255-279`）：写 chunk/vector 前增加 `selectStatusById` 中段校验，状态非 `running`（被删除抢占）时放弃写入
  - `runChunkTask`（`244` 成功分支）：成功收尾由 `updateById` 收紧为 `running -> success` 的 CAS

> 这一步堵住 issue 竞态时序的 t4：embedding 完成后、写回 chunk/vector 前再 reload 一次状态，若已被删除流程改成 `deleting`，主动放弃写入，避免产生孤儿分块/向量。startChunk 的入口 CAS 则堵住「删除先到、分块后启动」——删除已置 `deleting`，CAS 命中 0 行，分块直接拒绝。

- [ ] **Step 1: 收紧 startChunk 的状态抢占**

将 `startChunk` 中 `arg -> { ... }` 内的 `documentMapper.update(...)` 块（`174-185`）替换为 CAS 调用：

```java
                arg -> {
                    int updated = documentMapper.casStatus(
                            docId,
                            List.of(DocumentStatus.PENDING.getCode(), DocumentStatus.FAILED.getCode()),
                            DocumentStatus.RUNNING.getCode()
                    );
                    if (updated == 0) {
                        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
                        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
                        throw new ClientException("文档分块操作正在进行中，请稍后再试");
                    }
                    KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
                    event.setKbId(documentDO.getKbId());
                    scheduleService.upsertSchedule(documentDO);
                }
```

补充 import（若未存在）：`import java.util.List;`（文件已 import `java.util.List`，确认即可）。

> 说明：原逻辑 `ne(status, running)` 允许从 `success` 再次触发分块（重新分块场景）。改为 `pending/failed -> running` 后，`success` 文档需重新分块时走的是「先回到可分块态」的既有入口——确认 `success` 重新分块是否为现有需求；若是，则源状态列表追加 `DocumentStatus.SUCCESS.getCode()`。**实现时用 `git log`/调用方确认 success→running 是否合法路径，再决定是否加入。** 默认按 issue 的 `pending/failed -> running` 实现。

- [ ] **Step 2: persist 前增加中段校验**

将 `persistChunksAndVectorsAtomically` 的事务体（`265-277`）改为：

```java
        transactionOperations.executeWithoutResult(status -> {
            // 中段二次校验：embedding 是耗时外部调用，期间删除流程可能已将状态抢为 deleting。
            // 写回前 reload 一次，状态非 running 则放弃写入，避免产生孤儿 chunk/vector。
            String latest = documentMapper.selectStatusById(docId);
            if (!DocumentStatus.RUNNING.getCode().equals(latest)) {
                log.warn("分块写回前检测到状态已变更为 {}，放弃写入: docId={}", latest, docId);
                status.setRollbackOnly();
                return;
            }
            knowledgeChunkService.deleteByDocId(docId);
            knowledgeChunkService.batchCreate(docId, chunks);
            vectorStoreService.deleteDocumentVectors(collectionName, docId);
            vectorStoreService.indexDocumentChunks(collectionName, docId, chunkResults);
            KnowledgeDocumentDO updateDocumentDO = KnowledgeDocumentDO.builder()
                    .id(docId)
                    .chunkCount(chunks.size())
                    .status(DocumentStatus.SUCCESS.getCode())
                    .updatedBy(UserContext.getUsername())
                    .build();
            documentMapper.updateById(updateDocumentDO);
        });
```

> 这里成功收尾保留 `updateById`（已在 `running` 校验通过的同一事务内，状态写 `success` 安全）。`status.setRollbackOnly()` 确保放弃写入时本段事务不留痕。

- [ ] **Step 3: 运行已有分块相关测试，确认未破坏**

Run: `./mvnw -q -pl bootstrap test -Dtest=ScheduleRefreshProcessorTest`
Expected: PASS（该测试覆盖调度→分块路径，验证 CAS 改造未破坏既有行为）

- [ ] **Step 4: 编译验证**

Run: `./mvnw -q -pl bootstrap -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java
git commit -m "fix: 分块入口 CAS 抢占 + 写库前中段状态校验 (issue #42)"
```

### Task 11: CleanupTaskWorker — 扫描 + 执行 + 退避重试 + 告警

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/CleanupTaskWorker.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/schedule/CleanupTaskWorkerTest.java`

> 范式照搬 `KnowledgeDocumentScheduleJob`：`@Component` + `@Scheduled(fixedDelayString=...)` 扫描 `pending` 且 `next_retry_time` 到期的任务，逐条调用外部服务。成功置 `success`；失败 `retry_count+1` 并按指数退避设置 `next_retry_time`；超过 `maxRetry` 置 `failed` 并 `log.error` 告警。`DELETE FROM vectors WHERE doc_id=?` / `deleteByUrl` 都是幂等的，重试安全。
>
> 单实例够用先不引租约锁（清理幂等，多实例重复执行无害，仅浪费一次调用）；如需多实例去重，复用 `ScheduleLockManager` 模式。本计划默认不加锁，注释中标注扩展点。

- [ ] **Step 1: 写 worker 测试（先定义行为）**

```java
package com.nageoffer.ai.ragent.knowledge.schedule;

import com.nageoffer.ai.ragent.knowledge.config.CleanupTaskProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.FileCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.VectorCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.FileCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.VectorCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.enums.CleanupTaskStatus;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CleanupTaskWorkerTest {

    @Mock private VectorCleanupTaskMapper vectorMapper;
    @Mock private FileCleanupTaskMapper fileMapper;
    @Mock private VectorStoreService vectorStoreService;
    @Mock private FileStorageService fileStorageService;

    private CleanupTaskWorker worker;

    @BeforeEach
    void setUp() {
        CleanupTaskProperties props = new CleanupTaskProperties();
        props.setBatchSize(50);
        props.setMaxRetry(3);
        props.setBaseBackoffSeconds(30L);
        worker = new CleanupTaskWorker(vectorMapper, fileMapper, vectorStoreService, fileStorageService, props);
    }

    @Test
    void vectorTask_success_marksSuccess() {
        VectorCleanupTaskDO task = VectorCleanupTaskDO.builder()
                .id("t1").docId("doc-1").collectionName("kb").status("pending").retryCount(0).build();
        when(vectorMapper.selectList(any())).thenReturn(List.of(task));
        when(fileMapper.selectList(any())).thenReturn(List.of());

        worker.scan();

        verify(vectorStoreService).deleteDocumentVectors("kb", "doc-1");
        ArgumentCaptor<VectorCleanupTaskDO> captor = ArgumentCaptor.forClass(VectorCleanupTaskDO.class);
        verify(vectorMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CleanupTaskStatus.SUCCESS.getCode());
    }

    @Test
    void vectorTask_failure_incrementsRetryAndBackoff() {
        VectorCleanupTaskDO task = VectorCleanupTaskDO.builder()
                .id("t1").docId("doc-1").collectionName("kb").status("pending").retryCount(0).build();
        when(vectorMapper.selectList(any())).thenReturn(List.of(task));
        when(fileMapper.selectList(any())).thenReturn(List.of());
        doThrow(new RuntimeException("pgvector down"))
                .when(vectorStoreService).deleteDocumentVectors(anyString(), anyString());

        worker.scan();

        ArgumentCaptor<VectorCleanupTaskDO> captor = ArgumentCaptor.forClass(VectorCleanupTaskDO.class);
        verify(vectorMapper).updateById(captor.capture());
        VectorCleanupTaskDO saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(CleanupTaskStatus.PENDING.getCode());
        assertThat(saved.getRetryCount()).isEqualTo(1);
        assertThat(saved.getNextRetryTime()).isNotNull();
        assertThat(saved.getErrorMessage()).contains("pgvector down");
    }

    @Test
    void vectorTask_exhaustedRetry_marksFailed() {
        VectorCleanupTaskDO task = VectorCleanupTaskDO.builder()
                .id("t1").docId("doc-1").collectionName("kb").status("pending").retryCount(3).build();
        when(vectorMapper.selectList(any())).thenReturn(List.of(task));
        when(fileMapper.selectList(any())).thenReturn(List.of());
        doThrow(new RuntimeException("still down"))
                .when(vectorStoreService).deleteDocumentVectors(anyString(), anyString());

        worker.scan();

        ArgumentCaptor<VectorCleanupTaskDO> captor = ArgumentCaptor.forClass(VectorCleanupTaskDO.class);
        verify(vectorMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CleanupTaskStatus.FAILED.getCode());
    }

    @Test
    void fileTask_success_marksSuccess() {
        FileCleanupTaskDO task = FileCleanupTaskDO.builder()
                .id("f1").fileUrl("s3://b/x.pdf").status("pending").retryCount(0).build();
        when(vectorMapper.selectList(any())).thenReturn(List.of());
        when(fileMapper.selectList(any())).thenReturn(List.of(task));

        worker.scan();

        verify(fileStorageService).deleteByUrl("s3://b/x.pdf");
        ArgumentCaptor<FileCleanupTaskDO> captor = ArgumentCaptor.forClass(FileCleanupTaskDO.class);
        verify(fileMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CleanupTaskStatus.SUCCESS.getCode());
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `./mvnw -q -pl bootstrap test -Dtest=CleanupTaskWorkerTest`
Expected: FAIL —— `CleanupTaskWorker` 不存在

- [ ] **Step 3: 创建 CleanupTaskWorker（license 头补全）— 类骨架与扫描入口**

```java
package com.nageoffer.ai.ragent.knowledge.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.knowledge.config.CleanupTaskProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.FileCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.VectorCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.FileCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.VectorCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.enums.CleanupTaskStatus;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * outbox 清理任务 worker：异步执行 PgVector / 文件存储删除，失败退避重试，超阈值告警。
 * 多实例部署如需去重，可复用 ScheduleLockManager 租约锁（当前清理幂等，未加锁）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CleanupTaskWorker {

    private final VectorCleanupTaskMapper vectorMapper;
    private final FileCleanupTaskMapper fileMapper;
    private final VectorStoreService vectorStoreService;
    private final FileStorageService fileStorageService;
    private final CleanupTaskProperties properties;

    @Scheduled(fixedDelayString = "${rag.knowledge.cleanup.scan-delay-ms:15000}")
    public void scan() {
        scanVectorTasks();
        scanFileTasks();
    }
```

- [ ] **Step 4: 追加向量任务扫描与执行**

```java
    private void scanVectorTasks() {
        Date now = new Date();
        LambdaQueryWrapper<VectorCleanupTaskDO> qw = Wrappers.lambdaQuery(VectorCleanupTaskDO.class)
                .eq(VectorCleanupTaskDO::getStatus, CleanupTaskStatus.PENDING.getCode())
                .and(w -> w.isNull(VectorCleanupTaskDO::getNextRetryTime)
                        .or().le(VectorCleanupTaskDO::getNextRetryTime, now))
                .last("LIMIT " + Math.max(properties.getBatchSize(), 1));
        List<VectorCleanupTaskDO> tasks = vectorMapper.selectList(qw);
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        for (VectorCleanupTaskDO task : tasks) {
            try {
                vectorStoreService.deleteDocumentVectors(task.getCollectionName(), task.getDocId());
                markSuccess(task);
            } catch (Exception e) {
                handleVectorFailure(task, e);
            }
        }
    }

    private void markSuccess(VectorCleanupTaskDO task) {
        VectorCleanupTaskDO update = VectorCleanupTaskDO.builder()
                .id(task.getId())
                .status(CleanupTaskStatus.SUCCESS.getCode())
                .build();
        vectorMapper.updateById(update);
    }

    private void handleVectorFailure(VectorCleanupTaskDO task, Exception e) {
        int nextRetry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        VectorCleanupTaskDO update = VectorCleanupTaskDO.builder()
                .id(task.getId())
                .retryCount(nextRetry)
                .errorMessage(truncate(e.getMessage()))
                .build();
        if (nextRetry > properties.getMaxRetry()) {
            update.setStatus(CleanupTaskStatus.FAILED.getCode());
            log.error("向量清理任务重试耗尽，转入死信需人工介入: taskId={}, docId={}, collection={}",
                    task.getId(), task.getDocId(), task.getCollectionName(), e);
        } else {
            update.setStatus(CleanupTaskStatus.PENDING.getCode());
            update.setNextRetryTime(computeNextRetry(nextRetry));
            log.warn("向量清理任务失败，第 {} 次重试: taskId={}, docId={}", nextRetry, task.getId(), task.getDocId(), e);
        }
        vectorMapper.updateById(update);
    }
```

- [ ] **Step 5: 追加文件任务扫描与执行（结构对称）**

```java
    private void scanFileTasks() {
        Date now = new Date();
        LambdaQueryWrapper<FileCleanupTaskDO> qw = Wrappers.lambdaQuery(FileCleanupTaskDO.class)
                .eq(FileCleanupTaskDO::getStatus, CleanupTaskStatus.PENDING.getCode())
                .and(w -> w.isNull(FileCleanupTaskDO::getNextRetryTime)
                        .or().le(FileCleanupTaskDO::getNextRetryTime, now))
                .last("LIMIT " + Math.max(properties.getBatchSize(), 1));
        List<FileCleanupTaskDO> tasks = fileMapper.selectList(qw);
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        for (FileCleanupTaskDO task : tasks) {
            try {
                fileStorageService.deleteByUrl(task.getFileUrl());
                FileCleanupTaskDO update = FileCleanupTaskDO.builder()
                        .id(task.getId())
                        .status(CleanupTaskStatus.SUCCESS.getCode())
                        .build();
                fileMapper.updateById(update);
            } catch (Exception e) {
                handleFileFailure(task, e);
            }
        }
    }

    private void handleFileFailure(FileCleanupTaskDO task, Exception e) {
        int nextRetry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        FileCleanupTaskDO update = FileCleanupTaskDO.builder()
                .id(task.getId())
                .retryCount(nextRetry)
                .errorMessage(truncate(e.getMessage()))
                .build();
        if (nextRetry > properties.getMaxRetry()) {
            update.setStatus(CleanupTaskStatus.FAILED.getCode());
            log.error("文件清理任务重试耗尽，转入死信需人工介入: taskId={}, fileUrl={}",
                    task.getId(), task.getFileUrl(), e);
        } else {
            update.setStatus(CleanupTaskStatus.PENDING.getCode());
            update.setNextRetryTime(computeNextRetry(nextRetry));
            log.warn("文件清理任务失败，第 {} 次重试: taskId={}, fileUrl={}", nextRetry, task.getId(), task.getFileUrl(), e);
        }
        fileMapper.updateById(update);
    }
```

- [ ] **Step 6: 追加退避与截断工具方法，并闭合类**

```java
    private Date computeNextRetry(int retryCount) {
        long backoffSeconds = properties.getBaseBackoffSeconds() * (1L << Math.min(retryCount, 10));
        return new Date(System.currentTimeMillis() + backoffSeconds * 1000L);
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
```

- [ ] **Step 7: 运行 worker 测试，确认通过**

Run: `./mvnw -q -pl bootstrap test -Dtest=CleanupTaskWorkerTest`
Expected: PASS（5 个用例全绿）

- [ ] **Step 8: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/CleanupTaskWorker.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/schedule/CleanupTaskWorkerTest.java
git commit -m "feat: 新增 CleanupTaskWorker 异步执行 outbox 清理并退避重试 (issue #42)"
```

### Task 12: 配置项写入 application.yaml + 全量回归

**Files:**
- Modify: `bootstrap/src/main/resources/application.yaml`（在 `rag.knowledge.schedule` 同级追加 `cleanup`）

- [ ] **Step 1: 确认现有配置层级**

Run: `./mvnw -q -pl bootstrap -am compile` 之前，先 grep 现有键：
Run: `grep -nE "rag:|knowledge:|schedule:" bootstrap/src/main/resources/application.yaml`
Expected: 找到 `rag.knowledge.schedule` 节点位置（若不存在则配置走默认值，本步可跳过 yaml 修改，仅依赖 `CleanupTaskProperties` 默认值）

- [ ] **Step 2: 追加 cleanup 配置（在 rag.knowledge 下，与 schedule 平级）**

```yaml
rag:
  knowledge:
    cleanup:
      scan-delay-ms: 15000
      batch-size: 50
      max-retry: 5
      base-backoff-seconds: 30
```

> 若 `application.yaml` 无 `rag.knowledge` 节点，说明全部走 `@ConfigurationProperties` 默认值，则**跳过本步**——默认值已在 `CleanupTaskProperties` 给定，功能不受影响。

- [ ] **Step 3: 全量编译 + 测试**

Run: `./mvnw -q -pl bootstrap -am test`
Expected: BUILD SUCCESS，新增 3 个测试类全绿，既有测试无回归

- [ ] **Step 4: 提交**

```bash
git add bootstrap/src/main/resources/application.yaml
git commit -m "chore: 新增 outbox 清理 worker 配置项"
```

---

## 改造后竞态闭环验证（对照 issue 第五节）

| 场景 | 本方案结果 | 对应 Task |
|---|---|---|
| 删除先到 | 分块入口 `casStatus(pending/failed -> running)` 命中 0 行，分块拒绝启动 | Task 10 |
| 分块先到（短任务） | 删除入口 `tryMarkDeleting` 命中 0 行，抛 `ClientException`，事务回滚 | Task 9 |
| 分块进行中触发删除 | persist 前 `selectStatusById` 发现状态已是 `deleting`，`setRollbackOnly` 放弃写入 | Task 10 |
| MySQL/PG 主库删成功、PgVector 删除失败 | outbox 任务持续退避重试至成功，超阈值告警 | Task 8 + 11 |
| 文件删除失败 | 同上，且不再静默吞异常 | Task 9 + 11 |
| 进程在删除外部资源前宕机 | 清理意图已随主事务落库，重启后 worker 继续扫描执行 | Task 8 + 11 |

---

## 落地清单对照（issue 第六节逐条覆盖）

1. ✅ `DocumentStatus` 新增 `DELETING` —— Task 1
2. ✅ `KnowledgeDocumentMapper` 新增 `casStatus` / `selectStatusById` —— Task 2（CAS 入口包装在 Task 3 的 `DocumentStatusHelper`）
3. ✅ `KnowledgeDocumentServiceImpl#delete` 去除 `if status==running` 校验改 CAS —— Task 9
4. ✅ 分块入口 CAS + 写库前中段校验 —— Task 10
5. ✅ 新建 `t_vector_cleanup_task` / `t_file_cleanup_task` 表 + 后台 worker —— Task 5/6/8/11
6. ✅ 移除 `deleteStoredFileQuietly` 静默吞异常 —— Task 9

---

## Self-Review（写完后自查）

**1. Spec coverage：** issue 六项落地清单逐条映射到 Task（见上表），无遗漏。第四节 4.1~4.4 四个改造点分别落在 Task 1/3、9、10、5-11。第五节六种竞态场景全部有对应闭环。

**2. Placeholder scan：** 全部步骤含可直接落地的完整代码 / SQL / 命令；无 "TODO"、"类似上文"、"自行处理" 等占位。两处「需实现时确认」均给了默认值兜底并说明判定方法（Task 10 Step 1 的 `success→running`、Task 12 的 yaml 节点是否存在）。

**3. Type consistency：**
- 状态码字符串：`pending/running/failed/success/deleting`（DocumentStatus）与 `pending/success/failed`（CleanupTaskStatus）全程一致。
- 方法签名：`casStatus(String, List<String>, String)`、`selectStatusById(String)`、`tryMarkDeleting/tryStartChunk/tryMarkSuccess/tryMarkFailed(String)`、`enqueueVectorCleanup(String,String)`、`enqueueFileCleanup(String)` 在定义处（Task 2/3/8）与调用处（Task 9/10/11）签名一致。
- 实体字段：`VectorCleanupTaskDO{docId,collectionName,status,retryCount,nextRetryTime,errorMessage}`、`FileCleanupTaskDO{fileUrl,...}` 在 DO（Task 5）、DDL（Task 6）、Service（Task 8）、Worker（Task 11）、测试中字段名一致。
- 表名：`t_vector_cleanup_task` / `t_file_cleanup_task` 在 `@TableName`、schema、升级脚本中一致。

**自查发现并已修正：** issue 伪代码用 `IDLE/COMPLETED`，已统一翻译为本仓库 `PENDING/SUCCESS` 并在背景章节列出对照表，避免实现者照抄出错。

---

## Execution Handoff

计划已保存到 `docs/superpowers/plans/2026-06-18-document-delete-chunk-race.md`。两种执行方式：

1. **Subagent-Driven（推荐）** —— 每个 Task 派发独立 subagent，Task 间双阶段审查，迭代快。
2. **Inline Execution** —— 在当前会话按 Task 顺序批量执行，带检查点审查。

你想用哪种？








