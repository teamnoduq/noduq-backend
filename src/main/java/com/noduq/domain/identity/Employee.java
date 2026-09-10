package com.noduq.domain.identity;

import java.time.Instant;
import java.util.UUID;

public record Employee(
		UUID id,
		UUID organizationId,
		UUID branchId,
		String displayName,
		String username,
		String codeHash,
		String codeLookup,
		boolean active,
		Instant createdAt) {
}
