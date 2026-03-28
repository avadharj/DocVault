package com.docvault.controller;

import com.docvault.dto.*;
import com.docvault.exception.FileValidationException;
import com.docvault.exception.ResourceNotFoundException;
import com.docvault.security.UserDetailsImpl;
import com.docvault.service.MultipartUploadService;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MultipartUploadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MultipartUploadService multipartUploadService;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final UserDetailsImpl editorUser = new UserDetailsImpl(
            1L, "editor", "editor@example.com", "hashedpassword", true,
            List.of(new SimpleGrantedAuthority("ROLE_EDITOR"))
    );

    private final UserDetailsImpl viewerUser = new UserDetailsImpl(
            3L, "viewer", "viewer@example.com", "hashedpassword", true,
            List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))
    );

    @Nested
    @DisplayName("POST /api/files/multipart/initiate")
    class InitiateEndpoint {

        @Test
        @DisplayName("Given EDITOR with valid request, when initiating, then returns 201 with pre-signed URLs")
        void givenEditorWithValidRequest_whenInitiating_thenReturns201() throws Exception {
            // Given
            MultipartUploadInitRequest request = MultipartUploadInitRequest.builder()
                    .filename("bigfile.pdf")
                    .contentType("application/pdf")
                    .fileSize(50L * 1024 * 1024)
                    .build();

            MultipartUploadInitResponse response = MultipartUploadInitResponse.builder()
                    .fileId(UUID.randomUUID())
                    .uploadId("upload-id-123")
                    .s3Key("uploads/1/2026/03/uuid_bigfile.pdf")
                    .totalParts(10)
                    .partSize(5L * 1024 * 1024)
                    .parts(List.of(
                            MultipartUploadInitResponse.PresignedPartUrl.builder()
                                    .partNumber(1).uploadUrl("https://s3.amazonaws.com/part1").build()))
                    .build();

            when(multipartUploadService.initiateUpload(any(), any())).thenReturn(response);

            // When / Then
            mockMvc.perform(post("/api/files/multipart/initiate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request))
                            .with(user(editorUser)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.uploadId").value("upload-id-123"))
                    .andExpect(jsonPath("$.totalParts").value(10))
                    .andExpect(jsonPath("$.parts[0].partNumber").value(1))
                    .andExpect(jsonPath("$.parts[0].uploadUrl").exists());
        }

        @Test
        @DisplayName("Given VIEWER, when initiating, then returns 403")
        void givenViewer_whenInitiating_thenReturns403() throws Exception {
            MultipartUploadInitRequest request = MultipartUploadInitRequest.builder()
                    .filename("file.pdf")
                    .contentType("application/pdf")
                    .fileSize(10L * 1024 * 1024)
                    .build();

            mockMvc.perform(post("/api/files/multipart/initiate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request))
                            .with(user(viewerUser)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Given no auth, when initiating, then returns 401")
        void givenNoAuth_whenInitiating_thenReturns401() throws Exception {
            MultipartUploadInitRequest request = MultipartUploadInitRequest.builder()
                    .filename("file.pdf")
                    .contentType("application/pdf")
                    .fileSize(10L * 1024 * 1024)
                    .build();

            mockMvc.perform(post("/api/files/multipart/initiate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Given missing filename, when initiating, then returns 400")
        void givenMissingFilename_whenInitiating_thenReturns400() throws Exception {
            MultipartUploadInitRequest request = MultipartUploadInitRequest.builder()
                    .contentType("application/pdf")
                    .fileSize(10L * 1024 * 1024)
                    .build();

            mockMvc.perform(post("/api/files/multipart/initiate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request))
                            .with(user(editorUser)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Given file below threshold, when initiating, then returns 400")
        void givenFileBelowThreshold_whenInitiating_thenReturns400() throws Exception {
            MultipartUploadInitRequest request = MultipartUploadInitRequest.builder()
                    .filename("small.pdf")
                    .contentType("application/pdf")
                    .fileSize(1024L)
                    .build();

            when(multipartUploadService.initiateUpload(any(), any()))
                    .thenThrow(new FileValidationException("File size is below the multipart threshold"));

            mockMvc.perform(post("/api/files/multipart/initiate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request))
                            .with(user(editorUser)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/files/multipart/complete")
    class CompleteEndpoint {

        @Test
        @DisplayName("Given valid completion, when completing, then returns 200 with file metadata")
        void givenValidCompletion_whenCompleting_thenReturns200() throws Exception {
            // Given
            UUID fileId = UUID.randomUUID();
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

            FileUploadResponse response = FileUploadResponse.builder()
                    .id(fileId)
                    .originalFilename("bigfile.pdf")
                    .contentType("application/pdf")
                    .fileSize(50L * 1024 * 1024)
                    .uploadStatus("CLEAN")
                    .createdAt(LocalDateTime.now())
                    .build();

            when(multipartUploadService.completeUpload(any(), any())).thenReturn(response);

            // When / Then
            mockMvc.perform(post("/api/files/multipart/complete")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request))
                            .with(user(editorUser)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.uploadStatus").value("CLEAN"))
                    .andExpect(jsonPath("$.originalFilename").value("bigfile.pdf"));
        }

        @Test
        @DisplayName("Given file not found, when completing, then returns 404")
        void givenFileNotFound_whenCompleting_thenReturns404() throws Exception {
            UUID fileId = UUID.randomUUID();
            MultipartUploadCompleteRequest request = MultipartUploadCompleteRequest.builder()
                    .fileId(fileId)
                    .uploadId("upload-id-123")
                    .parts(List.of(MultipartUploadCompleteRequest.CompletedPart.builder()
                            .partNumber(1).etag("etag1").build()))
                    .build();

            when(multipartUploadService.completeUpload(any(), any()))
                    .thenThrow(new ResourceNotFoundException("File not found"));

            mockMvc.perform(post("/api/files/multipart/complete")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request))
                            .with(user(editorUser)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/files/multipart/abort")
    class AbortEndpoint {

        @Test
        @DisplayName("Given valid abort request, when aborting, then returns 200")
        void givenValidAbortRequest_whenAborting_thenReturns200() throws Exception {
            UUID fileId = UUID.randomUUID();
            MultipartUploadAbortRequest request = MultipartUploadAbortRequest.builder()
                    .fileId(fileId)
                    .uploadId("upload-id-123")
                    .build();

            doNothing().when(multipartUploadService).abortUpload(any(), any());

            mockMvc.perform(post("/api/files/multipart/abort")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request))
                            .with(user(editorUser)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Upload aborted successfully"));
        }

        @Test
        @DisplayName("Given VIEWER, when aborting, then returns 403")
        void givenViewer_whenAborting_thenReturns403() throws Exception {
            UUID fileId = UUID.randomUUID();
            MultipartUploadAbortRequest request = MultipartUploadAbortRequest.builder()
                    .fileId(fileId)
                    .uploadId("upload-id-123")
                    .build();

            mockMvc.perform(post("/api/files/multipart/abort")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request))
                            .with(user(viewerUser)))
                    .andExpect(status().isForbidden());
        }
    }
}
