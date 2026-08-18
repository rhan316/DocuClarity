package org.dar316.docuclarity.repository;

import org.dar316.docuclarity.model.Document;
import org.dar316.docuclarity.model.OutboxEntry;
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Automatycznie ustawia created_at / updated_at przed zapisem encji do DB.
 *
 * Spring Data JDBC nie korzysta z DEFAULT z bazy — wstawia wszystkie kolumny.
 * Ten callback ustawia timestampy przed konwersją do SQL INSERT/UPDATE.
 */
@Component
public class EntityTimestampCallback implements BeforeConvertCallback<Object> {

    @Override
    public Object onBeforeConvert(Object entity) {
        Instant now = Instant.now();
        if (entity instanceof Document doc) {
            if (doc.getCreatedAt() == null) {
                doc.setCreatedAt(now);
            }
            doc.setUpdatedAt(now);
        } else if (entity instanceof OutboxEntry entry) {
            if (entry.getCreatedAt() == null) {
                // Używamy getCreatedAt — dla OutboxEntry settery dla created_at nie istnieje,
                // więc radzimy sobie przez refleksję brakującym setterem dodanym w encji
                entry.setCreatedAt(now);
            }
        }
        return entity;
    }
}
