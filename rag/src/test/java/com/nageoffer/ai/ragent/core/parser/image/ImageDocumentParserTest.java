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

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SVG 栅格化边界测试（审计 F-10）：小宽度+巨高度声明不再产生无界画布分配——
 * 声明尺寸组合预算在栅格化前预拒；带单位等预检放行形态由宽高双上限夹逼兜底。
 * VLM/存储不在本测试面：rasterizeSvg 是纯字节函数
 */
@DisplayName("ImageDocumentParser SVG 栅格化边界")
class ImageDocumentParserTest {

    private static byte[] rasterize(String svg) {
        return ImageDocumentParser.rasterizeSvg(svg.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @Timeout(20)
    @DisplayName("小宽+巨高声明（F-10 原始形态）：组合预算预拒，不进入栅格化")
    void rejectsHugeDeclaredCanvasBeforeRasterization() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"50000000\">"
                + "<rect x=\"0\" y=\"0\" width=\"100\" height=\"100\"/></svg>";
        assertThatThrownBy(() -> rasterize(svg))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("声明尺寸超出支持上限")
                .hasMessageNotContaining("50000000");
        // 进程存活：同批继续栅格化正常 SVG
        assertThatCode(() -> rasterize(normalSvg())).doesNotThrowAnyException();
    }

    @Test
    @Timeout(20)
    @DisplayName("宽高声明均合法但面积超组合预算：同样预拒")
    void rejectsWideTallCombinedArea() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100000\" height=\"100000\">"
                + "<rect x=\"0\" y=\"0\" width=\"10\" height=\"10\"/></svg>";
        assertThatThrownBy(() -> rasterize(svg))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("声明尺寸超出支持上限");
    }

    @Test
    @Timeout(20)
    @DisplayName("带单位的巨高声明（预检放行形态）：KEY_MAX_HEIGHT 夹逼后高度 ≤ 上限")
    void clampsUnitSuffixedTallSvgViaMaxHeightHint() throws Exception {
        // px 后缀超出预检的纯数值合同，交由栅格化双上限兜底——旧代码此处高度原样通过（batik 宽度分支不钳高）
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800px\" height=\"2400px\">"
                + "<rect x=\"0\" y=\"0\" width=\"800\" height=\"2400\"/></svg>";
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(rasterize(svg)));
        assertThat(image).isNotNull();
        assertThat(image.getHeight()).isLessThanOrEqualTo((int) ImageDocumentParser.SVG_RASTER_MAX_DIMENSION);
        assertThat(image.getWidth()).isLessThanOrEqualTo((int) ImageDocumentParser.SVG_RASTER_MAX_DIMENSION);
    }

    @Test
    @Timeout(20)
    @DisplayName("正常 SVG 与无内在尺寸 SVG：栅格化行为零回归")
    void keepsNormalAndIntrinsicSizeFreeSvgsWorking() throws Exception {
        BufferedImage normal = ImageIO.read(new ByteArrayInputStream(rasterize(normalSvg())));
        assertThat(normal).isNotNull();
        assertThat(normal.getWidth()).isEqualTo(400);
        assertThat(normal.getHeight()).isEqualTo(300);

        // 无 width/height 声明（仅 viewBox）：既有路径不回归，仍产 PNG
        String viewBoxOnly = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 100\">"
                + "<rect x=\"0\" y=\"0\" width=\"200\" height=\"100\"/></svg>";
        BufferedImage intrinsicFree = ImageIO.read(new ByteArrayInputStream(rasterize(viewBoxOnly)));
        assertThat(intrinsicFree).isNotNull();
    }

    private static String normalSvg() {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"400\" height=\"300\">"
                + "<circle cx=\"200\" cy=\"150\" r=\"100\" fill=\"#3366cc\"/></svg>";
    }
}
