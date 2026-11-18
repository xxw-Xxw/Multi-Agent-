package com.docreview.security;

/** 拦截器解析出的当前登录用户，通过 request attribute 传给 Controller。 */
public record CurrentUser(String userId, String username) {

    public static final String ATTRIBUTE = "currentUser";
}
