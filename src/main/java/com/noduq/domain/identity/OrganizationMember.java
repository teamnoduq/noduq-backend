package com.noduq.domain.identity;

import java.time.Instant;
import java.util.UUID;

public record OrganizationMember(
		UUID id,
		UUID organizationId,
		UUID profileId,
		MemberRole role,
		Instant createdAt) {
}
