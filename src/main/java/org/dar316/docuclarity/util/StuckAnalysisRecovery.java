package org.dar316.docuclarity.util;

import org.dar316.docuclarity.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StuckAnalysisRecovery {
    private static final Logger logger = LoggerFactory.getLogger(StuckAnalysisRecovery.class);

    private final DocumentRepository documentRepository;

    public StuckAnalysisRecovery(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resetStuckAnalysis() {
        int reset = documentRepository.resetStuckAnalysis();

        if (reset > 0) {
            logger.info("Reset {} stuck ANALYZING documents to ANALYSIS_QUEUE", reset);

            // re-publish dla zresetowanych dokumentów
            // TODO: Opcjonalnie - można tu dodać batch re-publish do Redis Stream
            // Na razie dokumenty czekają w ANALYSIS_QUEUED; można je retrigggerować
            // manualnie przez POST /api/documents/{id}/analyze lub przez scheduler
        }
    }
}
