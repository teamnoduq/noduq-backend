package com.noduq.domain.payments;

import java.time.Instant;
import java.util.UUID;

/** The shop's Gmail, which NODUQ reads for the same Bancolombia receipt the SMS already carries. */
public record GmailConnection(
		UUID organizationId,
		UUID profileId,
		String gmailAddress,
		String refreshToken,
		String historyId,
		Instant lastPolledAt) {
}
