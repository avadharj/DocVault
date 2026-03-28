package com.docvault.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultipartUploadCompleteRequest {

    @NotNull(message = "File ID is required")
    private UUID fileId;

    @NotBlank(message = "Upload ID is required")
    private String uploadId;

    @NotEmpty(message = "At least one completed part is required")
    @Valid
    private List<CompletedPart> parts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompletedPart {

        @Min(value = 1, message = "Part number must be at least 1")
        private int partNumber;

        @NotBlank(message = "ETag is required")
        private String etag;
    }
}
