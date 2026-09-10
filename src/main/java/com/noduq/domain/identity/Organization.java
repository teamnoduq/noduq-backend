package com.noduq.domain.identity;

import java.time.Instant;
import java.util.UUID;

public record Organization(UUID id, String name, String smsPhone, Instant createdAt) {
}
