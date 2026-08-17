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

package com.nageoffer.ai.ragent.rag.core.storage;

import java.io.InputStream;

/**
 * 对象存储底层 SPI：只认 (bucket, key) 裸操作，不感知 namespace、key 组装与业务 DTO
 * <p>
 * S3 兼容存储（rustfs / minio）与阿里云 OSS 各一实现，由 {@code rag.storage.type} 通过 {@code @ConditionalOnProperty}
 * 二选一注册。命名空间/key 组装、桶归属、类型探测等后端无关逻辑收敛在
 * {@link com.nageoffer.ai.ragent.rag.service.impl.DefaultFileStorageService}，本接口只负责与具体存储对话
 */
public interface ObjectStorageClient {

    /**
     * 流式上传（低内存）
     * <p>
     * 各后端选择自身最省内存的方式实现：S3 用预签名 URL + HttpURLConnection 零堆流式，
     * OSS 用 SDK putObject 配合 contentLength 按块流式。不保证自动重试，失败需业务层重试
     *
     * @param bucket      桶名
     * @param key         对象键
     * @param content     内容流
     * @param size        内容字节数
     * @param contentType 已探测好的内容类型
     */
    void streamPut(String bucket, String key, InputStream content, long size, String contentType);

    /**
     * 可靠上传（SDK 原生，带自动重试）
     * <p>
     * 网络抖动/超时自动重发，代价是可能将 payload 缓冲到堆内存；适用于小文件或对可靠性敏感的场景
     */
    void reliablePut(String bucket, String key, InputStream content, long size, String contentType);

    /**
     * 打开对象读取流，调用方负责关闭
     */
    InputStream getObject(String bucket, String key);

    /**
     * 删除单个对象（幂等）
     */
    void deleteObject(String bucket, String key);

    /**
     * 按前缀分页列举并批量删除（幂等），用于删除知识库目录
     *
     * @param bucket 桶名
     * @param prefix key 前缀，如 {@code {collectionName}/}
     */
    void deleteByPrefix(String bucket, String prefix);

    /**
     * 判断对象是否存在
     */
    boolean objectExists(String bucket, String key);

    /**
     * 判断桶是否存在
     */
    boolean bucketExists(String bucket);

    /**
     * 创建桶（幂等：已存在视为成功）
     */
    void createBucket(String bucket);

    /**
     * 给桶下发公共读策略（幂等），使桶内对象可被浏览器匿名直连预览
     */
    void setBucketPublicRead(String bucket);

    /**
     * 拼装浏览器可直连的公开 URL
     * <p>
     * S3/rustfs 为 path-style（{@code {base}/{bucket}/{key}}），OSS 为虚拟主机式（{@code {bucketBase}/{key}}）
     */
    String buildPublicUrl(String bucket, String key);
}
