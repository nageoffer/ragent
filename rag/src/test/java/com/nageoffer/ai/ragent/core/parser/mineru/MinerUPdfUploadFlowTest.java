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

import com.nageoffer.ai.ragent.TestRagentApplication;
import com.nageoffer.ai.ragent.core.parser.BlockTextRenderer;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MinerU 真实环境集成测试:上传本地 PDF → MinerU SaaS 解析 → 落盘 zip + 打印 markdown
 * <p>
 * <b>不使用任何 mock</b>,走最真实的 Spring Boot 环境,依赖:
 * <ul>
 *   <li>环境变量 {@code MINERU_API_KEY} 已配置</li>
 *   <li>RustFS(图片上传)与其它 Spring 依赖(DB / Redis 等)可正常连接</li>
 *   <li>通过 {@code -Dmineru.test.pdf=/abs/path/to.pdf} 指定本地 PDF</li>
 * </ul>
 * <p>
 * 运行示例:
 * <pre>
 * mvn -pl bootstrap test -Dtest=MinerUPdfUploadFlowTest \
 *     -Dmineru.test.pdf=/Users/machen/Downloads/sample.pdf \
 *     -DfailIfNoTests=false
 * </pre>
 * <p>
 * 不传 {@code mineru.test.pdf} 时整类跳过(不启动 Spring context),不影响日常构建
 * <p>
 * MinerU 返回的 zip 会自动解压到仓库根目录下的 {@code .mineru-output/}(临时目录,已加入 .gitignore)
 */
@SpringBootTest(classes = TestRagentApplication.class, webEnvironment = WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "mineru.test.pdf", matches = ".+")
@DisplayName("MinerU PDF 上传解析全流程(真实环境)")
class MinerUPdfUploadFlowTest {

    /**
     * MinerU 产物落盘目录名(仓库根目录下, dot 前缀, 已 gitignore)
     */
    private static final String OUT_DIR_NAME = ".output";

    @Autowired
    private MinerUDocumentParser parser;

    @Autowired
    private MinerUClient minerUClient;

    @Test
    @DisplayName("上传本地 PDF → MinerU 解析 → 落盘并解压 zip + 打印 Markdown")
    void uploadRealPdf_fullFlow() throws Exception {
        Path pdf = Path.of(System.getProperty("mineru.test.pdf"));
        byte[] pdfBytes = Files.readAllBytes(pdf);
        String fileName = pdf.getFileName().toString();

        System.out.println("==== 开始上传 PDF: " + pdf.toAbsolutePath() + " (bytes=" + pdfBytes.length + ")");

        // 真实调用 MinerU 全链路:requestUpload → uploadFile → poll → downloadZip → unpack
        ParsedDocument result = parser.parseStructured(pdfBytes, "application/pdf",
                Map.of(MinerUDocumentParser.OPT_SOURCE_FILE, fileName,
                        MinerUDocumentParser.OPT_DOCUMENT_ID, "doc-integration-test"));

        // metadata 里有 MinerU 返回的 zipUrl,复用真实 client 再下一份落盘
        Object zipUrlObj = result.metadata().get(MinerUDocumentParser.META_ZIP_URL);
        String zipUrl = zipUrlObj == null ? "" : zipUrlObj.toString();
        byte[] zipBytes = zipUrl.isEmpty() ? new byte[0] : minerUClient.downloadZip(zipUrl);

        // 落盘到仓库根目录下的 .output/{run-时间戳-短uuid}/,每次运行独立子目录,避免覆盖
        Path runDir = resolveRepoRoot().resolve(OUT_DIR_NAME).resolve(newRunDirName());
        Files.createDirectories(runDir);
        Path zipPath = runDir.resolve("mineru-result.zip");
        Files.write(zipPath, zipBytes);
        List<String> extracted = extractZip(zipBytes, runDir);

        System.out.println("==== 输出目录: " + runDir.toAbsolutePath());
        System.out.println("==== MinerU 返回 zip 已保存: " + zipPath + " (bytes=" + zipBytes.length + ")");
        System.out.println("==== zip 已解压, 共 " + extracted.size() + " 个文件:");
        extracted.forEach(p -> System.out.println("    " + p));
        System.out.println("==== metadata: " + result.metadata());

        // 打印 MinerU 原始 markdown(zip 内 .md 文件)
        System.out.println("==== MinerU 解析出的 Markdown(原始) ====");
        System.out.println(extractMarkdown(zipBytes));

        // 打印 ragent Block 渲染后的 markdown
        System.out.println("==== ragent Block 渲染 Markdown ====");
        System.out.println(BlockTextRenderer.render(result.blocks()));
        System.out.println("==== Block 数量: " + result.blocks().size());
    }

    /**
     * 仓库根:从测试类编译位置(target/test-classes)逐级向上,取最顶层仍含 pom.xml 的目录(即聚合根),IDE/mvn 通用;取不到回退 user.dir
     */
    private static Path resolveRepoRoot() {
        Path fallback = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        try {
            Path dir = Paths.get(MinerUPdfUploadFlowTest.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            Path root = fallback;
            for (Path p = dir; p != null; p = p.getParent()) {
                if (Files.exists(p.resolve("pom.xml"))) {
                    root = p;
                }
            }
            return root;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 每次运行一个独立子目录名:时间戳 + 短 uuid,可排序且不冲突
     */
    private static String newRunDirName() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        return "run-" + ts + "-" + shortId;
    }

    /**
     * 把 zip 全部 entry 解压到 outDir,返回解出的相对路径列表(含目录 entry 跳过)
     */
    private static List<String> extractZip(byte[] zipBytes, Path outDir) throws IOException {
        List<String> entries = new ArrayList<>();
        if (zipBytes == null || zipBytes.length == 0) {
            return entries;
        }
        Path outRoot = outDir.toAbsolutePath().normalize();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path target = outRoot.resolve(entry.getName()).normalize();
                // 防 zip-slip:解压目标必须仍在 outDir 内
                if (!target.startsWith(outRoot)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.write(target, zin.readAllBytes());
                entries.add(outRoot.relativize(target).toString());
            }
        }
        return entries;
    }

    /**
     * 从 zip 字节里抽取第一个 .md entry 的文本
     */
    private static String extractMarkdown(byte[] zipBytes) throws IOException {
        if (zipBytes == null || zipBytes.length == 0) {
            return "(zip 为空)";
        }
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".md")) {
                    return new String(zin.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "(zip 中未找到 .md 文件)";
    }
}
