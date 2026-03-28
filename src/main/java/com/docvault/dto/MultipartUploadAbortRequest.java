package com.docvault.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultipartUploadAbortRequest {

    @NotNull(message = "File ID is required")
    private UUID fileId;

    @NotBlank(message = "Upload ID is required")
    private String uploadId;
}
