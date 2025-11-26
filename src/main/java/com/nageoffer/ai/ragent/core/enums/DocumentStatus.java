package com.nageoffer.ai.ragent.core.enums;

import lombok.Getter;

@Getter
public enum DocumentStatus {

    PENDING("pending"),

    RUNNING("running"),

    FAILED("failed"),

    SUCCESS("success");

    private final String code;

    DocumentStatus(String c) {
        this.code = c;
    }
}
