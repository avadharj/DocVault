package com.docvault.service;

import com.docvault.exception.StorageException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Low-level S3 operations. This service knows about S3 but nothing
 * about FileMetadata, users, or business rules.
 */
@Service
@RequiredArgsConstructor
public class S3StorageService {

    private static final Logger logger = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${app.aws.s3.bucket-name}")
    private String bucketName;

    // ========================================================================
    // SINGLE FILE UPLOAD (existing — for files under the multipart threshold)
    // ========================================================================

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

    // ========================================================================
    // PRE-SIGNED DOWNLOAD URL (existing)
    // ========================================================================

    public String generatePresignedDownloadUrl(String s3Key, Duration duration) {
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(duration)
                    .getObjectRequest(getRequest)
                    .build();

            PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
            logger.info("Generated pre-signed download URL for s3://{}/{}", bucketName, s3Key);
            return presigned.url().toString();
        } catch (Exception e) {
            logger.error("Failed to generate pre-signed URL for s3://{}/{}", bucketName, s3Key, e);
            throw new StorageException("Failed to generate download URL", e);
        }
    }

    // ========================================================================
    // MULTIPART UPLOAD — client-side with pre-signed URLs
    // ========================================================================

    /**
     * Step 1: Initiates a multipart upload in S3.
     * Returns an upload ID that the client uses for all subsequent part uploads.
     */
    public String initiateMultipartUpload(String s3Key, String contentType) {
        try {
            CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType(contentType)
                    .build();

            CreateMultipartUploadResponse response = s3Client.createMultipartUpload(request);
            String uploadId = response.uploadId();

            logger.info("Initiated multipart upload [uploadId={}, s3Key={}]", uploadId, s3Key);
            return uploadId;
        } catch (Exception e) {
            logger.error("Failed to initiate multipart upload for s3://{}/{}", bucketName, s3Key, e);
            throw new StorageException("Failed to initiate multipart upload", e);
        }
    }

    /**
     * Step 2: Generates a pre-signed URL for uploading a single part.
     * The client PUTs bytes directly to this URL — no data passes through our server.
     */
    public String generatePresignedUploadPartUrl(String s3Key, String uploadId,
                                                  int partNumber, Duration duration) {
        try {
            UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .build();

            UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                    .signatureDuration(duration)
                    .uploadPartRequest(uploadPartRequest)
                    .build();

            PresignedUploadPartRequest presigned = s3Presigner.presignUploadPart(presignRequest);

            logger.debug("Generated pre-signed upload URL for part {} [uploadId={}]",
                    partNumber, uploadId);
            return presigned.url().toString();
        } catch (Exception e) {
            logger.error("Failed to generate pre-signed upload URL for part {} [uploadId={}]",
                    partNumber, uploadId, e);
            throw new StorageException("Failed to generate upload URL for part " + partNumber, e);
        }
    }

    /**
     * Step 3: Completes the multipart upload by assembling all parts.
     * Called after the client has uploaded all parts and reports their ETags.
     */
    public void completeMultipartUpload(String s3Key, String uploadId,
                                         List<CompletedPartData> parts) {
        try {
            List<CompletedPart> s3Parts = parts.stream()
                    .map(p -> CompletedPart.builder()
                            .partNumber(p.partNumber())
                            .eTag(p.etag())
                            .build())
                    .collect(Collectors.toList());

            CompleteMultipartUploadRequest request = CompleteMultipartUploadRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder()
                            .parts(s3Parts)
                            .build())
                    .build();

            s3Client.completeMultipartUpload(request);
            logger.info("Completed multipart upload [uploadId={}, s3Key={}, parts={}]",
                    uploadId, s3Key, parts.size());
        } catch (Exception e) {
            logger.error("Failed to complete multipart upload [uploadId={}, s3Key={}]",
                    uploadId, s3Key, e);
            throw new StorageException("Failed to complete multipart upload", e);
        }
    }

    /**
     * Abort: Cancels an in-progress multipart upload, cleaning up any uploaded parts.
     * Important for avoiding storage charges on abandoned uploads.
     */
    public void abortMultipartUpload(String s3Key, String uploadId) {
        try {
            AbortMultipartUploadRequest request = AbortMultipartUploadRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .uploadId(uploadId)
                    .build();

            s3Client.abortMultipartUpload(request);
            logger.info("Aborted multipart upload [uploadId={}, s3Key={}]", uploadId, s3Key);
        } catch (Exception e) {
            logger.error("Failed to abort multipart upload [uploadId={}, s3Key={}]",
                    uploadId, s3Key, e);
            throw new StorageException("Failed to abort multipart upload", e);
        }
    }

    public String getBucketName() {
        return bucketName;
    }

    /**
     * Simple record to pass completed part data without depending on AWS SDK types
     * in the service layer above.
     */
    public record CompletedPartData(int partNumber, String etag) {}
}
