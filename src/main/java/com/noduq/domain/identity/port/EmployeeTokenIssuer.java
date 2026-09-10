package com.noduq.domain.identity.port;

import java.time.Instant;
import java.util.UUID;

public interface EmployeeTokenIssuer {

	String issue(UUID sessionId, UUID employeeId, UUID organizationId, Instant expiresAt);

	EmployeeTokenPayload parse(String token);

	record EmployeeTokenPayload(UUID sessionId, UUID employeeId, UUID organizationId, Instant expiresAt) {
	}
}
