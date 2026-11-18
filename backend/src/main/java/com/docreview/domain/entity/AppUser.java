package com.docreview.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 登录账号；密码只保存加盐哈希，不保存明文。 */
@Entity
@Table(name = "app_user", indexes = @Index(name = "idx_user_username", columnList = "username", unique = true))
@Getter
@Setter
public class AppUser {

    @Id
    private String id;

    @Column(nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 512)
    private String passwordHash;

    private String displayName;

    private LocalDateTime createdAt;
}
