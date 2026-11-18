package com.docreview.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;

/**
 * /api/** 统一鉴权：优先读 Authorization: Bearer，其次读 ?token=（EventSource 无法设置请求头）。
 * 校验通过把当前用户写入 request attribute，失败直接返回 401 JSON。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtService jwtService;
    private final ObjectMapper mapper;

    public AuthInterceptor(JwtService jwtService, ObjectMapper mapper) {
        this.jwtService = jwtService;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        CurrentUser user = jwtService.verify(resolveToken(request));
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(response.getWriter(), Map.of("message", "未登录或登录已过期，请重新登录"));
            return false;
        }
        request.setAttribute(CurrentUser.ATTRIBUTE, user);
        return true;
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        String param = request.getParameter("token");
        return param == null || param.isBlank() ? null : param.trim();
    }
}
