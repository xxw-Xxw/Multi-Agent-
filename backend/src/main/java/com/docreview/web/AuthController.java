package com.docreview.web;

import com.docreview.domain.entity.AppUser;
import com.docreview.repository.AppUserRepository;
import com.docreview.security.CurrentUser;
import com.docreview.security.JwtService;
import com.docreview.security.PasswordHasher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

/** 注册与登录：成功即签发 JWT，前端之后所有请求都带该 token。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;

    public AuthController(AppUserRepository userRepository, PasswordHasher passwordHasher, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
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
    public AuthResponse register(@RequestBody RegisterRequest request) {
        String username = normalize(request.username());
        String password = request.password() == null ? "" : request.password();
        if (username.length() < 3 || username.length() > 32) {
            throw new IllegalArgumentException("用户名需为 3~32 个字符");
        }
        if (password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
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
        return authResponse(user);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest request) {
        AppUser user = userRepository.findByUsername(normalize(request.username())).orElse(null);
        if (user == null || !passwordHasher.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return authResponse(user);
    }

    @GetMapping("/me")
    public UserInfo me(@RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser currentUser) {
        AppUser user = userRepository.findById(currentUser.userId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        return new UserInfo(user.getId(), user.getUsername(), user.getDisplayName());
    }

    private AuthResponse authResponse(AppUser user) {
        return new AuthResponse(jwtService.issue(user.getId(), user.getUsername()),
                new UserInfo(user.getId(), user.getUsername(), user.getDisplayName()));
    }

    private String normalize(String username) {
        return username == null ? "" : username.trim();
    }
}
