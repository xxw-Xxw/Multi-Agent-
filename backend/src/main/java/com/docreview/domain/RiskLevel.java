package com.docreview.domain;

public enum RiskLevel {
    LOW(1),
    MEDIUM(2),
    HIGH(3);

    private final int weight;

    RiskLevel(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    public static RiskLevel higher(RiskLevel a, RiskLevel b) {
        return a.weight >= b.weight ? a : b;
    }

    public static RiskLevel parse(String raw) {
        if (raw == null) {
            return MEDIUM;
        }
        for (RiskLevel level : values()) {
            if (level.name().equalsIgnoreCase(raw.trim())) {
                return level;
            }
        }
        return MEDIUM;
    }
}
