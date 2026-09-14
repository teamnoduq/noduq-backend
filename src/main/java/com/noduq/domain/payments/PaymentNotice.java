package com.noduq.domain.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment the bank told us about. Only the payer, the amount and the moment are kept;
 * the raw message never reaches the database.
 */
public record PaymentNotice(
		UUID id,
		UUID organizationId,
		PaymentSource source,
		String payerName,
		BigDecimal amount,
		String currency,
		Instant occurredAt,
		Instant receivedAt,
		String fingerprint,
		Instant emailConfirmedAt,
		String unparsedExcerpt) {

	public static final String DEFAULT_CURRENCY = "COP";

	/** True when we managed to read something useful out of the message. */
	public boolean readable() {
		return payerName != null || amount != null;
	}

	public boolean confirmedByEmail() {
		return emailConfirmedAt != null;
	}

	/** The moment to show on screen: the bank's own timestamp when it gave us one. */
	public Instant happenedAt() {
		return occurredAt == null ? receivedAt : occurredAt;
	}
}
