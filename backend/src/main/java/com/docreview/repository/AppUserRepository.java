package com.docreview.repository;

import com.docreview.domain.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, String> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);
}
