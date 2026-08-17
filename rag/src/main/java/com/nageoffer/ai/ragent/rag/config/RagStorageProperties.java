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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 对象存储配置
 * <p>
 * 与 {@code rag.vector.type}(pg/milvus)、{@code rag.keyword.type}(none/es) 同构，通过 {@code type}
 * 在 S3 兼容存储（rustfs / minio）与阿里云 OSS 间切换。所有知识库文档共用一个全局桶 {@code kbBucket}，
 * 每个知识库对应桶内一个目录（key 前缀 = collectionName）；多模态资产落公共读桶 {@code assetBucket}
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.storage")
public class RagStorageProperties {

    /**
     * 存储后端类型
     * 可选 s3（rustfs / minio，默认）/ oss（阿里云）
     */
    private String type = "s3";

    /**
     * 全局知识库桶：所有知识库文档共用，按 collectionName 目录隔离
     * <p>
     * 私有桶，文档仅服务端 openStream 读取，不对外直连；OSS 桶名全局唯一，生产需按部署覆盖
     */
    private String kbBucket = "ragent-sources";

    /**
     * 多模态资产桶：PDF 抽出的图片等，公共读，供浏览器匿名直连预览
     */
    private String assetBucket = "ragent-assets";

    /**
     * S3 兼容存储配置（type=s3 生效）
     */
    private S3 s3 = new S3();

    /**
     * 阿里云 OSS 配置（type=oss 生效）
     */
    private Oss oss = new Oss();

    @Data
    public static class S3 {

        /**
         * S3 服务端点，如 rustfs 的 <a href="http://localhost:9000">...</a>
         */
        private String endpoint;

        /**
         * 访问密钥 ID
         */
        private String accessKey;

        /**
         * 访问密钥
         */
        private String secretKey;

        /**
         * 区域，S3 兼容存储通常任填，默认 us-east-1
         */
        private String region = "us-east-1";

        /**
         * 是否强制 path-style 寻址：rustfs / minio 需为 true
         */
        private boolean pathStyle = true;

        /**
         * 浏览器可直连的公开基址，内外网端点不同时配置；留空回退 endpoint
         */
        private String publicUrl;

        /**
         * 公开基址：留空时回退 endpoint
         */
        public String resolvePublicUrl() {
            return StringUtils.hasText(publicUrl) ? publicUrl : endpoint;
        }
    }

    @Data
    public static class Oss {

        /**
         * OSS 服务端点，如 <a href="https://oss-cn-hangzhou.aliyuncs.com">...</a>
         */
        private String endpoint;

        /**
         * 访问密钥 ID
         */
        private String accessKey;

        /**
         * 访问密钥
         */
        private String secretKey;

        /**
         * 区域，如 cn-hangzhou，OSS V4 签名可能需要
         */
        private String region;

        /**
         * 资产公开访问基址（虚拟主机式，已含 bucket 子域），如 <a href="https://ragent-assets.oss-cn-hangzhou.aliyuncs.com">...</a>
         */
        private String publicUrl;
    }
}
