package com.docreview.service;

import com.docreview.config.AppProperties;
import com.docreview.domain.entity.DocumentEntity;
import com.docreview.repository.DocumentRepository;
import com.docreview.web.dto.DocumentDto;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Set<String> ALLOWED = Set.of("pdf", "docx", "txt");

    private final DocumentRepository repository;
    private final AppProperties props;

    public DocumentService(DocumentRepository repository, AppProperties props) {
        this.repository = repository;
        this.props = props;
    }

    public DocumentDto upload(MultipartFile file, String userId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        String originalName = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
        String ext = extensionOf(originalName);
        if (!ALLOWED.contains(ext)) {
            throw new IllegalArgumentException("暂只支持 pdf / docx / txt 文件");
        }

        String id = UUID.randomUUID().toString();
        try {
            Path dir = Paths.get(props.getStorage().getDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path target = dir.resolve(id + "." + ext);
            file.transferTo(target.toFile());

            DocumentEntity entity = new DocumentEntity();
            entity.setId(id);
            entity.setUserId(userId);
            entity.setName(originalName);
            entity.setContentType(file.getContentType());
            entity.setSizeBytes(file.getSize());
            entity.setStoragePath(target.toFile().getAbsolutePath());
            entity.setContentHash(sha256(target.toFile()));
            entity.setBlockCount(0);
            entity.setCreatedAt(LocalDateTime.now());
            repository.save(entity);
            return toDto(entity);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("保存上传文件失败: " + e.getMessage(), e);
        }
    }

    public DocumentEntity get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + id));
    }

    public DocumentDto toDto(DocumentEntity entity) {
        return new DocumentDto(entity.getId(), entity.getName(), entity.getContentType(),
                entity.getSizeBytes(), entity.getBlockCount());
    }

    private String extensionOf(String name) {
        int idx = name.lastIndexOf('.');
        return idx < 0 ? "" : name.substring(idx + 1).toLowerCase();
    }

    private String sha256(File file) {
        try (var in = Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
