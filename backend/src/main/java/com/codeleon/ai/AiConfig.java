package com.codeleon.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public OllamaClient ollamaClient(RestClient.Builder builder, AiProperties props) {
        return new OllamaClient(builder, props);
    }

    @Bean
    public QdrantClient qdrantClient(RestClient.Builder builder, AiProperties props) {
        return new QdrantClient(builder, props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "codeleon.ai", name = "enabled", havingValue = "true")
    public ApplicationRunner aiBootstrap(QdrantClient qdrant) {
        return args -> {
            try {
                qdrant.ensureCollection();
            } catch (Exception ex) {
                log.warn("Qdrant bootstrap failed (is the 'ai' compose profile up?): {}", ex.getMessage());
            }
        };
    }
}
