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

package com.nageoffer.ai.ragent.rag.config;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.core.storage.ObjectStorageClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 对象存储桶启动初始化器
 * <p>
 * 系统启动时自动创建两个全局桶，免去运维手动建桶/配权限：
 * <ul>
 *   <li>知识库桶 {@code rag.storage.kb-bucket}：私有，承载所有知识库文档，按 collectionName 目录隔离</li>
 *   <li>资产桶 {@code rag.storage.asset-bucket}：公共读，PDF 抽出的图片等需被浏览器匿名直连预览</li>
 * </ul>
 * 集群环境下用 Redisson 分布式锁保证只建一次：先判断是否存在 → 拿锁 → 双重检查 → 建桶
 * <p>
 * 失败策略：快速失败，存储 / Redis 不可用时让异常向上抛，启动即暴露问题
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageInitializer {

    private static final String LOCK_KEY_PREFIX = "ragent:storage:bucket:init:";
    private static final long LOCK_WAIT_SECONDS = 30;

    private final ObjectStorageClient objectStorageClient;
    private final RedissonClient redissonClient;
    private final RagStorageProperties properties;

    @PostConstruct
    public void initBuckets() {
        ensureBucket(properties.getKbBucket(), false);
        ensureBucket(properties.getAssetBucket(), true);
    }

    private void ensureBucket(String bucket, boolean publicRead) {
        ensureBucketExists(bucket);
        if (publicRead) {
            // 幂等下发公共读：无论新建还是已存在，都保证桶内对象可被浏览器匿名直连预览
            objectStorageClient.setBucketPublicRead(bucket);
            log.info("对象存储桶就绪（公共读）bucket={}", bucket);
        } else {
            log.info("对象存储桶就绪 bucket={}", bucket);
        }
    }

    private void ensureBucketExists(String bucket) {
        if (objectStorageClient.bucketExists(bucket)) {
            return;
        }

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + bucket);
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("对象存储桶初始化获取分布式锁被中断 bucket=" + bucket);
        }
        if (!locked) {
            throw new ServiceException("对象存储桶初始化获取分布式锁超时 bucket=" + bucket);
        }

        try {
            if (objectStorageClient.bucketExists(bucket)) {
                return;
            }
            objectStorageClient.createBucket(bucket);
            log.info("对象存储桶创建成功 bucket={}", bucket);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
