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

package com.nageoffer.ai.ragent.rag.core.graph;

import cn.hutool.core.util.StrUtil;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图谱文档来源标识 {collectionName}_{docId} 的编解码
 * <p>
 * docId 为雪花纯数字，从右锚定「末段 _数字」即可唯一还原两个分量；库名用户自填、可含下划线且可互为前缀
 * （kb 与 kb_hr 合法共存），从左做子串匹配必然串库——读侧把别库证据划进主路，删侧连带删光别库数据且不可逆，
 * 归属判定必须走这里的解析 + 全名等值
 */
public record GraphFileSource(String collectionName, String docId) {

    /**
     * 右锚定格式：主体严格为 {collectionName}_{docId}，容忍服务端追加的扩展名修饰
     */
    private static final Pattern FORMAT = Pattern.compile("^(.+)_(\\d+)((?:\\.[A-Za-z0-9]+)*)$");

    /**
     * 写入侧编码，与 parse 互为逆运算
     */
    public static String encode(String collectionName, String docId) {
        return collectionName + "_" + docId;
    }

    /**
     * 从 LightRAG 回传的 file_path 还原来源，先取 basename 以跳过可能的目录前缀；不符合编码返回 null
     */
    public static GraphFileSource parse(String filePath) {
        if (StrUtil.isBlank(filePath)) {
            return null;
        }
        String baseName = filePath.substring(filePath.lastIndexOf('/') + 1);
        Matcher matcher = FORMAT.matcher(baseName);
        return matcher.matches() ? new GraphFileSource(matcher.group(1), matcher.group(2)) : null;
    }
}
