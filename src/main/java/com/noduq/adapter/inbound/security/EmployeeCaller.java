package com.noduq.adapter.inbound.security;

import java.util.UUID;

public record EmployeeCaller(UUID sessionId, UUID employeeId, UUID organizationId) {
}
