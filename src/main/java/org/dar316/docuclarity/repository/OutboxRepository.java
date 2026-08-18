package org.dar316.docuclarity.repository;

import org.dar316.docuclarity.model.OutboxEntry;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends CrudRepository<OutboxEntry, UUID> {

    /**
     * Pobiera wpisy PENDING posortowane po dacie utworzenia (FIFO).
     */
    @Query("SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY created_at ASC")
    List<OutboxEntry> findPending();
}
