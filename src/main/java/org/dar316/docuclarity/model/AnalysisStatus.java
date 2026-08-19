package org.dar316.docuclarity.model;

/**
 * Status pipeline'u analizy LLM dokumentu.
 * Zgodny z CHECK constraint tabeli documents (V2__analysis.sql)
 */
public enum AnalysisStatus {
    NOT_ANALYZED,
    ANALYSIS_QUEUED,
    ANALYZING,
    ANALYZED,
    ANALYSIS_FAILED;

    public static AnalysisStatus fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("AnalysisStatus code cannot be null");
        }
        return AnalysisStatus.valueOf(code);
    }

    public String code() {
        return name();
    }
}
