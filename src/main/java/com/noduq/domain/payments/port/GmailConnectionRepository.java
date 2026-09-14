package com.noduq.domain.payments.port;

import com.noduq.domain.payments.GmailConnection;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GmailConnectionRepository {

	void upsert(GmailConnection connection);

	Optional<GmailConnection> findByOrganization(UUID organizationId);

	List<GmailConnection> all();

	void delete(UUID organizationId);

	void touched(UUID organizationId, String historyId, Instant polledAt);
}
