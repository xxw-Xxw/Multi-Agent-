package com.docreview.web;

import com.docreview.config.AppProperties;
import com.docreview.domain.entity.AppUser;
import com.docreview.repository.AppUserRepository;
import com.docreview.security.AuthCookies;
import com.docreview.security.CurrentUser;
import com.docreview.security.JwtService;
import com.docreview.security.LoginAttempts;
import com.docreview.security.PasswordHasher;
import com.docreview.security.TokenDenylist;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/** 注册与登录：签发 JWT 并写入 HttpOnly Cookie，前端不再把 token 放进 localStorage。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AppUserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;
    private final TokenDenylist denylist;
    private final LoginAttempts loginAttempts;
    private final AppProperties props;

    public AuthController(AppUserRepository userRepository, PasswordHasher passwordHasher, JwtService jwtService,
                          TokenDenylist denylist, LoginAttempts loginAttempts, AppProperties props) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
        this.denylist = denylist;
        this.loginAttempts = loginAttempts;
        this.props = props;
    }

    public record RegisterRequest(String username, String password, String displayName) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record UserInfo(String id, String username, String displayName) {
    }

    public record AuthResponse(String token, UserInfo user) {
    }

    @PostMapping("/register")
    public AuthResponse register(@RequestBody RegisterRequest request, HttpServletResponse response) {
        String username = normalize(request.username());
        String password = request.password() == null ? "" : request.password();
        if (username.length() < 3 || username.length() > 32) {
            throw new IllegalArgumentException("用户名需为 3~32 个字符");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已被注册");
        }

        AppUser user = new AppUser();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash(password));
        String displayName = request.displayName() == null ? "" : request.displayName().trim();
        user.setDisplayName(displayName.isEmpty() ? username : displayName);
        user.setCreatedAt(LocalDateTime.now());
        userRepository.save(user);
        return authResponse(user, response);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest request, HttpServletRequest httpRequest,
                              HttpServletResponse response) {
        String username = normalize(request.username());
        String password = request.password() == null ? "" : request.password();
        String key = httpRequest.getRemoteAddr() + "|" + username;
        loginAttempts.check(key);
        AppUser user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordHasher.matches(password, user.getPasswordHash())) {
            loginAttempts.recordFailure(key);
            throw new IllegalArgumentException("用户名或密码错误");
        }
        loginAttempts.reset(key);
        return authResponse(user, response);
    }

    @PostMapping("/logout")
    public Map<String, String> logout(
            @RequestAttribute(name = AuthCookies.TOKEN_ATTRIBUTE, required = false) String token,
            HttpServletResponse response) {
        denylist.revoke(token);
        response.addHeader(HttpHeaders.SET_COOKIE, expiredCookie().toString());
        return Map.of("message", "已退出登录");
    }

    @GetMapping("/me")
    public UserInfo me(@RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser currentUser) {
        AppUser user = userRepository.findById(currentUser.userId())
                .orElseThrow(() -> new NotFoundException("用户不存在"));
        return new UserInfo(user.getId(), user.getUsername(), user.getDisplayName());
    }

    private AuthResponse authResponse(AppUser user, HttpServletResponse response) {
        String token = jwtService.issue(user.getId(), user.getUsername());
        response.addHeader(HttpHeaders.SET_COOKIE, tokenCookie(token).toString());
        return new AuthResponse(token,
                new UserInfo(user.getId(), user.getUsername(), user.getDisplayName()));
    }

    private ResponseCookie tokenCookie(String token) {
        return ResponseCookie.from(AuthCookies.NAME, token)
                .httpOnly(true)
                .secure(props.getAuth().isCookieSecure())
                .sameSite(props.getAuth().getCookieSameSite())
                .path("/")
                .maxAge(Duration.ofMillis(jwtService.ttlMillis()))
                .build();
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(AuthCookies.NAME, "")
                .httpOnly(true)
                .secure(props.getAuth().isCookieSecure())
                .sameSite(props.getAuth().getCookieSameSite())
                .path("/")
                .maxAge(0)
                .build();
    }

    private String normalize(String username) {
        return username == null ? "" : username.trim();
    }
}
