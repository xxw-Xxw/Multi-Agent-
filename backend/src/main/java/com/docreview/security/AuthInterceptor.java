package com.docreview.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;

/**
 * /api/** 统一鉴权：浏览器读 HttpOnly Cookie，脚本/CLI 读 Authorization: Bearer。
 * 不再接受 URL query 中的 token，避免 token 进入访问日志与浏览器历史。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtService jwtService;
    private final TokenDenylist denylist;
    private final ObjectMapper mapper;

    public AuthInterceptor(JwtService jwtService, TokenDenylist denylist, ObjectMapper mapper) {
        this.jwtService = jwtService;
        this.denylist = denylist;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = resolveToken(request);
        CurrentUser user = token == null ? null : jwtService.verify(token);
        if (user == null || denylist.isRevoked(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(response.getWriter(), Map.of("message", "未登录或登录已过期，请重新登录"));
            return false;
        }
        request.setAttribute(CurrentUser.ATTRIBUTE, user);
        request.setAttribute(AuthCookies.TOKEN_ATTRIBUTE, token);
        return true;
    }

    private String resolveToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (AuthCookies.NAME.equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (value != null && !value.isBlank()) {
                        return value.trim();
                    }
                }
            }
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        return null;
    }
}
