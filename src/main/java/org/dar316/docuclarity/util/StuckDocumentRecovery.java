package org.dar316.docuclarity.util;

import org.dar316.docuclarity.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class StuckDocumentRecovery {
    private static final Logger log = LoggerFactory.getLogger(StuckDocumentRecovery.class);
    private final DocumentRepository documentRepository;
    private final TransactionTemplate transactionTemplate;

    public StuckDocumentRecovery(DocumentRepository documentRepository, TransactionTemplate transactionTemplate) {
        this.documentRepository = documentRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resetStuckProcessing() {
        /*
        On startup, no processing is in progress - any PROCESSING
        document is stuck from a previous crash.
         */
        int reset = documentRepository.resetStuckProcessing();

        if (reset > 0){
            log.info("Reset {} stuck PROCESSING documents to UPLOADED", reset);
        }
    }
}
