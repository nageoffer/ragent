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

package com.nageoffer.ai.ragent.core.parser.image;

import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.core.parser.ParserType;
import com.nageoffer.ai.ragent.core.parser.model.AssetRef;
import com.nageoffer.ai.ragent.core.parser.model.ImageBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.infra.vlm.VlmService;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 图片文档解析器（PNG / JPG / SVG）：入库期用 VLM 把图片转成「中文描述 + 图中文字 OCR」，产出单个 {@link ImageBlock}
 * <p>
 * 独立上传的图片自身没有可检索文本，直接 embedding {@code ![](url)} 只是噪声、永远召回不到，故 description 进
 * embedding 负责召回，原图上传资产桶后由 {@link com.nageoffer.ai.ragent.core.chunk.blockaware.ImageChunker}
 * 渲染为 {@code ![caption](url)} 随答复展示；只认领精确 MIME 而不用 {@code image/*} 通配，未覆盖的格式显式报错
 */
@Slf4j
@Component
public class ImageDocumentParser implements DocumentParser {

    public static final String OPT_SOURCE_FILE = "sourceFile";
    public static final String OPT_DOCUMENT_ID = "documentId";

    /**
     * SVG 栅格化边长上限（宽高同值）：batik 对超限边各自等比缩放，宽高两个 hint 齐备后
     * 任何声明尺寸组合的落点都 ≤ 上限×上限（仅设宽度时窄高型 SVG 的高会原样通过——
     * batik 1.18 setImageSize 宽高夹逼是两个独立分支），画布分配有界（约 10MB ARGB）
     */
    static final float SVG_RASTER_MAX_DIMENSION = 1600f;

    /**
     * SVG 声明尺寸组合预算（宽×高，像素数）：栅格化前的确定性预拒，拦下病态声明、
     * 不让 batik 内部先按声明尺寸建结构。取栅格化上限面积的 10 倍——合法文档 SVG
     * （图表/示意图）远够用，声明面积逼近该值的矢量本身已无检索价值
     */
    static final double SVG_DECLARED_MAX_PIXELS = (double) SVG_RASTER_MAX_DIMENSION * SVG_RASTER_MAX_DIMENSION * 10;

    private final VlmService vlmService;
    private final FileStorageService fileStorageService;
    private final ImageParseProperties properties;

    public ImageDocumentParser(VlmService vlmService,
                               FileStorageService fileStorageService,
                               ImageParseProperties properties) {
        this.vlmService = vlmService;
        this.fileStorageService = fileStorageService;
        this.properties = properties;
    }

    @Override
    public String getParserType() {
        return ParserType.IMAGE.getType();
    }

    @Override
    public Map<ParseProfile, Set<String>> supportedMimeTypes() {
        return Map.of(ParseProfile.FAST, Set.of(
                "image/png",
                "image/jpeg",
                "image/jpg",
                "image/svg+xml"
        ));
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            throw new ServiceException("图片解析输入字节为空");
        }
        String sourceFile = extract(options, OPT_SOURCE_FILE, "");
        String documentId = extract(options, OPT_DOCUMENT_ID, UUID.randomUUID().toString());

        // 0. SVG 归一化：矢量 XML 栅格化成 PNG，此后字节与 mime 与 PNG 路径完全一致
        if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).equals("image/svg+xml")) {
            content = rasterizeSvg(content);
            mimeType = "image/png";
        }

        // 1. VLM 图生文：整段输出直接作描述，不解析任何分隔符，prompt 措辞可自由调整
        String description = vlmService.describeImage(
                content, mimeType, properties.getDescriptionPrompt(), properties.getMaxOutputTokens());
        description = description == null ? "" : description.strip();
        // 空描述等同失败：放过去只会产出永远召回不到的纯链接 chunk
        if (description.isBlank()) {
            throw new ServiceException("VLM 返回空描述，无法生成可检索文本：file=" + sourceFile);
        }

        // 2. 原图上传资产桶（public-read），拿匿名可达的公网 URL
        String ext = extFromMime(mimeType);
        String filename = "assets/" + documentId + "/" + UUID.randomUUID() + "." + ext;
        StoredFileDTO stored = fileStorageService.uploadAsset(content, filename, mimeType);
        String publicUrl = fileStorageService.getPublicUrl(stored.getUrl());

        // 3. 构造 ImageBlock：description 既作展示与答题正文，也作向量文本（ImageChunker 渲染时会去掉 URL 噪声）
        String caption = stripExt(sourceFile);
        AssetRef asset = new AssetRef(publicUrl, mimeType);
        ImageBlock block = new ImageBlock(Provenance.ofFile(sourceFile), asset, caption, caption, description);

        log.info("图片图生文完成: file={}, descChars={}, url={}", sourceFile, description.length(), publicUrl);
        return ParsedDocument.of(List.of(block), Map.of(
                "parser", getParserType(),
                "mimeType", mimeType == null ? "" : mimeType,
                "descriptionChars", description.length()
        ));
    }

    private static String extract(Map<String, Object> options, String key, String defaultValue) {
        if (options == null) {
            return defaultValue;
        }
        Object v = options.get(key);
        return (v == null || v.toString().isBlank()) ? defaultValue : v.toString();
    }

    /**
     * SVG 栅格化成 PNG 字节，VLM 视觉输入只认栅格格式
     * <p>
     * 必须铺白底：PNGTranscoder 默认透明背景，VLM 解码带 alpha 的 PNG 会把透明区合成为黑或空、返回空描述；
     * 宽高双上限夹逼画布尺寸（见 {@link #SVG_RASTER_MAX_DIMENSION}），声明尺寸超组合预算的在栅格化前预拒
     */
    static byte[] rasterizeSvg(byte[] svg) {
        checkDeclaredSvgSize(svg);
        try {
            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_MAX_WIDTH, SVG_RASTER_MAX_DIMENSION);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_MAX_HEIGHT, SVG_RASTER_MAX_DIMENSION);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_BACKGROUND_COLOR, Color.WHITE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transcoder.transcode(new TranscoderInput(new ByteArrayInputStream(svg)), new TranscoderOutput(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new ServiceException("SVG 栅格化失败：" + e.getMessage());
        }
    }

    /**
     * 声明尺寸组合预算预检：根元素 width/height 均为可解析的纯数值时校验宽×高乘积，
     * 超预算抛业务异常；无内在尺寸、百分比、带单位等形态直接放行（既有行为，画布
     * 分配由栅格化双上限兜底），预算检查自身解析失败也放行交由栅格化给出原语义
     */
    private static void checkDeclaredSvgSize(byte[] svg) {
        Element root;
        try {
            root = Jsoup.parse(new ByteArrayInputStream(svg), null, "").selectFirst("svg");
        } catch (Exception e) {
            return;
        }
        if (root == null) {
            return;
        }
        Double width = parseDeclaredLength(root.attr("width"));
        Double height = parseDeclaredLength(root.attr("height"));
        if (width == null || height == null) {
            return;
        }
        double pixels = width * height;
        if (pixels > SVG_DECLARED_MAX_PIXELS) {
            throw new ServiceException("SVG 声明尺寸超出支持上限，已拒绝解析该文档");
        }
    }

    private static Double parseDeclaredLength(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            return value > 0 && value < Double.POSITIVE_INFINITY ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extFromMime(String mimeType) {
        if (mimeType == null) {
            return "png";
        }
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> "jpg";
            default -> "png";
        };
    }

    private static String stripExt(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
