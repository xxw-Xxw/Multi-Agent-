package com.docreview.security;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录已退出登录的 token，使其在自然过期前立即失效。
 * 单实例内存实现；多实例部署时应替换为 Redis 等共享存储。
 */
@Component
public class TokenDenylist {

    private static final int PURGE_THRESHOLD = 1_000;

    private final Map<String, Long> revoked = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public TokenDenylist(JwtService jwtService) {
        this.ttlMillis = jwtService.ttlMillis();
    }

    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        purgeIfNeeded();
        revoked.put(token, System.currentTimeMillis() + ttlMillis);
    }

    public boolean isRevoked(String token) {
        if (token == null) {
            return false;
        }
        Long expiresAt = revoked.get(token);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt <= System.currentTimeMillis()) {
            revoked.remove(token);
            return false;
        }
        return true;
    }

    private void purgeIfNeeded() {
        if (revoked.size() < PURGE_THRESHOLD) {
            return;
        }
        long now = System.currentTimeMillis();
        revoked.entrySet().removeIf(entry -> entry.getValue() <= now);
    }
}
