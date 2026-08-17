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

import com.nageoffer.ai.ragent.core.parser.image.ImageParseProperties;
import com.nageoffer.ai.ragent.core.parser.model.AssetRef;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.CodeBlock;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.HtmlTableBlock;
import com.nageoffer.ai.ragent.core.parser.model.ImageBlock;
import com.nageoffer.ai.ragent.core.parser.model.ListBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.infra.vlm.VlmService;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MinerU 结果解包器：zip 字节流 → ParsedDocument
 * <p>
 * zip 内是一份 markdown 加若干图片，图片先逐个上传资产桶换取公开 URL，再按 {zip 内路径 → URL} 映射
 * 改写 markdown AST 里的图片地址，其中段首图片提升为 {@link ImageBlock}；字节在手，顺带调 VLM
 * 生成描述，不然内嵌图的向量文本只剩一条 URL
 */
@Slf4j
@Component
public class MinerUResultUnpacker {

    private static final Parser MARKDOWN_PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    private final FileStorageService fileStorageService;
    private final VlmService vlmService;
    private final ImageParseProperties imageParseProperties;

    public MinerUResultUnpacker(FileStorageService fileStorageService,
                                VlmService vlmService,
                                ImageParseProperties imageParseProperties) {
        this.fileStorageService = fileStorageService;
        this.vlmService = vlmService;
        this.imageParseProperties = imageParseProperties;
    }

    /**
     * 解包 zip 为 ParsedDocument，其中 ImageBlock 已带上公开 URL 的 AssetRef
     *
     * @param sourceFile 写入 Provenance.sourceFile 的来源标识
     * @param documentId 资产 key 命名用，落成 {@code assets/{documentId}/{uuid}.{ext}}
     */
    public ParsedDocument unpack(byte[] zipBytes, String sourceFile, String documentId) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new ServiceException("MinerU zip 字节为空");
        }

        ZipContents contents = readZip(zipBytes);
        if (contents.markdown == null) {
            throw new ServiceException("MinerU zip 中未找到 markdown 文件");
        }

        // 上传所有图片，得 {zip 内路径 → 公开 URL} 映射
        Map<String, String> imageUrlMap = uploadImages(contents.images, documentId);
        Map<String, String> imageDescriptionMap = describeImages(contents.images);

        // 解析 markdown 输出 Block 列表
        Provenance prov = Provenance.ofFile(sourceFile);
        Document doc = (Document) MARKDOWN_PARSER.parse(contents.markdown);
        UnpackVisitor visitor = new UnpackVisitor(prov, imageUrlMap, imageDescriptionMap);
        doc.accept(visitor);

        return ParsedDocument.of(visitor.getBlocks(), Map.of(
                "parser", "MinerU",
                "imagesUploaded", imageUrlMap.size(),
                "imagesDescribed", imageDescriptionMap.size(),
                "blocks", visitor.getBlocks().size()
        ));
    }

    /**
     * 单文件 zip 内容快照
     */
    private record ZipContents(String markdown, Map<String, byte[]> images) {
    }

    private ZipContents readZip(byte[] zipBytes) {
        String markdown = null;
        Map<String, byte[]> images = new HashMap<>();

        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                byte[] data = readAll(zin);

                if (name.toLowerCase(Locale.ROOT).endsWith(".md") && markdown == null) {
                    markdown = new String(data, StandardCharsets.UTF_8);
                } else if (isImage(name)) {
                    images.put(name, data);
                }
            }
        } catch (IOException e) {
            throw new ServiceException("MinerU zip 解压失败: " + e.getMessage());
        }
        return new ZipContents(markdown, images);
    }

    private static byte[] readAll(ZipInputStream zin) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zin.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    /**
     * 上传所有图片到资产桶，返回 {zip 内路径 → 公开访问 URL}
     */
    private Map<String, String> uploadImages(Map<String, byte[]> images, String documentId) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, byte[]> e : images.entrySet()) {
            String zipPath = e.getKey();
            byte[] data = e.getValue();
            String ext = extractExt(zipPath);
            String filename = "assets/" + documentId + "/" + UUID.randomUUID() + "." + ext;
            String mime = inferMime(ext);
            try {
                StoredFileDTO stored = fileStorageService.uploadAsset(data, filename, mime);
                // 转成浏览器可直连的公开 URL（资产桶已开公共读），固化进 markdown 图片链接入库
                String publicUrl = fileStorageService.getPublicUrl(stored.getUrl());
                result.put(zipPath, publicUrl);
            } catch (Exception ex) {
                log.error("MinerU 图片上传失败 zipPath={}", zipPath, ex);
                throw new ServiceException("MinerU 图片上传失败 " + zipPath + ": " + ex.getMessage());
            }
        }
        return result;
    }

    /**
     * 逐张图生文，键与 {@code imageUrlMap} 同为 zip 内路径
     * <p>
     * 单张失败只记日志不中断，一张插图召不回来远好过整篇文档入库失败；串行调用，多图文档解析会明显变慢，
     * 但 MinerU 云端解析本就以分钟计
     */
    private Map<String, String> describeImages(Map<String, byte[]> images) {
        if (!imageParseProperties.isEmbeddedDescribeEnabled() || images.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, byte[]> e : images.entrySet()) {
            String zipPath = e.getKey();
            try {
                String description = vlmService.describeImage(e.getValue(), inferMime(extractExt(zipPath)),
                        imageParseProperties.getDescriptionPrompt(), imageParseProperties.getMaxOutputTokens());
                if (description != null && !description.isBlank()) {
                    result.put(zipPath, description.strip());
                }
            } catch (Exception ex) {
                log.warn("MinerU 内嵌图图生文失败，该图向量文本将只剩链接 zipPath={}", zipPath, ex);
            }
        }
        return result;
    }

    private static String extractExt(String path) {
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(idx + 1).toLowerCase(Locale.ROOT) : "bin";
    }

    private static String inferMime(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "application/octet-stream";
        };
    }

    // ===================== AST Visitor =====================

    /**
     * 遍历 markdown AST 输出 Block
     * <p>
     * 段首 Image 剥离出来提升为 {@link ImageBlock}、剩余内容另起 ParagraphBlock，行内 Image 留在 ParagraphBlock
     * 文本里，两者的链接都已替换成资产桶 URL
     */
    private static final class UnpackVisitor extends AbstractVisitor {

        private final Provenance provenance;
        private final Map<String, String> imageUrlMap;
        private final Map<String, String> imageDescriptionMap;
        private final List<Block> blocks = new ArrayList<>();

        UnpackVisitor(Provenance provenance, Map<String, String> imageUrlMap,
                      Map<String, String> imageDescriptionMap) {
            this.provenance = provenance;
            this.imageUrlMap = imageUrlMap;
            this.imageDescriptionMap = imageDescriptionMap;
        }

        List<Block> getBlocks() {
            return blocks;
        }

        @Override
        public void visit(Heading heading) {
            blocks.add(new HeadingBlock(
                    provenance,
                    heading.getLevel(),
                    extractInlineText(heading)
            ));
        }

        @Override
        public void visit(Paragraph paragraph) {
            if (paragraph.getParent() instanceof ListItem) {
                return;
            }

            Node rest = paragraph.getFirstChild();
            while (rest != null) {
                if (rest instanceof Image img) {
                    handleStandaloneImage(img);
                } else if (!isBlank(rest)) {
                    break;
                }
                rest = rest.getNext();
            }

            String text = extractInlineTextFrom(rest);
            if (!text.isEmpty()) {
                blocks.add(new ParagraphBlock(provenance, text));
            }
        }

        @Override
        public void visit(FencedCodeBlock codeBlock) {
            blocks.add(new CodeBlock(
                    provenance,
                    codeBlock.getInfo(),
                    stripTrailingNewline(codeBlock.getLiteral())
            ));
        }

        @Override
        public void visit(IndentedCodeBlock codeBlock) {
            blocks.add(new CodeBlock(
                    provenance,
                    null,
                    stripTrailingNewline(codeBlock.getLiteral())
            ));
        }

        @Override
        public void visit(BulletList bulletList) {
            blocks.add(buildListBlock(bulletList, false));
        }

        @Override
        public void visit(OrderedList orderedList) {
            blocks.add(buildListBlock(orderedList, true));
        }

        @Override
        public void visit(CustomBlock customBlock) {
            if (customBlock instanceof TableBlock tableBlock) {
                handleTable(tableBlock);
                return;
            }
            super.visit(customBlock);
        }

        /**
         * MinerU 的表格以原始 HTML 嵌在 markdown 里，被 commonmark 归为 HtmlBlock
         * <p>
         * 表格单拎出来：落成段落会被按字符硬切、断面停在标签中间，其余 HTML 仍走段落保底不丢内容
         */
        @Override
        public void visit(HtmlBlock htmlBlock) {
            String html = htmlBlock.getLiteral() == null ? "" : htmlBlock.getLiteral().strip();
            if (html.isEmpty()) {
                return;
            }
            blocks.add(html.regionMatches(true, 0, "<table", 0, 6)
                    ? new HtmlTableBlock(provenance, html)
                    : new ParagraphBlock(provenance, html));
        }

        private static boolean isBlank(Node node) {
            return node instanceof SoftLineBreak
                    || node instanceof HardLineBreak
                    || (node instanceof Text t && t.getLiteral().trim().isEmpty());
        }

        private void handleStandaloneImage(Image image) {
            String rawDest = image.getDestination();
            String zipPath = resolveZipPath(rawDest);
            String resolved = resolveImageUrl(rawDest);
            String caption = extractInlineText(image);

            AssetRef asset = new AssetRef(resolved, inferMimeFromUrl(resolved));
            blocks.add(new ImageBlock(
                    provenance,
                    asset, caption, caption,
                    zipPath == null ? null : imageDescriptionMap.get(zipPath)
            ));
        }

        private String resolveImageUrl(String rawDest) {
            String zipPath = resolveZipPath(rawDest);
            if (zipPath != null) {
                return imageUrlMap.get(zipPath);
            }
            return rawDest == null ? "" : rawDest;
        }

        /**
         * 把 markdown 里的图片地址还原成 zip 内路径，URL 与描述两张表共用这个键
         */
        private String resolveZipPath(String rawDest) {
            if (rawDest == null) {
                return null;
            }
            // 优先精确匹配
            if (imageUrlMap.containsKey(rawDest)) {
                return rawDest;
            }
            // 模糊匹配：MinerU markdown 里可能写 ./images/xxx，也可能写 images/xxx
            String norm = rawDest.replaceFirst("^\\./", "");
            if (imageUrlMap.containsKey(norm)) {
                return norm;
            }
            // 用文件名匹配兜底
            int idx = norm.lastIndexOf('/');
            String fileName = idx >= 0 ? norm.substring(idx + 1) : norm;
            for (String key : imageUrlMap.keySet()) {
                if (key.endsWith("/" + fileName) || key.equals(fileName)) {
                    return key;
                }
            }
            return null;
        }

        private static String inferMimeFromUrl(String url) {
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".gif")) return "image/gif";
            return "application/octet-stream";
        }

        private ListBlock buildListBlock(Node listNode, boolean ordered) {
            List<String> items = new ArrayList<>();
            Node child = listNode.getFirstChild();
            while (child != null) {
                if (child instanceof ListItem) {
                    items.add(extractInlineText(child).trim());
                }
                child = child.getNext();
            }
            return new ListBlock(provenance, ordered, items);
        }

        private void handleTable(TableBlock tableBlock) {
            List<String> headers = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();

            Node child = tableBlock.getFirstChild();
            while (child != null) {
                if (child instanceof TableHead head) {
                    Node hr = head.getFirstChild();
                    if (hr instanceof TableRow tr) {
                        headers.addAll(extractCellTexts(tr));
                    }
                } else if (child instanceof TableBody body) {
                    Node tr = body.getFirstChild();
                    while (tr != null) {
                        if (tr instanceof TableRow row) {
                            rows.add(extractCellTexts(row));
                        }
                        tr = tr.getNext();
                    }
                }
                child = child.getNext();
            }

            blocks.add(new com.nageoffer.ai.ragent.core.parser.model.TableBlock(
                    provenance,
                    headers,
                    rows
            ));
        }

        private List<String> extractCellTexts(TableRow row) {
            List<String> cells = new ArrayList<>();
            Node cell = row.getFirstChild();
            while (cell != null) {
                if (cell instanceof TableCell tc) {
                    cells.add(extractInlineText(tc).trim());
                }
                cell = cell.getNext();
            }
            return cells;
        }

        private String extractInlineText(Node parent) {
            return extractInlineTextFrom(parent.getFirstChild());
        }

        /**
         * 从指定兄弟节点起拼接 inline 文本（供段首剥离图片后渲染剩余内容）
         */
        private String extractInlineTextFrom(Node start) {
            StringBuilder sb = new StringBuilder();
            Node node = start;
            while (node != null) {
                appendInline(sb, node);
                node = node.getNext();
            }
            return sb.toString();
        }

        private void appendInline(StringBuilder sb, Node node) {
            if (node instanceof Text t) {
                sb.append(t.getLiteral());
            } else if (node instanceof Code code) {
                sb.append('`').append(code.getLiteral()).append('`');
            } else if (node instanceof Link link) {
                String inner = extractInlineText(link);
                sb.append('[').append(inner).append("](").append(link.getDestination()).append(')');
            } else if (node instanceof Image img) {
                // 行内图片保留 ![alt](url) 形式，随段落文本一起入库
                String alt = extractInlineText(img);
                String resolved = resolveImageUrl(img.getDestination());
                sb.append("![").append(alt).append("](").append(resolved).append(')');
            } else if (node instanceof Emphasis || node instanceof StrongEmphasis) {
                sb.append(extractInlineText(node));
            } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
                sb.append('\n');
            } else if (node.getFirstChild() != null) {
                Node child = node.getFirstChild();
                while (child != null) {
                    appendInline(sb, child);
                    child = child.getNext();
                }
            }
        }

        private static String stripTrailingNewline(String s) {
            if (s == null) {
                return "";
            }
            return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        }
    }
}
