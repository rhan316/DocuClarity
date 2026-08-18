package org.dar316.docuclarity;

import org.springframework.boot.SpringApplication;

public class TestDocuClarityApplication {

    public static void main(String[] args) {
        SpringApplication.from(DocuClarityApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
