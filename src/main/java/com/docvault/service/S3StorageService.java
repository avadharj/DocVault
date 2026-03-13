package com.docvault.service;

import com.docvault.exception.StorageException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

/**
 * Low-level S3 operations. This service knows about S3 but nothing
 * about FileMetadata, users, or business rules. That separation
 * keeps it testable and reusable.
 */
@Service
@RequiredArgsConstructor
public class S3StorageService {

    private static final Logger logger = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3Client;

    @Value("${app.aws.s3.bucket-name}")
    private String bucketName;

    /**
     * Uploads a file to S3.
     *
     * @param s3Key       the full object key (e.g., "uploads/1/2026/03/uuid_report.pdf")
     * @param inputStream the file content
     * @param fileSize    content length in bytes (required for S3 PutObject)
     * @param contentType MIME type
     */
    public void uploadFile(String s3Key, InputStream inputStream, long fileSize, String contentType) {
        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType(contentType)
                    .contentLength(fileSize)
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromInputStream(inputStream, fileSize));

            logger.info("Uploaded file to S3: s3://{}/{}", bucketName, s3Key);
        } catch (Exception e) {
            logger.error("Failed to upload file to S3: s3://{}/{}", bucketName, s3Key, e);
            throw new StorageException("Failed to upload file to S3", e);
        }
    }

    public String getBucketName() {
        return bucketName;
    }
}
