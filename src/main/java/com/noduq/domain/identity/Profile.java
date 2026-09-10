package com.noduq.domain.identity;

import java.time.Instant;
import java.util.UUID;

public record Profile(UUID id, String displayName, Instant createdAt) {
}
