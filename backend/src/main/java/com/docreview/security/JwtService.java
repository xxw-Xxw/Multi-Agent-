package com.docreview.security;

import com.docreview.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手写 HS256 JWT：只有 header.payload.signature 三段，签名用 HmacSHA256。
 * 不引第三方依赖，签名与过期校验都在本地完成。
 */
@Component
public class JwtService {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private final byte[] secret;
    private final long ttlMillis;
    private final ObjectMapper mapper;

    public JwtService(AppProperties props, ObjectMapper mapper) {
        this.secret = props.getAuth().getSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 16) {
            throw new IllegalStateException("app.auth.secret 至少需要 16 字节");
        }
        this.ttlMillis = Math.max(1, props.getAuth().getTtlHours()) * 3_600_000L;
        this.mapper = mapper;
    }

    public String issue(String userId, String username) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", userId);
        payload.put("username", username);
        payload.put("exp", (System.currentTimeMillis() + ttlMillis) / 1000);
        try {
            String head = ENCODER.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8));
            String body = ENCODER.encodeToString(mapper.writeValueAsBytes(payload));
            String signingInput = head + "." + body;
            return signingInput + "." + ENCODER.encodeToString(hmac(signingInput));
        } catch (Exception e) {
            throw new IllegalStateException("签发 token 失败: " + e.getMessage(), e);
        }
    }

    /** 校验签名与过期时间；任何一步失败返回 null。 */
    public CurrentUser verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        byte[] actual;
        try {
            actual = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!MessageDigest.isEqual(hmac(parts[0] + "." + parts[1]), actual)) {
            return null;
        }
        try {
            JsonNode payload = mapper.readTree(DECODER.decode(parts[1]));
            if (payload.path("exp").asLong(0) * 1000 <= System.currentTimeMillis()) {
                return null;
            }
            String userId = payload.path("sub").asText("");
            return userId.isBlank() ? null : new CurrentUser(userId, payload.path("username").asText(""));
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("token 签名失败: " + e.getMessage(), e);
        }
    }
}
