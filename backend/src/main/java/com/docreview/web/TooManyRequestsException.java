package com.docreview.web;

/** 触发限流，映射为 429。 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
