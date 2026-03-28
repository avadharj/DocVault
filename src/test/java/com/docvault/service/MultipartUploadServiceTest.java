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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MultipartUploadServiceTest {

    @Mock
    private FileMetadataRepository fileMetadataRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private S3StorageService s3StorageService;

    @InjectMocks
    private MultipartUploadService multipartUploadService;

    private User testUser;
    private UserDetailsImpl userDetails;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(1L)
                .username("editor")
                .email("editor@example.com")
                .password("hashedpassword")
                .build();

        userDetails = new UserDetailsImpl(
                1L, "editor", "editor@example.com", "hashedpassword", true,
                List.of(new SimpleGrantedAuthority("ROLE_EDITOR"))
        );
    }

    @Nested
    @DisplayName("Initiate Upload")
    class InitiateUpload {

        @Test
        @DisplayName("Given valid 50MB file, when initiating, then returns pre-signed URLs for 10 parts")
        void givenValid50MBFile_whenInitiating_thenReturnsPresignedUrlsFor10Parts() {
            // Given
            long fileSize = 50L * 1024 * 1024; // 50 MB
            MultipartUploadInitRequest request = MultipartUploadInitRequest.builder()
                    .filename("bigfile.pdf")
                    .contentType("application/pdf")
                    .fileSize(fileSize)
                    .build();

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(s3StorageService.getBucketName()).thenReturn("test-bucket");
            when(fileMetadataRepository.save(any(FileMetadata.class)))
                    .thenAnswer(inv -> {
                        FileMetadata fm = inv.getArgument(0);
                        if (fm.getId() == null) fm.setId(UUID.randomUUID());
                        return fm;
                    });
            when(s3StorageService.initiateMultipartUpload(anyString(), anyString()))
                    .thenReturn("upload-id-123");
            when(s3StorageService.generatePresignedUploadPartUrl(anyString(), anyString(), anyInt(), any()))
                    .thenReturn("https://s3.amazonaws.com/presigned-url");

            // When
            MultipartUploadInitResponse response =
                    multipartUploadService.initiateUpload(request, userDetails);

            // Then
            assertThat(response.getFileId()).isNotNull();
            assertThat(response.getUploadId()).isEqualTo("upload-id-123");
            assertThat(response.getTotalParts()).isEqualTo(10);
            assertThat(response.getPartSize()).isEqualTo(5 * 1024 * 1024);
            assertThat(response.getParts()).hasSize(10);
            assertThat(response.getParts().get(0).getPartNumber()).isEqualTo(1);
            assertThat(response.getParts().get(0).getUploadUrl()).contains("presigned");

            // Verify PENDING metadata was saved
            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getUploadStatus()).isEqualTo(UploadStatus.PENDING);
        }

        @Test
        @DisplayName("Given S3 initiate fails, when initiating, then marks metadata FAILED")
        void givenS3InitiateFails_whenInitiating_thenMarksMetadataFailed() {
            // Given
            MultipartUploadInitRequest request = MultipartUploadInitRequest.builder()
                    .filename("file.pdf")
                    .contentType("application/pdf")
                    .fileSize(10L * 1024 * 1024)
                    .build();

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(s3StorageService.getBucketName()).thenReturn("test-bucket");
            when(fileMetadataRepository.save(any(FileMetadata.class)))
                    .thenAnswer(inv -> {
                        FileMetadata fm = inv.getArgument(0);
                        if (fm.getId() == null) fm.setId(UUID.randomUUID());
                        return fm;
                    });
            when(s3StorageService.initiateMultipartUpload(anyString(), anyString()))
                    .thenThrow(new StorageException("S3 is down"));

            // When / Then
            assertThatThrownBy(() -> multipartUploadService.initiateUpload(request, userDetails))
                    .isInstanceOf(StorageException.class);

            verify(fileMetadataRepository, times(2)).save(any(FileMetadata.class));
        }
    }

    @Nested
    @DisplayName("Complete Upload")
    class CompleteUpload {

        @Test
        @DisplayName("Given valid completion request, when completing, then marks file as CLEAN")
        void givenValidCompletionRequest_whenCompleting_thenMarksFileAsClean() {
            // Given
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = FileMetadata.builder()
                    .id(fileId)
                    .originalFilename("bigfile.pdf")
                    .storedFilename("uuid_bigfile.pdf")
                    .contentType("application/pdf")
                    .fileSize(50L * 1024 * 1024)
                    .s3Key("uploads/1/2026/03/uuid_bigfile.pdf")
                    .s3Bucket("test-bucket")
                    .uploadStatus(UploadStatus.PENDING)
                    .owner(testUser)
                    .build();

            MultipartUploadCompleteRequest request = MultipartUploadCompleteRequest.builder()
                    .fileId(fileId)
                    .uploadId("upload-id-123")
                    .parts(List.of(
                            MultipartUploadCompleteRequest.CompletedPart.builder()
                                    .partNumber(1).etag("etag1").build(),
                            MultipartUploadCompleteRequest.CompletedPart.builder()
                                    .partNumber(2).etag("etag2").build()
                    ))
                    .build();

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(fileMetadataRepository.findByIdAndOwner(fileId, testUser))
                    .thenReturn(Optional.of(metadata));
            when(fileMetadataRepository.saveAndFlush(any(FileMetadata.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // When
            FileUploadResponse response =
                    multipartUploadService.completeUpload(request, userDetails);

            // Then
            assertThat(response.getUploadStatus()).isEqualTo("CLEAN");
            verify(s3StorageService).completeMultipartUpload(
                    eq("uploads/1/2026/03/uuid_bigfile.pdf"),
                    eq("upload-id-123"),
                    anyList());
        }

        @Test
        @DisplayName("Given file not owned by user, when completing, then throws ResourceNotFoundException")
        void givenFileNotOwnedByUser_whenCompleting_thenThrowsResourceNotFoundException() {
            // Given
            UUID fileId = UUID.randomUUID();
            MultipartUploadCompleteRequest request = MultipartUploadCompleteRequest.builder()
                    .fileId(fileId)
                    .uploadId("upload-id-123")
                    .parts(List.of(MultipartUploadCompleteRequest.CompletedPart.builder()
                            .partNumber(1).etag("etag1").build()))
                    .build();

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(fileMetadataRepository.findByIdAndOwner(fileId, testUser))
                    .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> multipartUploadService.completeUpload(request, userDetails))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Given file already CLEAN, when completing, then throws FileValidationException")
        void givenFileAlreadyClean_whenCompleting_thenThrowsFileValidationException() {
            // Given
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = FileMetadata.builder()
                    .id(fileId)
                    .uploadStatus(UploadStatus.CLEAN)
                    .owner(testUser)
                    .build();

            MultipartUploadCompleteRequest request = MultipartUploadCompleteRequest.builder()
                    .fileId(fileId)
                    .uploadId("upload-id-123")
                    .parts(List.of(MultipartUploadCompleteRequest.CompletedPart.builder()
                            .partNumber(1).etag("etag1").build()))
                    .build();

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(fileMetadataRepository.findByIdAndOwner(fileId, testUser))
                    .thenReturn(Optional.of(metadata));

            // When / Then
            assertThatThrownBy(() -> multipartUploadService.completeUpload(request, userDetails))
                    .isInstanceOf(FileValidationException.class)
                    .hasMessageContaining("CLEAN");
        }

        @Test
        @DisplayName("Given S3 complete fails, when completing, then marks metadata FAILED")
        void givenS3CompleteFails_whenCompleting_thenMarksMetadataFailed() {
            // Given
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = FileMetadata.builder()
                    .id(fileId)
                    .s3Key("uploads/1/2026/03/uuid_file.pdf")
                    .uploadStatus(UploadStatus.PENDING)
                    .owner(testUser)
                    .build();

            MultipartUploadCompleteRequest request = MultipartUploadCompleteRequest.builder()
                    .fileId(fileId)
                    .uploadId("upload-id-123")
                    .parts(List.of(MultipartUploadCompleteRequest.CompletedPart.builder()
                            .partNumber(1).etag("etag1").build()))
                    .build();

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(fileMetadataRepository.findByIdAndOwner(fileId, testUser))
                    .thenReturn(Optional.of(metadata));
            doThrow(new StorageException("S3 failed"))
                    .when(s3StorageService).completeMultipartUpload(anyString(), anyString(), anyList());

            // When / Then
            assertThatThrownBy(() -> multipartUploadService.completeUpload(request, userDetails))
                    .isInstanceOf(StorageException.class);

            assertThat(metadata.getUploadStatus()).isEqualTo(UploadStatus.FAILED);
            verify(fileMetadataRepository).save(metadata);
        }
    }

    @Nested
    @DisplayName("Abort Upload")
    class AbortUpload {

        @Test
        @DisplayName("Given valid abort request, when aborting, then marks metadata FAILED and calls S3 abort")
        void givenValidAbortRequest_whenAborting_thenMarksMetadataFailedAndCallsS3Abort() {
            // Given
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = FileMetadata.builder()
                    .id(fileId)
                    .s3Key("uploads/1/2026/03/uuid_file.pdf")
                    .uploadStatus(UploadStatus.PENDING)
                    .owner(testUser)
                    .build();

            MultipartUploadAbortRequest request = MultipartUploadAbortRequest.builder()
                    .fileId(fileId)
                    .uploadId("upload-id-123")
                    .build();

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(fileMetadataRepository.findByIdAndOwner(fileId, testUser))
                    .thenReturn(Optional.of(metadata));

            // When
            multipartUploadService.abortUpload(request, userDetails);

            // Then
            assertThat(metadata.getUploadStatus()).isEqualTo(UploadStatus.FAILED);
            verify(s3StorageService).abortMultipartUpload(
                    "uploads/1/2026/03/uuid_file.pdf", "upload-id-123");
            verify(fileMetadataRepository).save(metadata);
        }

        @Test
        @DisplayName("Given S3 abort fails, when aborting, then still marks metadata FAILED")
        void givenS3AbortFails_whenAborting_thenStillMarksMetadataFailed() {
            // Given
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = FileMetadata.builder()
                    .id(fileId)
                    .s3Key("uploads/1/2026/03/uuid_file.pdf")
                    .uploadStatus(UploadStatus.PENDING)
                    .owner(testUser)
                    .build();

            MultipartUploadAbortRequest request = MultipartUploadAbortRequest.builder()
                    .fileId(fileId)
                    .uploadId("upload-id-123")
                    .build();

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(fileMetadataRepository.findByIdAndOwner(fileId, testUser))
                    .thenReturn(Optional.of(metadata));
            doThrow(new StorageException("S3 unreachable"))
                    .when(s3StorageService).abortMultipartUpload(anyString(), anyString());

            // When — should NOT throw (abort is best-effort)
            multipartUploadService.abortUpload(request, userDetails);

            // Then
            assertThat(metadata.getUploadStatus()).isEqualTo(UploadStatus.FAILED);
            verify(fileMetadataRepository).save(metadata);
        }
    }

    @Nested
    @DisplayName("Validate Request")
    class ValidateRequest {

        @Test
        @DisplayName("Given file below multipart threshold, when validating, then throws")
        void givenFileBelowThreshold_whenValidating_thenThrows() {
            MultipartUploadInitRequest request = MultipartUploadInitRequest.builder()
                    .filename("small.pdf")
                    .contentType("application/pdf")
                    .fileSize(1024L) // 1 KB
                    .build();

            assertThatThrownBy(() -> multipartUploadService.validateRequest(request))
                    .isInstanceOf(FileValidationException.class)
                    .hasMessageContaining("single file upload");
        }

        @Test
        @DisplayName("Given file exceeding 5GB, when validating, then throws")
        void givenFileExceeding5GB_whenValidating_thenThrows() {
            MultipartUploadInitRequest request = MultipartUploadInitRequest.builder()
                    .filename("huge.zip")
                    .contentType("application/zip")
                    .fileSize(6L * 1024 * 1024 * 1024) // 6 GB
                    .build();

            assertThatThrownBy(() -> multipartUploadService.validateRequest(request))
                    .isInstanceOf(FileValidationException.class)
                    .hasMessageContaining("5GB");
        }

        @Test
        @DisplayName("Given disallowed content type, when validating, then throws")
        void givenDisallowedContentType_whenValidating_thenThrows() {
            MultipartUploadInitRequest request = MultipartUploadInitRequest.builder()
                    .filename("malware.exe")
                    .contentType("application/x-executable")
                    .fileSize(10L * 1024 * 1024)
                    .build();

            assertThatThrownBy(() -> multipartUploadService.validateRequest(request))
                    .isInstanceOf(FileValidationException.class)
                    .hasMessageContaining("not allowed");
        }
    }

    @Nested
    @DisplayName("Calculate Part Size")
    class CalculatePartSize {

        @Test
        @DisplayName("Given 50MB file, when calculating, then returns 5MB parts")
        void given50MBFile_whenCalculating_thenReturns5MBParts() {
            long partSize = multipartUploadService.calculatePartSize(50L * 1024 * 1024);
            assertThat(partSize).isEqualTo(5L * 1024 * 1024);
        }

        @Test
        @DisplayName("Given 1GB file, when calculating, then returns 100MB parts")
        void given1GBFile_whenCalculating_thenReturns100MBParts() {
            long partSize = multipartUploadService.calculatePartSize(1024L * 1024 * 1024);
            assertThat(partSize).isEqualTo(100L * 1024 * 1024);
        }

        @Test
        @DisplayName("Given 5GB file, when calculating, then respects 10000 part limit")
        void given5GBFile_whenCalculating_thenRespects10000PartLimit() {
            long fileSize = 5L * 1024 * 1024 * 1024;
            long partSize = multipartUploadService.calculatePartSize(fileSize);
            int partCount = (int) Math.ceil((double) fileSize / partSize);
            assertThat(partCount).isLessThanOrEqualTo(10_000);
        }
    }
}
