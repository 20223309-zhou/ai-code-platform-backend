package com.ai.codeplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("pexels")
@Data
public class PexelsConfig {
    // API Key
    private String apiKey;
}
