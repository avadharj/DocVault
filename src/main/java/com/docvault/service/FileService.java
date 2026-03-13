package com.docvault.service;

import com.docvault.dto.FileUploadResponse;
import com.docvault.entity.FileMetadata;
import com.docvault.entity.User;
import com.docvault.enums.UploadStatus;
import com.docvault.exception.FileValidationException;
import com.docvault.exception.StorageException;
import com.docvault.repository.FileMetadataRepository;
import com.docvault.repository.UserRepository;
import com.docvault.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileService {

    private static final Logger logger = LoggerFactory.getLogger(FileService.class);

    // 100 MB in bytes
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            // Documents
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/csv",
            // Images
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            // Archives
            "application/zip",
            "application/gzip"
    );

    private final FileMetadataRepository fileMetadataRepository;
    private final UserRepository userRepository;
    private final S3StorageService s3StorageService;

    @Transactional
    public FileUploadResponse uploadFile(MultipartFile file, UserDetailsImpl userDetails) {
        validateFile(file);

        User owner = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        String storedFilename = UUID.randomUUID() + "_" + originalFilename;
        String s3Key = buildS3Key(owner.getId(), storedFilename);

        // 1. Save metadata as PENDING before uploading to S3
        FileMetadata metadata = FileMetadata.builder()
                .originalFilename(originalFilename)
                .storedFilename(storedFilename)
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .s3Key(s3Key)
                .s3Bucket(s3StorageService.getBucketName())
                .uploadStatus(UploadStatus.PENDING)
                .owner(owner)
                .build();

        metadata = fileMetadataRepository.save(metadata);
        logger.info("Created file metadata [id={}] for user '{}'", metadata.getId(), owner.getUsername());

        // 2. Upload to S3
        try {
            s3StorageService.uploadFile(
                    s3Key,
                    file.getInputStream(),
                    file.getSize(),
                    file.getContentType()
            );
        } catch (IOException e) {
            logger.error("Failed to read uploaded file: {}", originalFilename, e);
            metadata.setUploadStatus(UploadStatus.FAILED);
            fileMetadataRepository.save(metadata);
            throw new StorageException("Failed to read uploaded file", e);
        } catch (StorageException e) {
            metadata.setUploadStatus(UploadStatus.FAILED);
            fileMetadataRepository.save(metadata);
            throw e;
        }

        // 3. Mark as CLEAN (virus scanning comes in Phase 3)
        metadata.setUploadStatus(UploadStatus.CLEAN);
        metadata = fileMetadataRepository.saveAndFlush(metadata);
        logger.info("File upload complete [id={}, s3Key={}]", metadata.getId(), s3Key);

        return FileUploadResponse.fromEntity(metadata);
    }

    /**
     * Validates the uploaded file against size and content type restrictions.
     */
    void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileValidationException("File is required and must not be empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new FileValidationException(
                    "File size exceeds maximum allowed size of 100MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new FileValidationException(
                    "File type '" + contentType + "' is not allowed");
        }
    }

    /**
     * Builds an S3 key with a logical folder structure:
     * uploads/{userId}/{year}/{month}/{storedFilename}
     */
    String buildS3Key(Long userId, String storedFilename) {
        LocalDate now = LocalDate.now();
        return String.format("uploads/%d/%d/%02d/%s",
                userId, now.getYear(), now.getMonthValue(), storedFilename);
    }

    /**
     * Sanitizes the original filename to prevent path traversal and weird characters.
     * Strips directory separators, trims whitespace, replaces spaces with underscores.
     */
    String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }
        // Strip any path components (e.g., "../../etc/passwd" → "passwd")
        String sanitized = filename.replace("\\", "/");
        int lastSlash = sanitized.lastIndexOf('/');
        if (lastSlash >= 0) {
            sanitized = sanitized.substring(lastSlash + 1);
        }
        // Replace spaces, trim, and fallback if empty
        sanitized = sanitized.trim().replaceAll("\\s+", "_");
        return sanitized.isEmpty() ? "unnamed" : sanitized;
    }
}
