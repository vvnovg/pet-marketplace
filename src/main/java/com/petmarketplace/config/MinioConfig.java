package com.petmarketplace.config;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    @ConditionalOnProperty(name = "storage.provider", havingValue = "minio")
    @ConfigurationProperties(prefix = "storage.minio")
    public MinioProperties minioProperties() {
        return new MinioProperties(null, null, null);
    }

    @Bean
    @ConditionalOnProperty(name = "storage.provider", havingValue = "minio")
    public MinioClient minioClient(MinioProperties props) {
        return MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .build();
    }

    public record MinioProperties(String endpoint, String accessKey, String secretKey) {}
}
