package com.nageoffer.ai.ragent.core.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum IntentKind {

    /**
     * 知识库类，走 RAG
     */
    KB(0),

    /**
     * 系统交互类，比如欢迎语、介绍自己
     */
    SYSTEM(1);

    private final int code;

    public static IntentKind fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (IntentKind e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return name();
    }
}