package com.docvault.entity;

import com.docvault.enums.UploadStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "file_metadata", indexes = {
        @Index(name = "idx_file_owner", columnList = "owner_id"),
        @Index(name = "idx_file_status", columnList = "upload_status"),
        @Index(name = "idx_file_s3_key", columnList = "s3_key", unique = true)
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "owner")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class FileMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    // What the user originally named the file (e.g., "report.pdf")
    @Column(nullable = false)
    private String originalFilename;

    // UUID-based name in S3 to avoid collisions (e.g., "a3f1b2c4-report.pdf")
    @Column(nullable = false)
    private String storedFilename;

    // MIME type (e.g., "application/pdf", "image/png")
    @Column(nullable = false)
    private String contentType;

    // File size in bytes
    @Column(nullable = false)
    private Long fileSize;

    // Full S3 object key (e.g., "uploads/2026/03/a3f1b2c4-report.pdf")
    @Column(nullable = false, unique = true)
    private String s3Key;

    // Which S3 bucket the file lives in
    @Column(nullable = false)
    private String s3Bucket;

    // Tracks the file through upload → scan → clean/infected/failed
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UploadStatus uploadStatus = UploadStatus.PENDING;

    // LAZY — we rarely need the full User object when loading file metadata.
    // If we do, Spring will fetch it on access within a transaction.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
