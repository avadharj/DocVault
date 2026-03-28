package com.docvault.controller;

import com.docvault.dto.*;
import com.docvault.security.UserDetailsImpl;
import com.docvault.service.MultipartUploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files/multipart")
@RequiredArgsConstructor
public class MultipartUploadController {

    private final MultipartUploadService multipartUploadService;

    /**
     * Step 1: Client sends file metadata (name, size, type).
     * Server creates a PENDING record, initiates S3 multipart upload,
     * and returns pre-signed URLs for each part.
     */
    @PostMapping("/initiate")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public ResponseEntity<MultipartUploadInitResponse> initiateUpload(
            @Valid @RequestBody MultipartUploadInitRequest request,
            @AuthenticationPrincipal UserDetailsImpl userDetails) {

        MultipartUploadInitResponse response =
                multipartUploadService.initiateUpload(request, userDetails);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Step 2: Client has uploaded all parts directly to S3 using the
     * pre-signed URLs. Now it reports the ETags to complete the upload.
     */
    @PostMapping("/complete")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public ResponseEntity<FileUploadResponse> completeUpload(
            @Valid @RequestBody MultipartUploadCompleteRequest request,
            @AuthenticationPrincipal UserDetailsImpl userDetails) {

        FileUploadResponse response =
                multipartUploadService.completeUpload(request, userDetails);
        return ResponseEntity.ok(response);
    }

    /**
     * Abort: Client cancels an in-progress upload.
     * Cleans up S3 parts and marks metadata as FAILED.
     */
    @PostMapping("/abort")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public ResponseEntity<MessageResponse> abortUpload(
            @Valid @RequestBody MultipartUploadAbortRequest request,
            @AuthenticationPrincipal UserDetailsImpl userDetails) {

        multipartUploadService.abortUpload(request, userDetails);
        return ResponseEntity.ok(MessageResponse.of("Upload aborted successfully"));
    }
}
