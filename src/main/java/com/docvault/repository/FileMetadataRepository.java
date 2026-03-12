package com.docvault.repository;

import com.docvault.entity.FileMetadata;
import com.docvault.entity.User;
import com.docvault.enums.UploadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {

    // All files owned by a specific user, newest first
    List<FileMetadata> findAllByOwnerOrderByCreatedAtDesc(User owner);

    // All files with a given upload status (e.g., find all PENDING files for scanning)
    List<FileMetadata> findAllByUploadStatus(UploadStatus uploadStatus);

    // All files owned by a user with a specific status
    List<FileMetadata> findAllByOwnerAndUploadStatus(User owner, UploadStatus uploadStatus);

    // Find a file by its S3 key (useful for S3 event callbacks)
    Optional<FileMetadata> findByS3Key(String s3Key);

    // Find a file by ID but only if owned by a specific user (security check)
    Optional<FileMetadata> findByIdAndOwner(UUID id, User owner);

    // Count files per user (useful for quota enforcement later)
    long countByOwner(User owner);
}
