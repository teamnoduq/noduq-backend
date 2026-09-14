package com.noduq.domain.identity;

import java.time.Instant;
import java.util.UUID;

public record OrganizationPlan(
		UUID organizationId,
		String entitlement,
		String status,
		Instant periodEndsAt,
		String storeProductId,
		String lastEventId) {

	public static final String SMS = "noduq_sms";
	public static final String EMAIL = "noduq_email";

	public boolean active(Instant now) {
		if (entitlement == null || entitlement.isBlank()) {
			return false;
		}
		if ("expired".equals(status)) {
			return false;
		}
		return periodEndsAt == null || periodEndsAt.isAfter(now);
	}

	public boolean allowsSms(Instant now) {
		return active(now) && SMS.equals(entitlement);
	}

	public boolean allowsEmail(Instant now) {
		return active(now) && (SMS.equals(entitlement) || EMAIL.equals(entitlement));
	}
}
