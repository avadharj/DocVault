package com.docvault.service;

import com.docvault.dto.FileDownloadResponse;
import com.docvault.dto.FileUploadResponse;
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
import org.mockito.stubbing.Answer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    @Mock
    private FileMetadataRepository fileMetadataRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private S3StorageService s3StorageService;

    @InjectMocks
    private FileService fileService;

    private User testUser;
    private UserDetailsImpl userDetails;

    private final Answer<FileMetadata> saveAnswer = invocation -> {
        FileMetadata fm = invocation.getArgument(0);
        if (fm.getId() == null) fm.setId(UUID.randomUUID());
        return fm;
    };

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
    @DisplayName("Upload File")
    class UploadFile {

        @Test
        @DisplayName("Given valid PDF file, when uploading, then saves metadata and uploads to S3")
        void givenValidPdfFile_whenUploading_thenSavesMetadataAndUploadsToS3() throws IOException {
            // Given
            MultipartFile file = mockMultipartFile("report.pdf", "application/pdf",
                    1024, new byte[1024]);

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(s3StorageService.getBucketName()).thenReturn("test-bucket");
            when(fileMetadataRepository.save(any(FileMetadata.class))).thenAnswer(saveAnswer);
            when(fileMetadataRepository.saveAndFlush(any(FileMetadata.class))).thenAnswer(saveAnswer);

            // When
            FileUploadResponse response = fileService.uploadFile(file, userDetails);

            // Then
            assertThat(response.getOriginalFilename()).isEqualTo("report.pdf");
            assertThat(response.getContentType()).isEqualTo("application/pdf");
            assertThat(response.getFileSize()).isEqualTo(1024);
            assertThat(response.getUploadStatus()).isEqualTo("CLEAN");
            assertThat(response.getId()).isNotNull();

            // Verify S3 upload was called
            verify(s3StorageService).uploadFile(anyString(), any(), eq(1024L), eq("application/pdf"));

            // Verify metadata saves: save(PENDING) + saveAndFlush(CLEAN)
            verify(fileMetadataRepository).save(any(FileMetadata.class));
            verify(fileMetadataRepository).saveAndFlush(any(FileMetadata.class));
        }

        @Test
        @DisplayName("Given valid file, when uploading, then S3 key follows expected pattern")
        void givenValidFile_whenUploading_thenS3KeyFollowsExpectedPattern() throws IOException {
            // Given
            MultipartFile file = mockMultipartFile("test.png", "image/png",
                    512, new byte[512]);

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(s3StorageService.getBucketName()).thenReturn("test-bucket");
            when(fileMetadataRepository.save(any(FileMetadata.class))).thenAnswer(saveAnswer);
            when(fileMetadataRepository.saveAndFlush(any(FileMetadata.class))).thenAnswer(saveAnswer);

            // When
            fileService.uploadFile(file, userDetails);

            // Then — capture the metadata to inspect the S3 key
            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());

            FileMetadata saved = captor.getValue();
            assertThat(saved.getS3Key()).startsWith("uploads/1/");
            assertThat(saved.getS3Key()).endsWith("_test.png");
            assertThat(saved.getS3Bucket()).isEqualTo("test-bucket");
            assertThat(saved.getOwner()).isEqualTo(testUser);
        }

        @Test
        @DisplayName("Given S3 upload fails, when uploading, then marks metadata as FAILED and throws")
        void givenS3UploadFails_whenUploading_thenMarksMetadataAsFailedAndThrows() throws IOException {
            // Given
            MultipartFile file = mockMultipartFile("report.pdf", "application/pdf",
                    1024, new byte[1024]);

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(s3StorageService.getBucketName()).thenReturn("test-bucket");
            when(fileMetadataRepository.save(any(FileMetadata.class))).thenAnswer(saveAnswer);

            doThrow(new StorageException("S3 is down"))
                    .when(s3StorageService).uploadFile(anyString(), any(), anyLong(), anyString());

            // When / Then
            assertThatThrownBy(() -> fileService.uploadFile(file, userDetails))
                    .isInstanceOf(StorageException.class);

            // Verify status was set to FAILED: save(PENDING) + save(FAILED)
            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository, times(2)).save(captor.capture());

            FileMetadata lastSave = captor.getAllValues().get(1);
            assertThat(lastSave.getUploadStatus()).isEqualTo(UploadStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("Validate File")
    class ValidateFile {

        @Test
        @DisplayName("Given null file, when validating, then throws FileValidationException")
        void givenNullFile_whenValidating_thenThrowsFileValidationException() {
            assertThatThrownBy(() -> fileService.validateFile(null))
                    .isInstanceOf(FileValidationException.class)
                    .hasMessage("File is required and must not be empty");
        }

        @Test
        @DisplayName("Given empty file, when validating, then throws FileValidationException")
        void givenEmptyFile_whenValidating_thenThrowsFileValidationException() {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(true);

            assertThatThrownBy(() -> fileService.validateFile(file))
                    .isInstanceOf(FileValidationException.class)
                    .hasMessage("File is required and must not be empty");
        }

        @Test
        @DisplayName("Given file exceeding max size, when validating, then throws FileValidationException")
        void givenFileExceedingMaxSize_whenValidating_thenThrowsFileValidationException() {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(101L * 1024 * 1024); // 101 MB

            assertThatThrownBy(() -> fileService.validateFile(file))
                    .isInstanceOf(FileValidationException.class)
                    .hasMessageContaining("100MB");
        }

        @Test
        @DisplayName("Given disallowed content type, when validating, then throws FileValidationException")
        void givenDisallowedContentType_whenValidating_thenThrowsFileValidationException() {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            when(file.getContentType()).thenReturn("application/x-executable");

            assertThatThrownBy(() -> fileService.validateFile(file))
                    .isInstanceOf(FileValidationException.class)
                    .hasMessageContaining("not allowed");
        }

        @Test
        @DisplayName("Given null content type, when validating, then throws FileValidationException")
        void givenNullContentType_whenValidating_thenThrowsFileValidationException() {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            when(file.getContentType()).thenReturn(null);

            assertThatThrownBy(() -> fileService.validateFile(file))
                    .isInstanceOf(FileValidationException.class)
                    .hasMessageContaining("not allowed");
        }

        @Test
        @DisplayName("Given valid PDF, when validating, then does not throw")
        void givenValidPdf_whenValidating_thenDoesNotThrow() {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            when(file.getContentType()).thenReturn("application/pdf");

            // Should not throw
            fileService.validateFile(file);
        }
    }

    @Nested
    @DisplayName("Sanitize Filename")
    class SanitizeFilename {

        @Test
        @DisplayName("Given normal filename, when sanitizing, then returns unchanged")
        void givenNormalFilename_whenSanitizing_thenReturnsUnchanged() {
            assertThat(fileService.sanitizeFilename("report.pdf")).isEqualTo("report.pdf");
        }

        @Test
        @DisplayName("Given path traversal attempt, when sanitizing, then strips directory components")
        void givenPathTraversalAttempt_whenSanitizing_thenStripsDirectoryComponents() {
            assertThat(fileService.sanitizeFilename("../../etc/passwd")).isEqualTo("passwd");
        }

        @Test
        @DisplayName("Given Windows path, when sanitizing, then strips directory components")
        void givenWindowsPath_whenSanitizing_thenStripsDirectoryComponents() {
            assertThat(fileService.sanitizeFilename("C:\\Users\\evil\\malware.exe")).isEqualTo("malware.exe");
        }

        @Test
        @DisplayName("Given filename with spaces, when sanitizing, then replaces with underscores")
        void givenFilenameWithSpaces_whenSanitizing_thenReplacesWithUnderscores() {
            assertThat(fileService.sanitizeFilename("my report final.pdf")).isEqualTo("my_report_final.pdf");
        }

        @Test
        @DisplayName("Given null filename, when sanitizing, then returns 'unnamed'")
        void givenNullFilename_whenSanitizing_thenReturnsUnnamed() {
            assertThat(fileService.sanitizeFilename(null)).isEqualTo("unnamed");
        }

        @Test
        @DisplayName("Given blank filename, when sanitizing, then returns 'unnamed'")
        void givenBlankFilename_whenSanitizing_thenReturnsUnnamed() {
            assertThat(fileService.sanitizeFilename("   ")).isEqualTo("unnamed");
        }
    }

    @Nested
    @DisplayName("Build S3 Key")
    class BuildS3Key {

        @Test
        @DisplayName("Given user ID and filename, when building S3 key, then follows expected pattern")
        void givenUserIdAndFilename_whenBuildingS3Key_thenFollowsExpectedPattern() {
            String key = fileService.buildS3Key(42L, "abc123_report.pdf");

            assertThat(key).startsWith("uploads/42/");
            assertThat(key).endsWith("/abc123_report.pdf");
            // Should contain year and month segments
            assertThat(key.split("/")).hasSize(5);
        }
    }

    @Nested
    @DisplayName("Generate Download URL")
    class GenerateDownloadUrl {

        @Test
        @DisplayName("Given valid file owned by user, when generating download URL, then returns pre-signed URL")
        void givenValidFileOwnedByUser_whenGeneratingDownloadUrl_thenReturnsPresignedUrl() {
            // Given
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = FileMetadata.builder()
                    .id(fileId)
                    .originalFilename("report.pdf")
                    .storedFilename("uuid_report.pdf")
                    .contentType("application/pdf")
                    .fileSize(2048L)
                    .s3Key("uploads/1/2026/03/uuid_report.pdf")
                    .s3Bucket("test-bucket")
                    .uploadStatus(UploadStatus.CLEAN)
                    .owner(testUser)
                    .build();

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(fileMetadataRepository.findByIdAndOwner(fileId, testUser))
                    .thenReturn(Optional.of(metadata));
            when(s3StorageService.generatePresignedDownloadUrl(anyString(), any(Duration.class)))
                    .thenReturn("https://s3.amazonaws.com/test-bucket/uploads/1/2026/03/uuid_report.pdf?signed");

            // When
            FileDownloadResponse response = fileService.generateDownloadUrl(fileId, userDetails);

            // Then
            assertThat(response.getFileId()).isEqualTo(fileId);
            assertThat(response.getOriginalFilename()).isEqualTo("report.pdf");
            assertThat(response.getContentType()).isEqualTo("application/pdf");
            assertThat(response.getFileSize()).isEqualTo(2048L);
            assertThat(response.getDownloadUrl()).contains("signed");
            assertThat(response.getExpiresAt()).isNotNull();

            verify(s3StorageService).generatePresignedDownloadUrl(
                    eq("uploads/1/2026/03/uuid_report.pdf"), any(Duration.class));
        }

        @Test
        @DisplayName("Given file not owned by user, when generating download URL, then throws ResourceNotFoundException")
        void givenFileNotOwnedByUser_whenGeneratingDownloadUrl_thenThrowsResourceNotFoundException() {
            // Given
            UUID fileId = UUID.randomUUID();

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(fileMetadataRepository.findByIdAndOwner(fileId, testUser))
                    .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> fileService.generateDownloadUrl(fileId, userDetails))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(fileId.toString());
        }

        @Test
        @DisplayName("Given file with PENDING status, when generating download URL, then throws FileValidationException")
        void givenFileWithPendingStatus_whenGeneratingDownloadUrl_thenThrowsFileValidationException() {
            // Given
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = FileMetadata.builder()
                    .id(fileId)
                    .originalFilename("report.pdf")
                    .storedFilename("uuid_report.pdf")
                    .contentType("application/pdf")
                    .fileSize(2048L)
                    .s3Key("uploads/1/2026/03/uuid_report.pdf")
                    .s3Bucket("test-bucket")
                    .uploadStatus(UploadStatus.PENDING)
                    .owner(testUser)
                    .build();

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(fileMetadataRepository.findByIdAndOwner(fileId, testUser))
                    .thenReturn(Optional.of(metadata));

            // When / Then
            assertThatThrownBy(() -> fileService.generateDownloadUrl(fileId, userDetails))
                    .isInstanceOf(FileValidationException.class)
                    .hasMessageContaining("PENDING");
        }

        @Test
        @DisplayName("Given file with FAILED status, when generating download URL, then throws FileValidationException")
        void givenFileWithFailedStatus_whenGeneratingDownloadUrl_thenThrowsFileValidationException() {
            // Given
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = FileMetadata.builder()
                    .id(fileId)
                    .originalFilename("report.pdf")
                    .storedFilename("uuid_report.pdf")
                    .contentType("application/pdf")
                    .fileSize(2048L)
                    .s3Key("uploads/1/2026/03/uuid_report.pdf")
                    .s3Bucket("test-bucket")
                    .uploadStatus(UploadStatus.FAILED)
                    .owner(testUser)
                    .build();

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(fileMetadataRepository.findByIdAndOwner(fileId, testUser))
                    .thenReturn(Optional.of(metadata));

            // When / Then
            assertThatThrownBy(() -> fileService.generateDownloadUrl(fileId, userDetails))
                    .isInstanceOf(FileValidationException.class)
                    .hasMessageContaining("FAILED");
        }

        @Test
        @DisplayName("Given S3 presigner fails, when generating download URL, then throws StorageException")
        void givenS3PresignerFails_whenGeneratingDownloadUrl_thenThrowsStorageException() {
            // Given
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = FileMetadata.builder()
                    .id(fileId)
                    .originalFilename("report.pdf")
                    .storedFilename("uuid_report.pdf")
                    .contentType("application/pdf")
                    .fileSize(2048L)
                    .s3Key("uploads/1/2026/03/uuid_report.pdf")
                    .s3Bucket("test-bucket")
                    .uploadStatus(UploadStatus.CLEAN)
                    .owner(testUser)
                    .build();

            when(userRepository.findByUsername("editor")).thenReturn(Optional.of(testUser));
            when(fileMetadataRepository.findByIdAndOwner(fileId, testUser))
                    .thenReturn(Optional.of(metadata));
            when(s3StorageService.generatePresignedDownloadUrl(anyString(), any(Duration.class)))
                    .thenThrow(new StorageException("Presigner failed"));

            // When / Then
            assertThatThrownBy(() -> fileService.generateDownloadUrl(fileId, userDetails))
                    .isInstanceOf(StorageException.class);
        }
    }

    // ========================
    // HELPER METHODS
    // ========================

    private MultipartFile mockMultipartFile(String filename, String contentType,
                                             long size, byte[] content) throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(filename);
        when(file.getContentType()).thenReturn(contentType);
        when(file.getSize()).thenReturn(size);
        when(file.isEmpty()).thenReturn(false);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));
        return file;
    }
}
