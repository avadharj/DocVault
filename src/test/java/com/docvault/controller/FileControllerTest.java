package com.docvault.controller;

import com.docvault.config.SecurityConfig;
import com.docvault.dto.FileUploadResponse;
import com.docvault.exception.FileValidationException;
import com.docvault.exception.GlobalExceptionHandler;
import com.docvault.exception.StorageException;
import com.docvault.filter.JwtAuthenticationFilter;
import com.docvault.security.JwtUtils;
import com.docvault.security.UserDetailsImpl;
import com.docvault.security.UserDetailsServiceImpl;
import com.docvault.service.FileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileService fileService;

    private final UserDetailsImpl editorUser = new UserDetailsImpl(
            1L, "editor", "editor@example.com", "hashedpassword", true,
            List.of(new SimpleGrantedAuthority("ROLE_EDITOR"))
    );

    private final UserDetailsImpl adminUser = new UserDetailsImpl(
            2L, "admin", "admin@example.com", "hashedpassword", true,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
    );

    private final UserDetailsImpl viewerUser = new UserDetailsImpl(
            3L, "viewer", "viewer@example.com", "hashedpassword", true,
            List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))
    );

    @Nested
    @DisplayName("POST /api/files/upload")
    class UploadEndpoint {

        @Test
        @DisplayName("Given EDITOR with valid file, when uploading, then returns 201 with file metadata")
        void givenEditorWithValidFile_whenUploading_thenReturns201WithFileMetadata() throws Exception {
            // Given
            MockMultipartFile file = new MockMultipartFile(
                    "file", "report.pdf", "application/pdf", "PDF content".getBytes());

            FileUploadResponse response = FileUploadResponse.builder()
                    .id(UUID.randomUUID())
                    .originalFilename("report.pdf")
                    .contentType("application/pdf")
                    .fileSize(11L)
                    .uploadStatus("CLEAN")
                    .createdAt(LocalDateTime.now())
                    .build();

            when(fileService.uploadFile(any(), any())).thenReturn(response);

            // When / Then
            mockMvc.perform(multipart("/api/files/upload")
                            .file(file)
                            .with(user(editorUser)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.originalFilename").value("report.pdf"))
                    .andExpect(jsonPath("$.contentType").value("application/pdf"))
                    .andExpect(jsonPath("$.uploadStatus").value("CLEAN"))
                    .andExpect(jsonPath("$.id").exists());
        }

        @Test
        @DisplayName("Given ADMIN with valid file, when uploading, then returns 201")
        void givenAdminWithValidFile_whenUploading_thenReturns201() throws Exception {
            // Given
            MockMultipartFile file = new MockMultipartFile(
                    "file", "data.csv", "text/csv", "a,b,c".getBytes());

            FileUploadResponse response = FileUploadResponse.builder()
                    .id(UUID.randomUUID())
                    .originalFilename("data.csv")
                    .contentType("text/csv")
                    .fileSize(5L)
                    .uploadStatus("CLEAN")
                    .createdAt(LocalDateTime.now())
                    .build();

            when(fileService.uploadFile(any(), any())).thenReturn(response);

            // When / Then
            mockMvc.perform(multipart("/api/files/upload")
                            .file(file)
                            .with(user(adminUser)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.originalFilename").value("data.csv"));
        }

        @Test
        @DisplayName("Given VIEWER, when uploading, then returns 403 Forbidden")
        void givenViewer_whenUploading_thenReturns403() throws Exception {
            // Given
            MockMultipartFile file = new MockMultipartFile(
                    "file", "report.pdf", "application/pdf", "PDF content".getBytes());

            // When / Then
            mockMvc.perform(multipart("/api/files/upload")
                            .file(file)
                            .with(user(viewerUser)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Given no authentication, when uploading, then returns 401 Unauthorized")
        void givenNoAuthentication_whenUploading_thenReturns401() throws Exception {
            // Given
            MockMultipartFile file = new MockMultipartFile(
                    "file", "report.pdf", "application/pdf", "PDF content".getBytes());

            // When / Then
            mockMvc.perform(multipart("/api/files/upload")
                            .file(file))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Given file validation fails, when uploading, then returns 400 with error message")
        void givenFileValidationFails_whenUploading_thenReturns400() throws Exception {
            // Given
            MockMultipartFile file = new MockMultipartFile(
                    "file", "malware.exe", "application/x-executable", "evil".getBytes());

            when(fileService.uploadFile(any(), any()))
                    .thenThrow(new FileValidationException("File type 'application/x-executable' is not allowed"));

            // When / Then
            mockMvc.perform(multipart("/api/files/upload")
                            .file(file)
                            .with(user(editorUser)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("File type 'application/x-executable' is not allowed"));
        }

        @Test
        @DisplayName("Given S3 upload fails, when uploading, then returns 500 with storage error")
        void givenS3UploadFails_whenUploading_thenReturns500() throws Exception {
            // Given
            MockMultipartFile file = new MockMultipartFile(
                    "file", "report.pdf", "application/pdf", "PDF content".getBytes());

            when(fileService.uploadFile(any(), any()))
                    .thenThrow(new StorageException("Failed to upload file to S3"));

            // When / Then
            mockMvc.perform(multipart("/api/files/upload")
                            .file(file)
                            .with(user(editorUser)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("File storage operation failed"));
        }

        @Test
        @DisplayName("Given no file in request, when uploading, then returns 400")
        void givenNoFileInRequest_whenUploading_thenReturns400() throws Exception {
            // When / Then
            mockMvc.perform(multipart("/api/files/upload")
                            .with(user(editorUser)))
                    .andExpect(status().isBadRequest());
        }
    }
}
