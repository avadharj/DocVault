package com.docvault.dto;

import com.docvault.entity.FileMetadata;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadResponse {

    private UUID id;
    private String originalFilename;
    private String contentType;
    private Long fileSize;
    private String uploadStatus;
    private LocalDateTime createdAt;

    public static FileUploadResponse fromEntity(FileMetadata file) {
        return FileUploadResponse.builder()
                .id(file.getId())
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .fileSize(file.getFileSize())
                .uploadStatus(file.getUploadStatus().name())
                .createdAt(file.getCreatedAt())
                .build();
    }
}
