package com.docvault.controller;

import com.docvault.dto.FileUploadResponse;
import com.docvault.security.UserDetailsImpl;
import com.docvault.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.docvault.dto.FileDownloadResponse;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public ResponseEntity<FileUploadResponse> uploadFile(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserDetailsImpl userDetails) {

        FileUploadResponse response = fileService.uploadFile(file, userDetails);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{fileId}/download")
    @PreAuthorize("hasAnyRole('VIEWER', 'EDITOR', 'ADMIN')")
    public ResponseEntity<FileDownloadResponse> downloadFile(
            @PathVariable UUID fileId,
            @AuthenticationPrincipal UserDetailsImpl userDetails) {
 
        FileDownloadResponse response = fileService.generateDownloadUrl(fileId, userDetails);
        return ResponseEntity.ok(response);
    }
}
