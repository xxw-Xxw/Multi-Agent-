package com.docreview.web;

import com.docreview.security.CurrentUser;
import com.docreview.service.DocumentService;
import com.docreview.web.dto.DocumentDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public DocumentDto upload(@RequestParam("file") MultipartFile file,
                              @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser currentUser) {
        return documentService.upload(file, currentUser.userId());
    }

    /** 前端用同一份清单渲染 accept，避免与后端校验规则漂移。 */
    @GetMapping("/allowed-types")
    public List<String> allowedTypes() {
        return DocumentService.allowedExtensions().stream().sorted().toList();
    }
}
