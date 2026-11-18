package com.docreview.domain;

public enum TaskStatus {
    CREATED,
    PARSING,
    PLANNING,
    REVIEWING,
    CRITIQUING,
    ARBITRATING,
    REPORTING,
    DONE,
    PARTIAL,
    FAILED;

    public boolean finished() {
        return this == DONE || this == PARTIAL || this == FAILED;
    }
}
