package com.noduq.adapter.outbound.persistence;

import com.noduq.domain.payments.PaymentNotice;
import com.noduq.domain.payments.PaymentSource;
import com.noduq.domain.payments.port.PaymentNoticeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcPaymentNoticeRepository implements PaymentNoticeRepository {

	private static final String COLUMNS = """
			id, organization_id, source, payer_name, amount, currency,
			occurred_at, received_at, fingerprint, email_confirmed_at, unparsed_excerpt
			""";

	private final JdbcTemplate jdbc;

	public JdbcPaymentNoticeRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<PaymentNotice> insertIfNew(PaymentNotice notice) {
		int inserted = jdbc.update(
				"""
						insert into payment_notices (
						  id, organization_id, source, payer_name, amount, currency,
						  occurred_at, received_at, fingerprint, unparsed_excerpt
						) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
						on conflict (organization_id, fingerprint) do nothing
						""",
				notice.id(),
				notice.organizationId(),
				notice.source().dbValue(),
				notice.payerName(),
				notice.amount(),
				notice.currency(),
				notice.occurredAt() == null ? null : Timestamp.from(notice.occurredAt()),
				Timestamp.from(notice.receivedAt()),
				notice.fingerprint(),
				notice.unparsedExcerpt());
		return inserted == 0 ? Optional.empty() : Optional.of(notice);
	}

	@Override
	public List<PaymentNotice> latest(UUID organizationId, int limit) {
		return jdbc.query(
				"select " + COLUMNS + """
						from payment_notices
						where organization_id = ?
						order by received_at desc
						limit ?
						""",
				this::notice,
				organizationId,
				limit);
	}

	@Override
	public List<PaymentNotice> search(
			UUID organizationId,
			int limit,
			Instant since,
			Instant until,
			String query,
			String source) {
		String trimmedQuery = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase() + "%";
		String sourceFilter = source == null || source.isBlank() ? null : source.trim().toLowerCase();
		return jdbc.query(
				"select " + COLUMNS + """
						from payment_notices
						where organization_id = ?
						  and (?::timestamptz is null or coalesce(occurred_at, received_at) >= ?)
						  and (?::timestamptz is null or coalesce(occurred_at, received_at) < ?)
						  and (?::text is null or lower(coalesce(payer_name, '')) like ?)
						  and (?::text is null or source = ?)
						order by received_at desc
						limit ?
						""",
				this::notice,
				organizationId,
				since == null ? null : Timestamp.from(since),
				since == null ? null : Timestamp.from(since),
				until == null ? null : Timestamp.from(until),
				until == null ? null : Timestamp.from(until),
				trimmedQuery,
				trimmedQuery,
				sourceFilter,
				sourceFilter,
				limit);
	}

	@Override
	public Optional<PaymentNotice> find(UUID organizationId, UUID noticeId) {
		return jdbc.query(
				"select " + COLUMNS + """
						from payment_notices
						where organization_id = ? and id = ?
						""",
				rs -> rs.next() ? Optional.of(notice(rs, 0)) : Optional.empty(),
				organizationId,
				noticeId);
	}

	@Override
	public Optional<PaymentNotice> findByFingerprint(UUID organizationId, String fingerprint) {
		return jdbc.query(
				"select " + COLUMNS + """
						from payment_notices
						where organization_id = ? and fingerprint = ?
						""",
				rs -> rs.next() ? Optional.of(notice(rs, 0)) : Optional.empty(),
				organizationId,
				fingerprint);
	}

	@Override
	public Optional<PaymentNotice> markEmailConfirmed(UUID organizationId, String fingerprint, Instant at) {
		return jdbc.query(
				"""
						update payment_notices
						set email_confirmed_at = coalesce(email_confirmed_at, ?)
						where organization_id = ? and fingerprint = ?
						returning
						""" + COLUMNS,
				rs -> rs.next() ? Optional.of(notice(rs, 0)) : Optional.empty(),
				Timestamp.from(at),
				organizationId,
				fingerprint);
	}

	private PaymentNotice notice(ResultSet rs, int ignored) throws SQLException {
		return new PaymentNotice(
				rs.getObject("id", UUID.class),
				rs.getObject("organization_id", UUID.class),
				PaymentSource.fromDb(rs.getString("source")),
				rs.getString("payer_name"),
				rs.getBigDecimal("amount"),
				rs.getString("currency"),
				instant(rs, "occurred_at"),
				instant(rs, "received_at"),
				rs.getString("fingerprint"),
				instant(rs, "email_confirmed_at"),
				rs.getString("unparsed_excerpt"));
	}

	private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
