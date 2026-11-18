package com.docreview.security;

import com.docreview.web.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流：同一 IP + 用户名在窗口期内超过阈值即拒绝，防止在线暴力破解。
 */
@Component
public class LoginAttempts {

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 10 * 60 * 1000L;

    private record Attempt(int count, long windowStart) {
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public void check(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return;
        }
        if (System.currentTimeMillis() - attempt.windowStart() >= WINDOW_MS) {
            attempts.remove(key);
            return;
        }
        if (attempt.count() >= MAX_FAILURES) {
            throw new TooManyRequestsException("登录失败次数过多，请 10 分钟后再试");
        }
    }

    public void recordFailure(String key) {
        long now = System.currentTimeMillis();
        attempts.compute(key, (ignored, current) -> {
            if (current == null || now - current.windowStart() >= WINDOW_MS) {
                return new Attempt(1, now);
            }
            return new Attempt(current.count() + 1, current.windowStart());
        });
    }

    public void reset(String key) {
        attempts.remove(key);
    }
}
