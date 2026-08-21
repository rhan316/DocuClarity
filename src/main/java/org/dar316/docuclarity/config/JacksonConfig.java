package org.dar316.docuclarity.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 2 configuration for internal domain serialization
 * (Outbox payload, MinIO JSON).
 *
 * Qualified as "appObjectMapper" to avoid collision with Spring Boot 4's
 *
 * autoconfigured HTTP message converters (Jackson 3)
 */
@Configuration
public class JacksonConfig {

    @Bean(name = "appObjectMapper")
    public ObjectMapper appObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
