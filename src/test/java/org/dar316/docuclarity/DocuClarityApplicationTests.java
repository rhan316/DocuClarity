package org.dar316.docuclarity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
@Import(TestcontainersConfiguration.class)
class DocuClarityApplicationTests {

    @Container
    static MinIOContainer minio = new MinIOContainer(
            DockerImageName.parse("minio/minio:latest"))
            .withUserName("minioadmin")
            .withPassword("minioadmin");

    @DynamicPropertySource
    static void configureMinio(DynamicPropertyRegistry registry) {
        registry.add("docuclarity.minio.endpoint", minio::getS3URL);
        registry.add("docuclarity.minio.access-key", minio::getUserName);
        registry.add("docuclarity.minio.secret-key", minio::getPassword);
        registry.add("docuclarity.minio.bucket", () -> "docuclarity-test");
    }

    @Test
    void contextLoads() {
    }
}
