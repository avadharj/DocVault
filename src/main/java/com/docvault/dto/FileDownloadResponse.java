package com.docvault.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileDownloadResponse {

    private UUID fileId;
    private String originalFilename;
    private String contentType;
    private Long fileSize;
    private String downloadUrl;
    private Instant expiresAt;
}
