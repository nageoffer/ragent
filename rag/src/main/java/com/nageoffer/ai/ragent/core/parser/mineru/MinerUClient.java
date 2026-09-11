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

package com.nageoffer.ai.ragent.core.parser.mineru;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * MinerU SaaS HTTP 客户端
 * <p>
 * 提供四个核心方法(走"本地文件批量上传解析"链路):
 * <ul>
 *   <li>{@link #requestUpload} 申请上传链接,返回 batch_id + 上传 URL</li>
 *   <li>{@link #uploadFile} 把文件字节 PUT 上传到 MinerU OSS</li>
 *   <li>{@link #queryResult} 查询任务状态(轮询用)</li>
 *   <li>{@link #downloadZip} 下载结果 zip 字节流</li>
 * </ul>
 * <p>
 * 鉴权:HTTP header {@code Authorization: Bearer <api-key>}(上传/下载用预签名 URL,无须鉴权头)
 * 响应解析:用 {@link JsonNode} 兼容 MinerU 字段细节变化,只读关心的字段
 */
@Slf4j
@Component
public class MinerUClient {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    /** 单个结果 zip 的下载上限 */
    private static final long MAX_ZIP_BYTES = 512L * 1024 * 1024;

    /** 上游响应体进日志的前缀长度 */
    private static final int LOG_BODY_LIMIT = 500;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MinerUProperties properties;

    public MinerUClient(@Qualifier("syncHttpClient") OkHttpClient httpClient,
                        ObjectMapper objectMapper,
                        MinerUProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 申请上传链接(本地文件批量上传解析的第一步)
     * <p>
     * 只提交文件元信息(不带 url),MinerU 返回 batch_id 与预签名上传 URL
     * 拿到 URL 后须调 {@link #uploadFile} 把文件字节 PUT 上去,MinerU 才会自动提交解析
     *
     * @param request 单文件解析请求
     * @return 含 batch_id + 上传 URL 的凭证
     * @throws ServiceException 网络异常 / api-key 缺失 / SaaS 返回错误 / 缺少上传 URL
     */
    public BatchUploadTicket requestUpload(BatchSubmitRequest request) {
        requireApiKey();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("enable_formula", request.enableFormula());
        body.put("enable_table", request.enableTable());
        body.put("language", request.language() == null ? "ch" : request.language());

        ArrayNode files = body.putArray("files");
        ObjectNode file = files.addObject();
        if (request.fileName() != null) {
            file.put("name", request.fileName());
        }
        file.put("is_ocr", request.isOcr());
        if (request.dataId() != null) {
            file.put("data_id", request.dataId());
        }

        String url = properties.getApiUrl() + "/file-urls/batch";
        Request httpRequest = newJsonPost(url, body.toString());

        JsonNode root = executeAndParse(httpRequest, "requestUpload");
        ensureSuccess(root, "requestUpload");

        JsonNode data = root.path("data");
        String batchId = data.path("batch_id").asText(null);
        if (batchId == null || batchId.isBlank()) {
            log.warn("MinerU requestUpload 返回缺少 batch_id, body={}", truncate(root.toString()));
            throw new ServiceException("MinerU requestUpload 返回缺少 batch_id");
        }

        JsonNode fileUrls = data.path("file_urls");
        if (!fileUrls.isArray() || fileUrls.isEmpty()) {
            log.warn("MinerU requestUpload 返回缺少 file_urls, body={}", truncate(root.toString()));
            throw new ServiceException("MinerU requestUpload 返回缺少 file_urls");
        }
        String uploadUrl = fileUrls.get(0).asText(null);
        if (uploadUrl == null || uploadUrl.isBlank()) {
            log.warn("MinerU requestUpload 返回的 file_urls[0] 为空, body={}", truncate(root.toString()));
            throw new ServiceException("MinerU requestUpload 返回的 file_urls[0] 为空");
        }

        log.info("MinerU 申请上传链接成功 batchId={} fileName={}", batchId, request.fileName());
        return new BatchUploadTicket(batchId, uploadUrl);
    }

    /**
     * 上传文件字节到 MinerU 预签名 URL(本地文件批量上传解析的第二步)
     * <p>
     * 注意:目标是 OSS 预签名 PUT 链接,按 MinerU 官方要求<b>不设 Content-Type、不带 Authorization</b>
     * 上传成功后 MinerU 自动探测并提交解析任务,随后即可 {@link #queryResult} 轮询
     *
     * @param uploadUrl {@link #requestUpload} 返回的上传 URL
     * @param content   文件原始字节
     * @throws ServiceException 网络异常 / 非 2xx 响应
     */
    public void uploadFile(String uploadUrl, byte[] content) {
        if (uploadUrl == null || uploadUrl.isBlank()) {
            throw new ServiceException("uploadUrl 不能为空");
        }
        if (content == null || content.length == 0) {
            throw new ServiceException("上传字节不能为空");
        }
        // 预签名 PUT:body 无 Content-Type(传 null),不加 Authorization
        Request httpRequest = new Request.Builder()
                .url(uploadUrl)
                .put(RequestBody.create(content, null))
                .build();
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                log.warn("MinerU uploadFile 失败 code={}, body={}", response.code(), readBodyForLog(response));
                throw new ServiceException("MinerU uploadFile 失败 code=" + response.code());
            }
            log.info("MinerU 文件上传成功 size={} url={}", content.length, uploadUrl);
        } catch (IOException e) {
            throw new ServiceException("MinerU uploadFile 网络异常: " + e.getMessage());
        }
    }

    /**
     * 查询任务状态
     */
    public MinerUStatus queryResult(String batchId) {
        requireApiKey();
        if (batchId == null || batchId.isBlank()) {
            throw new ServiceException("batchId 不能为空");
        }

        // MinerU v4 按 batch_id 查询结果:batch_id 是路径段(/extract-results/batch/{batch_id}),不是查询参数
        String url = properties.getApiUrl() + "/extract-results/batch/" + batchId;
        Request httpRequest = newGet(url);

        JsonNode root = executeAndParse(httpRequest, "queryResult");
        ensureSuccess(root, "queryResult");

        JsonNode data = root.path("data");
        JsonNode extractResult = data.path("extract_result");
        if (!extractResult.isArray() || extractResult.isEmpty()) {
            // 任务可能仍在排队,暂无 extract_result
            return new MinerUStatus(MinerUTaskState.RUNNING, null, null);
        }

        // 单文件提交,只取第一个
        JsonNode item = extractResult.get(0);
        String stateRaw = item.path("state").asText(null);
        MinerUTaskState state = MinerUTaskState.parse(stateRaw);
        String zipUrl = item.path("full_zip_url").asText(null);
        String errMsg = item.path("err_msg").asText(null);

        return new MinerUStatus(state, zipUrl, errMsg);
    }

    /**
     * 下载结果 zip 字节流
     * <p>
     * 注意:此 URL 通常是一次性预签名 URL,有时效;拿到 MinerUStatus.zipUrl 后立即下载
     */
    public byte[] downloadZip(String zipUrl) {
        if (zipUrl == null || zipUrl.isBlank()) {
            throw new ServiceException("zipUrl 不能为空");
        }
        Request httpRequest = new Request.Builder().url(zipUrl).get().build();
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                log.warn("MinerU downloadZip 失败 code={}, body={}", response.code(), readBodyForLog(response));
                throw new ServiceException("MinerU downloadZip 失败 code=" + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ServiceException("MinerU downloadZip 响应体为空");
            }
            if (body.contentLength() > MAX_ZIP_BYTES) {
                throw new ServiceException("MinerU zip 响应超过上限: " + MAX_ZIP_BYTES + " bytes");
            }
            return readAtMost(body.byteStream(), MAX_ZIP_BYTES);
        } catch (IOException e) {
            throw new ServiceException("MinerU downloadZip 网络异常: " + e.getMessage());
        }
    }

    // ============== private helpers ==============

    /**
     * 限量读取：Content-Length 不可知时（为 -1）由这里兜底
     */
    private static byte[] readAtMost(InputStream in, long maxBytes) throws IOException {
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = stream.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new ServiceException("MinerU zip 响应超过上限: " + maxBytes + " bytes");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void requireApiKey() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new ServiceException("MinerU api-key 未配置,请设置环境变量 MINERU_API_KEY");
        }
    }

    private Request newJsonPost(String url, String jsonBody) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON_MEDIA))
                .build();
    }

    private Request newGet(String url) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .get()
                .build();
    }

    private JsonNode executeAndParse(Request request, String opName) {
        try (Response response = httpClient.newCall(request).execute()) {
            String body = readBodySafe(response);
            if (!response.isSuccessful()) {
                log.warn("MinerU {} HTTP 异常 code={}, body={}", opName, response.code(), truncate(body));
                throw new ServiceException("MinerU " + opName + " HTTP 异常 code=" + response.code());
            }
            try {
                return objectMapper.readTree(body);
            } catch (IOException e) {
                log.warn("MinerU {} 响应非 JSON: {}", opName, truncate(body));
                throw new ServiceException("MinerU " + opName + " 响应非 JSON");
            }
        } catch (IOException e) {
            throw new ServiceException("MinerU " + opName + " 网络异常: " + e.getMessage());
        }
    }

    /**
     * 检查 MinerU 业务码,非 0 抛错
     * <p>
     * MinerU 标准响应格式 {@code {"code":0,"msg":"ok","data":{...}}}
     */
    private void ensureSuccess(JsonNode root, String opName) {
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            String msg = root.path("msg").asText("unknown");
            throw new ServiceException(String.format(
                    "MinerU %s 业务异常 code=%d msg=%s", opName, code, msg));
        }
    }

    private String readBodySafe(Response response) {
        try {
            ResponseBody body = response.body();
            return body == null ? "" : body.string();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 只为日志读取响应体前缀：既不把上游错误页整段读进堆，也不再回显给调用方
     */
    private String readBodyForLog(Response response) {
        try {
            ResponseBody body = response.body();
            if (body == null) {
                return "";
            }
            try (InputStream in = body.byteStream()) {
                return new String(in.readNBytes(LOG_BODY_LIMIT), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return "";
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= LOG_BODY_LIMIT ? text : text.substring(0, LOG_BODY_LIMIT) + "...";
    }
}
