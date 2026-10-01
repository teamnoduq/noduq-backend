package com.noduq.domain.payments.port;

import com.noduq.domain.payments.PaymentBucket;
import com.noduq.domain.payments.PaymentNotice;
import com.noduq.domain.payments.PaymentTally;

import java.time.Instant;
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

	/**
	 * Newest first. {@code before} and {@code beforeId} are the last row already shown, so the
	 * next page continues without loading the whole filter.
	 */
	List<PaymentNotice> search(
			UUID organizationId,
			int limit,
			Instant since,
			Instant until,
			String query,
			String source,
			Instant before,
			UUID beforeId,
			int offset);

	PaymentTally tally(
			UUID organizationId,
			Instant since,
			Instant until,
			String query,
			String source);

	Optional<PaymentNotice> find(UUID organizationId, UUID noticeId);

	Optional<PaymentNotice> findByFingerprint(UUID organizationId, String fingerprint);

	/**
	 * Stamps {@code email_confirmed_at} the first time the same payment arrives by mail.
	 * Later copies are a no-op and still return the row.
	 */
	Optional<PaymentNotice> markEmailConfirmed(UUID organizationId, String fingerprint, java.time.Instant at);

	/** Oldest moment on a stored notice, the first payment this shop actually kept. */
	Optional<Instant> earliest(UUID organizationId);

	/**
	 * Payments with an amount, grouped by Bogota day or by Bogota month.
	 * {@code start} on a month bucket is the first day of that month.
	 */
	List<PaymentBucket> buckets(UUID organizationId, Instant since, Instant until, boolean byMonth);

	/** Distinct non-blank payer names in the window. The same person on two days counts once. */
	long distinctPayers(UUID organizationId, Instant since, Instant until);
}
