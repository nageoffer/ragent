package com.nageoffer.ai.ragent.rag.rewrite;

public class QueryTermMappingUtil {

    /**
     * 安全归一化替换：
     * - 只替换 sourceTerm
     * - 如果当前位置本身已经是 targetTerm 起始（例如文本中已经是“平安保司”），则不重复替换
     */
    public static String applyMapping(String text, String sourceTerm, String targetTerm) {
        if (text == null || text.isEmpty() || sourceTerm == null || sourceTerm.isEmpty()) {
            return text;
        }

        StringBuilder sb = new StringBuilder();
        int idx = 0;
        int len = text.length();
        int sourceLen = sourceTerm.length();
        int targetLen = targetTerm.length();

        while (idx < len) {
            int hit = text.indexOf(sourceTerm, idx);
            if (hit < 0) {
                // 后面没有命中，整体拷贝
                sb.append(text, idx, len);
                break;
            }

            // 先把命中之前的文本拷贝过去
            sb.append(text, idx, hit);

            // 判断当前位置是否已经是 targetTerm 的开头
            boolean alreadyTarget =
                    targetTerm != null
                            && hit + targetLen <= len
                            && text.startsWith(targetTerm, hit);

            if (alreadyTarget) {
                // 已经是目标词开头了，直接按原文拷贝 targetTerm，一次性跳过
                sb.append(text, hit, hit + targetLen);
                idx = hit + targetLen;
            } else {
                // 不是目标词开头，正常做归一化替换
                sb.append(targetTerm);
                idx = hit + sourceLen;
            }
        }

        return sb.toString();
    }
}
