package com.noduq.domain.payments;

import java.time.Instant;
import java.util.UUID;

/**
 * A phone that gets the push. Belongs either to the owner ({@code profileId}) or to an
 * employee ({@code employeeId}), never to both.
 */
public record Device(
		UUID id,
		UUID organizationId,
		UUID profileId,
		UUID employeeId,
		String pushToken,
		String platform,
		boolean smsReader,
		Instant lastSeenAt) {

	public boolean belongsToOwner() {
		return profileId != null;
	}
}
