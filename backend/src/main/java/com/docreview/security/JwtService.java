package com.docreview.security;

import com.docreview.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
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

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final int MIN_SECRET_BYTES = 16;

    private final byte[] secret;
    private final long ttlMillis;
    private final ObjectMapper mapper;

    public JwtService(AppProperties props, ObjectMapper mapper) {
        String configured = props.getAuth().getSecret();
        this.secret = resolveSecret(configured);
        this.ttlMillis = Math.max(1, props.getAuth().getTtlHours()) * 3_600_000L;
        this.mapper = mapper;
    }

    /**
     * 不内置演示密钥：未配置时随机生成，保证默认部署也无法被伪造 token。
     * 代价是重启后已签发的 token 全部失效，生产环境应显式配置 APP_AUTH_SECRET。
     */
    private byte[] resolveSecret(String configured) {
        if (configured == null || configured.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            log.warn("未配置 APP_AUTH_SECRET，已生成临时签名密钥；重启后现有 token 会失效");
            return generated;
        }
        byte[] bytes = configured.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.auth.secret 至少需要 " + MIN_SECRET_BYTES + " 字节");
        }
        return bytes;
    }

    public long ttlMillis() {
        return ttlMillis;
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
