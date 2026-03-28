package com.docvault.service;

import com.docvault.dto.*;
import com.docvault.entity.FileMetadata;
import com.docvault.entity.User;
import com.docvault.enums.UploadStatus;
import com.docvault.exception.FileValidationException;
import com.docvault.exception.ResourceNotFoundException;
import com.docvault.exception.StorageException;
import com.docvault.repository.FileMetadataRepository;
import com.docvault.repository.UserRepository;
import com.docvault.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MultipartUploadService {

    private static final Logger logger = LoggerFactory.getLogger(MultipartUploadService.class);

    // 5 MB minimum part size (S3 requirement, except last part)
    static final long MIN_PART_SIZE = 5 * 1024 * 1024;

    // 100 MB part size — keeps part count reasonable for large files
    static final long DEFAULT_PART_SIZE = 100 * 1024 * 1024;

    // 5 GB max file size for multipart uploads
    static final long MAX_FILE_SIZE = 5L * 1024 * 1024 * 1024;

    // Pre-signed URLs valid for 1 hour (large files take time to upload)
    private static final Duration PRESIGNED_URL_DURATION = Duration.ofHours(1);

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/csv",
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "application/zip",
            "application/gzip"
    );

    private final FileMetadataRepository fileMetadataRepository;
    private final UserRepository userRepository;
    private final S3StorageService s3StorageService;

    /**
     * Step 1: Validates the request, creates a PENDING metadata record,
     * initiates the S3 multipart upload, and returns pre-signed URLs
     * for each part.
     */
    @Transactional
    public MultipartUploadInitResponse initiateUpload(
            MultipartUploadInitRequest request, UserDetailsImpl userDetails) {

        validateRequest(request);

        User owner = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        String originalFilename = sanitizeFilename(request.getFilename());
        String storedFilename = UUID.randomUUID() + "_" + originalFilename;
        String s3Key = buildS3Key(owner.getId(), storedFilename);

        // Create metadata record in PENDING state
        FileMetadata metadata = FileMetadata.builder()
                .originalFilename(originalFilename)
                .storedFilename(storedFilename)
                .contentType(request.getContentType())
                .fileSize(request.getFileSize())
                .s3Key(s3Key)
                .s3Bucket(s3StorageService.getBucketName())
                .uploadStatus(UploadStatus.PENDING)
                .owner(owner)
                .build();

        metadata = fileMetadataRepository.save(metadata);

        // Initiate S3 multipart upload
        String uploadId;
        try {
            uploadId = s3StorageService.initiateMultipartUpload(s3Key, request.getContentType());
        } catch (StorageException e) {
            metadata.setUploadStatus(UploadStatus.FAILED);
            fileMetadataRepository.save(metadata);
            throw e;
        }

        // Calculate parts and generate pre-signed URLs
        long fileSize = request.getFileSize();
        long partSize = calculatePartSize(fileSize);
        int totalParts = (int) Math.ceil((double) fileSize / partSize);

        List<MultipartUploadInitResponse.PresignedPartUrl> parts = new ArrayList<>();
        for (int i = 1; i <= totalParts; i++) {
            String url = s3StorageService.generatePresignedUploadPartUrl(
                    s3Key, uploadId, i, PRESIGNED_URL_DURATION);

            parts.add(MultipartUploadInitResponse.PresignedPartUrl.builder()
                    .partNumber(i)
                    .uploadUrl(url)
                    .build());
        }

        logger.info("Initiated multipart upload [fileId={}, uploadId={}, parts={}, user='{}']",
                metadata.getId(), uploadId, totalParts, owner.getUsername());

        return MultipartUploadInitResponse.builder()
                .fileId(metadata.getId())
                .uploadId(uploadId)
                .s3Key(s3Key)
                .totalParts(totalParts)
                .partSize(partSize)
                .parts(parts)
                .build();
    }

    /**
     * Step 2: Called after the client has uploaded all parts directly to S3.
     * Completes the multipart upload and marks the file as CLEAN.
     */
    @Transactional
    public FileUploadResponse completeUpload(
            MultipartUploadCompleteRequest request, UserDetailsImpl userDetails) {

        User owner = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        FileMetadata metadata = fileMetadataRepository
                .findByIdAndOwner(request.getFileId(), owner)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "File not found with id: " + request.getFileId()));

        if (metadata.getUploadStatus() != UploadStatus.PENDING) {
            throw new FileValidationException(
                    "Upload is not in PENDING state (current: " + metadata.getUploadStatus() + ")");
        }

        // Convert DTOs to S3 part data
        List<S3StorageService.CompletedPartData> partData = request.getParts().stream()
                .map(p -> new S3StorageService.CompletedPartData(p.getPartNumber(), p.getEtag()))
                .toList();

        try {
            s3StorageService.completeMultipartUpload(
                    metadata.getS3Key(), request.getUploadId(), partData);
        } catch (StorageException e) {
            metadata.setUploadStatus(UploadStatus.FAILED);
            fileMetadataRepository.save(metadata);
            throw e;
        }

        // Mark as CLEAN (virus scanning comes in Phase 3)
        metadata.setUploadStatus(UploadStatus.CLEAN);
        metadata = fileMetadataRepository.saveAndFlush(metadata);

        logger.info("Completed multipart upload [fileId={}, uploadId={}]",
                metadata.getId(), request.getUploadId());

        return FileUploadResponse.fromEntity(metadata);
    }

    /**
     * Abort: Cancels an in-progress multipart upload.
     * Cleans up S3 parts and marks the metadata as FAILED.
     */
    @Transactional
    public void abortUpload(MultipartUploadAbortRequest request, UserDetailsImpl userDetails) {

        User owner = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        FileMetadata metadata = fileMetadataRepository
                .findByIdAndOwner(request.getFileId(), owner)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "File not found with id: " + request.getFileId()));

        if (metadata.getUploadStatus() != UploadStatus.PENDING) {
            throw new FileValidationException(
                    "Upload is not in PENDING state (current: " + metadata.getUploadStatus() + ")");
        }

        try {
            s3StorageService.abortMultipartUpload(metadata.getS3Key(), request.getUploadId());
        } catch (StorageException e) {
            logger.warn("Failed to abort S3 multipart upload, marking as FAILED anyway", e);
        }

        metadata.setUploadStatus(UploadStatus.FAILED);
        fileMetadataRepository.save(metadata);

        logger.info("Aborted multipart upload [fileId={}, uploadId={}]",
                metadata.getId(), request.getUploadId());
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    void validateRequest(MultipartUploadInitRequest request) {
        if (request.getFileSize() > MAX_FILE_SIZE) {
            throw new FileValidationException("File size exceeds maximum allowed size of 5GB");
        }

        if (request.getFileSize() < MIN_PART_SIZE) {
            throw new FileValidationException(
                    "File size is below the multipart threshold (" + MIN_PART_SIZE / (1024 * 1024)
                            + "MB). Use the single file upload endpoint instead.");
        }

        String contentType = request.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new FileValidationException(
                    "File type '" + contentType + "' is not allowed");
        }
    }

    /**
     * Calculates the optimal part size.
     * S3 requires minimum 5MB per part (except last) and max 10,000 parts.
     */
    long calculatePartSize(long fileSize) {
        // Start with default part size
        long partSize = DEFAULT_PART_SIZE;

        // If file is small enough, use the minimum part size
        if (fileSize <= MIN_PART_SIZE * 100) {
            partSize = MIN_PART_SIZE;
        }

        // Ensure we don't exceed 10,000 parts (S3 limit)
        long minPartSizeForLimit = (long) Math.ceil((double) fileSize / 10_000);
        if (minPartSizeForLimit > partSize) {
            partSize = minPartSizeForLimit;
        }

        return partSize;
    }

    String buildS3Key(Long userId, String storedFilename) {
        LocalDate now = LocalDate.now();
        return String.format("uploads/%d/%d/%02d/%s",
                userId, now.getYear(), now.getMonthValue(), storedFilename);
    }

    String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }
        String sanitized = filename.replace("\\", "/");
        int lastSlash = sanitized.lastIndexOf('/');
        if (lastSlash >= 0) {
            sanitized = sanitized.substring(lastSlash + 1);
        }
        sanitized = sanitized.trim().replaceAll("\\s+", "_");
        return sanitized.isEmpty() ? "unnamed" : sanitized;
    }
}
