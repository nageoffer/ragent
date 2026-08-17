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

package com.nageoffer.ai.ragent.core.parser;

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
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Markdown 文档解析器：用 commonmark-java 解析 AST，按标题、段落、代码块、列表、GFM 表格与内嵌 HTML 产出对应 Block
 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

    /**
     * commonmark 解析器，线程安全可共享
     */
    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    @Override
    public String getParserType() {
        return ParserType.MARKDOWN.getType();
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParsedDocument.of(List.of());
        }

        String text = new String(content, StandardCharsets.UTF_8);
        Provenance prov = Provenance.ofFile(extractSourceFile(options));

        Document doc = (Document) PARSER.parse(text);
        BlockExtractingVisitor visitor = new BlockExtractingVisitor(prov);
        doc.accept(visitor);

        return ParsedDocument.of(visitor.getBlocks(), Map.of(
                "parser", getParserType(),
                "mimeType", mimeType == null ? "" : mimeType,
                "blocks", visitor.getBlocks().size()
        ));
    }

    /**
     * MIME 有两个来源，认领清单要同时覆盖：{@code text/x-web-markdown} 是 Tika 探测 {@code .md} 的产出，
     * {@code text/markdown}（RFC 7763）与 {@code text/x-markdown} 则来自外部 Content-Type，Tika 从不产出，
     * 看着像重复项但删不得
     * <p>
     * 认领 {@code text/plain} 同样刻意：txt 的缩进段落与列表交给本解析器至少能拿到结构
     */
    @Override
    public Map<ParseProfile, Set<String>> supportedMimeTypes() {
        return Map.of(ParseProfile.FAST, Set.of(
                "text/x-web-markdown",
                "text/markdown",
                "text/x-markdown",
                "text/plain"
        ));
    }

    private static String extractSourceFile(Map<String, Object> options) {
        if (options == null) {
            return "";
        }
        Object v = options.get("sourceFile");
        return v == null ? "" : v.toString();
    }

    // ===================== AST Visitor =====================

    /**
     * AST 访问器：commonmark 节点 → ragent Block，只处理顶层 block，不递归进嵌套
     * （列表项内的代码块仍归 ListBlock）
     */
    private static final class BlockExtractingVisitor extends AbstractVisitor {

        private final Provenance provenance;
        private final List<Block> blocks = new ArrayList<>();

        BlockExtractingVisitor(Provenance provenance) {
            this.provenance = provenance;
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
            // 不向下递归，标题内的 inline 已合并
        }

        @Override
        public void visit(Paragraph paragraph) {
            // 只处理顶层段落，列表项内的段落归 ListBlock
            if (paragraph.getParent() instanceof ListItem) {
                return;
            }
            // 独占一行的图片按图片块产出，而不是压成一段只剩 alt 文本的文字
            Image standaloneImage = asStandaloneImage(paragraph);
            if (standaloneImage != null) {
                blocks.add(toImageBlock(standaloneImage, provenance));
                return;
            }
            String text = extractInlineText(paragraph);
            if (!text.isEmpty()) {
                blocks.add(new ParagraphBlock(provenance, text));
            }
        }

        /**
         * 内嵌 HTML：{@code HtmlBlock} 是叶子节点、内容只在 literal 里，不接管就被基类的 visitChildren 静默丢弃
         * <p>
         * 表格另立块类型，落成段落会被按字符硬切、断面停在标签中间
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
            // 不向下递归
        }

        @Override
        public void visit(OrderedList orderedList) {
            blocks.add(buildListBlock(orderedList, true));
            // 不向下递归
        }

        @Override
        public void visit(org.commonmark.node.CustomBlock customBlock) {
            // GFM TableBlock 是 CustomBlock 子类
            if (customBlock instanceof TableBlock tableBlock) {
                handleTable(tableBlock);
                return;
            }
            super.visit(customBlock);
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
    }

    /**
     * 拼接节点内所有 inline 文本（Text / Code / Link / Emphasis 等），
     * Link 保留 {@code [text](url)} 形式以与下游 ImageChunker 风格一致
     */
    private static String extractInlineText(Node parent) {
        StringBuilder sb = new StringBuilder();
        Node child = parent.getFirstChild();
        while (child != null) {
            appendInline(sb, child);
            child = child.getNext();
        }
        return sb.toString();
    }

    private static void appendInline(StringBuilder sb, Node node) {
        if (node instanceof Text t) {
            sb.append(t.getLiteral());
        } else if (node instanceof Code code) {
            sb.append('`').append(code.getLiteral()).append('`');
        } else if (node instanceof Link link) {
            String inner = extractInlineText(link);
            String dest = link.getDestination();
            sb.append('[').append(inner).append("](").append(dest).append(')');
        } else if (node instanceof Image image) {
            // 必须带上 URL：只剩 alt 文本的话，md 里的图在知识库中既不可引用也不可预览
            sb.append("![").append(extractInlineText(image)).append("](")
                    .append(image.getDestination() == null ? "" : image.getDestination()).append(')');
        } else if (node instanceof Emphasis || node instanceof StrongEmphasis) {
            // 保留 inline 文本，丢掉 markdown 强调标记
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

    /**
     * 段落是否只包含一张图片（允许周围有空白文本）
     */
    private static Image asStandaloneImage(Paragraph paragraph) {
        Image found = null;
        Node child = paragraph.getFirstChild();
        while (child != null) {
            if (child instanceof Image image) {
                if (found != null) {
                    return null;
                }
                found = image;
            } else if (!(child instanceof SoftLineBreak || child instanceof HardLineBreak)
                    && !(child instanceof Text text && text.getLiteral().isBlank())) {
                return null;
            }
            child = child.getNext();
        }
        return found;
    }

    /**
     * 图片节点 → 图片块
     * <p>
     * 地址是作者写的原样地址（外链或相对路径），不经过资产上传，因此没有图生文描述，
     * 向量文本由分块阶段回落到链接本身
     */
    private static ImageBlock toImageBlock(Image image, Provenance provenance) {
        String url = image.getDestination() == null ? "" : image.getDestination();
        String altText = extractInlineText(image);
        return new ImageBlock(
                provenance,
                new AssetRef(url, guessImageMime(url)),
                image.getTitle(),
                altText
        );
    }

    /**
     * 按地址后缀猜 MIME：图片地址不经过字节探测，只能按扩展名给一个合理值
     */
    private static String guessImageMime(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return null;
    }

    private static String stripTrailingNewline(String s) {
        if (s == null) {
            return "";
        }
        if (s.endsWith("\n")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}
