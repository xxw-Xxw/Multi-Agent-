package com.docreview.web;

/** 已登录但无权访问目标资源，映射为 403。 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
