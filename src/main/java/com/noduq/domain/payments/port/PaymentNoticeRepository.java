package com.noduq.domain.payments.port;

import com.noduq.domain.payments.PaymentNotice;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentNoticeRepository {

	/**
	 * Stores the notice unless the same fingerprint already landed for this organization.
	 *
	 * @return the stored notice, or empty when it was a repeat
	 */
	Optional<PaymentNotice> insertIfNew(PaymentNotice notice);

	List<PaymentNotice> latest(UUID organizationId, int limit);

	List<PaymentNotice> search(
			UUID organizationId,
			int limit,
			java.time.Instant since,
			java.time.Instant until,
			String query,
			String source);

	Optional<PaymentNotice> find(UUID organizationId, UUID noticeId);

	Optional<PaymentNotice> findByFingerprint(UUID organizationId, String fingerprint);

	/**
	 * Stamps {@code email_confirmed_at} the first time the same payment arrives by mail.
	 * Later copies are a no-op and still return the row.
	 */
	Optional<PaymentNotice> markEmailConfirmed(UUID organizationId, String fingerprint, java.time.Instant at);
}
