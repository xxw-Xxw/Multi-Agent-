package com.docreview.security;

/** 鉴权相关常量：浏览器使用 HttpOnly Cookie，脚本/CLI 可使用 Bearer。 */
public final class AuthCookies {

    public static final String NAME = "DOCREVIEW_TOKEN";
    public static final String TOKEN_ATTRIBUTE = "currentToken";

    private AuthCookies() {
    }
}
