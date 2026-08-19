package org.dar316.docuclarity.config;

import org.dar316.docuclarity.service.*;
import org.dar316.docuclarity.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * Konfiguracja kolejki (Etap 5): Redis Streams + worker.
 *
 * <p>Tworzy:</p>
 * <ul>
 *   <li>RedisTemplate (String serializer) do publikacji i ACK.</li>
 *   <li>TaskExecutor (bounded pool) — OCR/Tesseract jest CPU-bound, izolujemy
 *       go od wątków webowych i od wątku nasłuchu strumienia.</li>
 *   <li>DocumentProcessingService (worker logic) — bean, by Spring wstrzyknął
 *       zależności; sam serwis używa TransactionTemplate (brak self-invocation).</li>
 *   <li>StreamMessageListenerContainer + rejestracja StreamConsumer.</li>
 * </ul>
 *
 * <p>Cała sekcja jest wyłączana gdy {@code docuclarity.queue.enabled=false}
 * (domyślnie true) — przydatne np. do testów jednostkowych uploadu bez Redis.</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "docuclarity.queue.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class QueueConfig {

    @Bean
    public RedisTemplate<String, String> queueRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        return template;
    }

    @Bean(name = "processingTaskExecutor")
    public TaskExecutor processingTaskExecutor(
            @Value("${docuclarity.queue.worker-pool-size:4}") int poolSize) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(poolSize * 2);
        executor.setThreadNamePrefix("docu-worker-");
        executor.initialize();

        return executor;
    }

    @Bean
    public DocumentProcessingService documentProcessingService(
            MinioStorageService minioStorageService,
            PdfTextExtractionService pdfTextExtractionService,
            PageQualityEvaluator pageQualityEvaluator,
            Tess4jOcrService tess4jOcrService,
            DocumentRepository documentRepository,
            DocumentProgressService documentProgressService,
            @Qualifier("appObjectMapper") ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            @Value("${docuclarity.queue.max-processing-attempts:3}") int maxProcessingAttempts,
            AnalysisService analysisService
    ) {
        return new DocumentProcessingService(
                minioStorageService,
                pdfTextExtractionService,
                pageQualityEvaluator,
                tess4jOcrService,
                documentRepository,
                documentProgressService,
                objectMapper,
                transactionTemplate,
                maxProcessingAttempts,
                analysisService
        );
    }

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamListenerContainer(
            RedisConnectionFactory connectionFactory,
            StreamConsumer streamConsumer,
            @Value("${docuclarity.queue.poll-timeout-ms:2000}") long pollTimeoutMs) {
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofMillis(pollTimeoutMs))
                        .build();
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);
        streamConsumer.subscribe(container);
        container.start();

        return container;
    }
}
