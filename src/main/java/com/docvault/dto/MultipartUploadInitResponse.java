package com.docvault.dto;

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
public class MultipartUploadInitResponse {

    private UUID fileId;
    private String uploadId;
    private String s3Key;
    private int totalParts;
    private long partSize;
    private List<PresignedPartUrl> parts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PresignedPartUrl {
        private int partNumber;
        private String uploadUrl;
    }
}
