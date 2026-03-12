package com.docvault.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class AwsConfig {

    @Value("${app.aws.region}")
    private String region;

    @Value("${app.aws.s3.access-key:#{null}}")
    private String accessKey;

    @Value("${app.aws.s3.secret-key:#{null}}")
    private String secretKey;

    /**
     * Builds the credentials provider.
     *
     * If access-key and secret-key are explicitly set (dev), uses static credentials.
     * If not set (prod/ECS/EC2), falls back to the default credential chain
     * which resolves from env vars → ~/.aws/credentials → IAM role.
     */
    private AwsCredentialsProvider credentialsProvider() {
        if (accessKey != null && secretKey != null
                && !accessKey.isBlank() && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }

    /**
     * S3Client — used for upload, download, delete, list operations.
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider())
                .build();
    }

    /**
     * S3Presigner — used for generating pre-signed URLs for direct browser
     * downloads without routing file bytes through the backend.
     */
    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider())
                .build();
    }
}
