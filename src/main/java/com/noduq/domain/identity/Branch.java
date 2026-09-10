package com.noduq.domain.identity;

import java.time.Instant;
import java.util.UUID;

public record Branch(UUID id, UUID organizationId, String name, Instant createdAt) {
}
