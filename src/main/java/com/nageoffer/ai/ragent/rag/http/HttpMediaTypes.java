package com.nageoffer.ai.ragent.rag.http;

import okhttp3.MediaType;

public final class HttpMediaTypes {

    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    public static final String JSON_UTF8_HEADER = "application/json; charset=UTF-8";

    private HttpMediaTypes() {
    }
}
