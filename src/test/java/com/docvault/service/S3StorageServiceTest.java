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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3StorageServiceTest {

    @Mock
    private S3Client s3Client;

    private S3StorageService s3StorageService;

    @BeforeEach
    void setUp() throws Exception {
        s3StorageService = new S3StorageService(s3Client);

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
}
