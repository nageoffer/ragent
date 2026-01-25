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

package com.nageoffer.ai.ragent.rag.Intent;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.rag.controller.vo.IntentNodeTreeVO;
import com.nageoffer.ai.ragent.ingestion.service.IntentTreeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class IntentTreeServiceTests {

    private final IntentTreeService intentTreeService;

    @Test
    public void initFromFactory() {
        intentTreeService.initFromFactory();
    }

    @Test
    public void getFullTree() {
        List<IntentNodeTreeVO> roots = intentTreeService.getFullTree();
        if (roots == null || roots.isEmpty()) {
            System.out.println("(意图树为空)");
            return;
        }

        // 顶层先按 sortOrder 排一下，避免顺序乱
        roots = roots.stream()
                .sorted(this::compareBySortOrder)
                .toList();

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < roots.size(); i++) {
            IntentNodeTreeVO root = roots.get(i);
            if (i > 0) {
                // 顶层节点之间空一行
                sb.append("\n");
            }
            printNodeTree(root, 0, sb);
        }

        System.out.println(sb.toString());
    }

    // ======================== 打印工具 ========================

    /**
     * 递归打印：
     * - 每行：缩进 + "- 名称 (LEVEL)"
     * - 下一行可选：缩进 + "  备注: xxx"
     * - 再下一行可选：缩进 + "  示例: 问题1 / 问题2"
     */
    private void printNodeTree(IntentNodeTreeVO node, int depth, StringBuilder sb) {
        String indent = "  ".repeat(Math.max(depth, 0));

        // 1) 名称 + 层级标签
        sb.append(indent)
                .append("- ")
                .append(safe(node.getName()));

        String levelLabel = levelLabel(node.getLevel());
        if (!levelLabel.isEmpty()) {
            sb.append(" (").append(levelLabel).append(")");
        }
        sb.append("\n");

        // 2) 备注（description）
        if (node.getDescription() != null && !node.getDescription().isBlank()) {
            sb.append(indent)
                    .append("  ")
                    .append("备注: ")
                    .append(node.getDescription().trim())
                    .append("\n");
        }

        // 3) 示例问题（examples）
        if (node.getExamples() != null && !node.getExamples().isEmpty()) {
            JSONArray jsonArray = JSONUtil.parseArray(node.getExamples());
            String joinedExamples = jsonArray.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(" / "));

            if (!joinedExamples.isEmpty()) {
                sb.append(indent)
                        .append("  ")
                        .append("示例: ")
                        .append(joinedExamples)
                        .append("\n");
            }
        }

        // 4) 子节点
        if (node.getChildren() != null && !node.getChildren().isEmpty()) {
            node.getChildren().stream()
                    .sorted(this::compareBySortOrder)
                    .forEach(child -> printNodeTree(child, depth + 1, sb));
        }
    }

    /**
     * level: 0=DOMAIN, 1=CATEGORY, 2=TOPIC
     */
    private String levelLabel(Integer level) {
        if (level == null) return "";
        return switch (level) {
            case 0 -> "DOMAIN";
            case 1 -> "CATEGORY";
            case 2 -> "TOPIC";
            default -> "";
        };
    }

    /**
     * sortOrder 从小到大；null 的放到最后
     */
    private int compareBySortOrder(IntentNodeTreeVO a, IntentNodeTreeVO b) {
        Integer sa = a.getSortOrder();
        Integer sb = b.getSortOrder();
        if (sa == null && sb == null) return 0;
        if (sa == null) return 1;
        if (sb == null) return -1;
        return Integer.compare(sa, sb);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
