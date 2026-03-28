package com.docvault.service;

import com.docvault.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;
import java.net.URI;
import java.util.List;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3StorageServiceTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    private S3StorageService s3StorageService;

    @BeforeEach
    void setUp() throws Exception {
        s3StorageService = new S3StorageService(s3Client, s3Presigner);

        // Inject the bucket name via reflection since @Value won't work in unit tests
        Field bucketNameField = S3StorageService.class.getDeclaredField("bucketName");
        bucketNameField.setAccessible(true);
        bucketNameField.set(s3StorageService, "test-bucket");
    }

    @Nested
    @DisplayName("Upload File")
    class UploadFile {

        @Test
        @DisplayName("Given valid inputs, when uploading, then calls S3 PutObject with correct parameters")
        void givenValidInputs_whenUploading_thenCallsS3PutObjectWithCorrectParameters() {
            // Given
            String s3Key = "uploads/1/2026/03/uuid_report.pdf";
            InputStream content = new ByteArrayInputStream("file content".getBytes());
            long fileSize = 12L;
            String contentType = "application/pdf";

            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());

            // When
            s3StorageService.uploadFile(s3Key, content, fileSize, contentType);

            // Then
            ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));

            PutObjectRequest captured = requestCaptor.getValue();
            assertThat(captured.bucket()).isEqualTo("test-bucket");
            assertThat(captured.key()).isEqualTo(s3Key);
            assertThat(captured.contentType()).isEqualTo("application/pdf");
            assertThat(captured.contentLength()).isEqualTo(12L);
        }

        @Test
        @DisplayName("Given S3 throws exception, when uploading, then wraps in StorageException")
        void givenS3ThrowsException_whenUploading_thenWrapsInStorageException() {
            // Given
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow(S3Exception.builder().message("Access Denied").build());

            // When / Then
            assertThatThrownBy(() ->
                    s3StorageService.uploadFile("key", new ByteArrayInputStream(new byte[0]),
                            0, "application/pdf"))
                    .isInstanceOf(StorageException.class)
                    .hasMessage("Failed to upload file to S3");
        }
    }

    @Test
    @DisplayName("Given bucket name configured, when getting bucket name, then returns correct value")
    void givenBucketNameConfigured_whenGettingBucketName_thenReturnsCorrectValue() {
        assertThat(s3StorageService.getBucketName()).isEqualTo("test-bucket");
    }

    @Nested
    @DisplayName("Generate Presigned Download URL")
    class GeneratePresignedDownloadUrl {

        @Test
        @DisplayName("Given valid S3 key, when generating presigned URL, then returns URL string")
        void givenValidS3Key_whenGeneratingPresignedUrl_thenReturnsUrlString() throws Exception {
            // Given
            PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
            when(presigned.url()).thenReturn(URI.create(
                    "https://test-bucket.s3.amazonaws.com/uploads/1/2026/03/uuid_report.pdf?X-Amz-Signature=abc").toURL());
            when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                    .thenReturn(presigned);

            // When
            String url = s3StorageService.generatePresignedDownloadUrl(
                    "uploads/1/2026/03/uuid_report.pdf", Duration.ofMinutes(15));

            // Then
            assertThat(url).contains("test-bucket");
            assertThat(url).contains("uuid_report.pdf");
            verify(s3Presigner).presignGetObject(any(GetObjectPresignRequest.class));
        }

        @Test
        @DisplayName("Given S3 presigner throws, when generating presigned URL, then wraps in StorageException")
        void givenS3PresignerThrows_whenGeneratingPresignedUrl_thenWrapsInStorageException() {
            // Given
            when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                    .thenThrow(new RuntimeException("Presigner broken"));

            // When / Then
            assertThatThrownBy(() ->
                    s3StorageService.generatePresignedDownloadUrl("key", Duration.ofMinutes(15)))
                    .isInstanceOf(StorageException.class)
                    .hasMessage("Failed to generate download URL");
        }
    }

    @Nested
    @DisplayName("Initiate Multipart Upload")
    class InitiateMultipartUpload {
 
        @Test
        @DisplayName("Given valid inputs, when initiating, then returns upload ID")
        void givenValidInputs_whenInitiating_thenReturnsUploadId() {
            // Given
            CreateMultipartUploadResponse response = CreateMultipartUploadResponse.builder()
                    .uploadId("upload-id-abc")
                    .build();
            when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                    .thenReturn(response);
 
            // When
            String uploadId = s3StorageService.initiateMultipartUpload("key", "application/pdf");
 
            // Then
            assertThat(uploadId).isEqualTo("upload-id-abc");
        }
 
        @Test
        @DisplayName("Given S3 throws, when initiating, then wraps in StorageException")
        void givenS3Throws_whenInitiating_thenWrapsInStorageException() {
            when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                    .thenThrow(S3Exception.builder().message("Failed").build());
 
            assertThatThrownBy(() -> s3StorageService.initiateMultipartUpload("key", "application/pdf"))
                    .isInstanceOf(StorageException.class);
        }
    }
 
    @Nested
    @DisplayName("Generate Presigned Upload Part URL")
    class GeneratePresignedUploadPartUrl {
 
        @Test
        @DisplayName("Given valid inputs, when generating, then returns URL")
        void givenValidInputs_whenGenerating_thenReturnsUrl() throws Exception {
            PresignedUploadPartRequest presigned = mock(PresignedUploadPartRequest.class);
            when(presigned.url()).thenReturn(
                    URI.create("https://bucket.s3.amazonaws.com/key?uploadId=abc&partNumber=1").toURL());
            when(s3Presigner.presignUploadPart(any(UploadPartPresignRequest.class)))
                    .thenReturn(presigned);
 
            String url = s3StorageService.generatePresignedUploadPartUrl(
                    "key", "upload-id", 1, Duration.ofHours(1));
 
            assertThat(url).contains("partNumber=1");
        }
 
        @Test
        @DisplayName("Given presigner throws, when generating, then wraps in StorageException")
        void givenPresignerThrows_whenGenerating_thenWrapsInStorageException() {
            when(s3Presigner.presignUploadPart(any(UploadPartPresignRequest.class)))
                    .thenThrow(new RuntimeException("Presigner broke"));
 
            assertThatThrownBy(() -> s3StorageService.generatePresignedUploadPartUrl(
                    "key", "upload-id", 1, Duration.ofHours(1)))
                    .isInstanceOf(StorageException.class);
        }
    }
 
    @Nested
    @DisplayName("Complete Multipart Upload")
    class CompleteMultipartUpload {
 
        @Test
        @DisplayName("Given valid parts, when completing, then calls S3 with correct ETags")
        void givenValidParts_whenCompleting_thenCallsS3WithCorrectETags() {
            when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                    .thenReturn(CompleteMultipartUploadResponse.builder().build());
 
            List<S3StorageService.CompletedPartData> parts = List.of(
                    new S3StorageService.CompletedPartData(1, "etag1"),
                    new S3StorageService.CompletedPartData(2, "etag2")
            );
 
            s3StorageService.completeMultipartUpload("key", "upload-id", parts);
 
            ArgumentCaptor<CompleteMultipartUploadRequest> captor =
                    ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
            verify(s3Client).completeMultipartUpload(captor.capture());
 
            assertThat(captor.getValue().multipartUpload().parts()).hasSize(2);
        }
 
        @Test
        @DisplayName("Given S3 throws, when completing, then wraps in StorageException")
        void givenS3Throws_whenCompleting_thenWrapsInStorageException() {
            when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                    .thenThrow(S3Exception.builder().message("Failed").build());
 
            assertThatThrownBy(() -> s3StorageService.completeMultipartUpload(
                    "key", "upload-id", List.of(new S3StorageService.CompletedPartData(1, "etag"))))
                    .isInstanceOf(StorageException.class);
        }
    }
 
    @Nested
    @DisplayName("Abort Multipart Upload")
    class AbortMultipartUpload {
 
        @Test
        @DisplayName("Given valid inputs, when aborting, then calls S3 abort")
        void givenValidInputs_whenAborting_thenCallsS3Abort() {
            when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                    .thenReturn(AbortMultipartUploadResponse.builder().build());
 
            s3StorageService.abortMultipartUpload("key", "upload-id");
 
            verify(s3Client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        }
 
        @Test
        @DisplayName("Given S3 throws, when aborting, then wraps in StorageException")
        void givenS3Throws_whenAborting_thenWrapsInStorageException() {
            when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                    .thenThrow(S3Exception.builder().message("Failed").build());
 
            assertThatThrownBy(() -> s3StorageService.abortMultipartUpload("key", "upload-id"))
                    .isInstanceOf(StorageException.class);
        }
    }
}
