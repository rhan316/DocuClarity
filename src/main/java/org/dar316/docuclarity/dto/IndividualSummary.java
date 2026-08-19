package org.dar316.docuclarity.dto;

/**
 * Strukturyzowane 1-stronnicowe podsumowanie dokumentu.
 * Każde pole jest nullable - LLM wypełnia tylko istotne.
 *
 * @param who Kto jest stroną umowy
 * @param what Czego dotyczy dokument
 * @param amount Za ile/kwota
 * @param duration Na jak długo
 * @param liability Kto za co odpowiada
 * @param keyDates Kluczowe daty
 * @param other Inne istotne informacje TODO: Możemy rozszerzyć other do klasy
 */
public record IndividualSummary(
        String who,
        String what,
        String amount,
        String duration,
        String liability,
        String keyDates,
        String other
) {

}
