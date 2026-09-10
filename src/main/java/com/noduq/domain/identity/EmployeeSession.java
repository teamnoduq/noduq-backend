package com.noduq.domain.identity;

import java.time.Instant;
import java.util.UUID;

public record EmployeeSession(UUID id, UUID employeeId, Instant expiresAt, Instant revokedAt) {

	public boolean usable(Instant now) {
		return revokedAt == null && expiresAt.isAfter(now);
	}
}
