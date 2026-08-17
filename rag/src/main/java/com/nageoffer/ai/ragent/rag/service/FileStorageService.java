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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * 文件存储服务：后端无关的高层门面
 * <p>
 * 所有知识库文档共用一个全局桶，按 {@code namespace}（= 知识库 collectionName）划分目录（key 前缀）；
 * 多模态资产落公共读资产桶。底层通过 {@link com.nageoffer.ai.ragent.rag.core.storage.ObjectStorageClient}
 * 在 S3 兼容存储与阿里云 OSS 间切换，业务代码只依赖本接口
 * <p>
 * 存储引用（{@link StoredFileDTO#getUrl()} / 文档 file_url）只保留裸 key（如 {@code {namespace}/{uuid}.ext}）：
 * 桶是部署级配置常量、不写进数据，读写时按操作语义回退到对应桶（文档→知识库桶，资产→资产桶）
 */
public interface FileStorageService {

    /**
     * 上传知识库文档（流式，低内存）
     * <p>
     * 写入全局知识库桶，对象 key = {@code {namespace}/{uuid}.{ext}}
     * 不具备 SDK 内置自动重试能力，失败需业务层自行重试
     *
     * @param namespace 知识库命名空间（collectionName）
     */
    StoredFileDTO upload(String namespace, MultipartFile file);

    /**
     * 上传知识库文档（流式，低内存）
     *
     * @param namespace 知识库命名空间（collectionName）
     */
    StoredFileDTO upload(String namespace, InputStream content, long size, String originalFilename, String contentType);

    /**
     * 上传知识库文档（流式，低内存）
     *
     * @param namespace 知识库命名空间（collectionName）
     */
    StoredFileDTO upload(String namespace, byte[] content, String originalFilename, String contentType);

    /**
     * 上传知识库文档（SDK 原生，带自动重试）
     * <p>
     * 具备自动重试机制，代价是可能将 payload 缓冲到堆内存；适用于小文件或对可靠性敏感的场景
     *
     * @param namespace 知识库命名空间（collectionName）
     */
    StoredFileDTO reliableUpload(String namespace, InputStream content, long size, String originalFilename, String contentType);

    /**
     * 上传多模态资产（公共读）
     * <p>
     * 写入资产桶，供 {@link #getPublicUrl} 转成浏览器可匿名直连的预览 URL
     */
    StoredFileDTO uploadAsset(byte[] content, String originalFilename, String contentType);

    /**
     * 打开我方文档的输入流（落知识库桶）
     *
     * @param key 对象裸 key（如 {@code {namespace}/{uuid}.ext}）
     */
    InputStream openStream(String key);

    /**
     * 删除我方文档（落知识库桶）
     *
     * @param key 对象裸 key
     */
    void deleteByUrl(String key);

    /**
     * 把我方资产裸 key 转为浏览器可匿名直连的公开预览 URL（落资产桶）
     * <p>
     * 要求资产桶已开公共读，仅用于多模态资产等可公开预览的对象
     *
     * @param key 资产对象裸 key
     * @return 公开 HTTP URL
     */
    String getPublicUrl(String key);

    /**
     * 创建知识库空间（幂等）：在全局知识库桶下建立目录（写 {@code {namespace}/} 标记对象）
     *
     * @param namespace 知识库命名空间（collectionName）
     */
    void createKnowledgeSpace(String namespace);

    /**
     * 删除知识库空间（幂等）：清空全局知识库桶下 {@code {namespace}/} 前缀的所有对象
     * <p>
     * 只删该知识库目录，绝不删桶
     *
     * @param namespace 知识库命名空间（collectionName）
     */
    void deleteKnowledgeSpace(String namespace);
}
