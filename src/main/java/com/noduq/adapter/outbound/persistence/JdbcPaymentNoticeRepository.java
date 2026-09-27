package com.noduq.adapter.outbound.persistence;

import com.noduq.domain.payments.PaymentNotice;
import com.noduq.domain.payments.PaymentSource;
import com.noduq.domain.payments.PaymentTally;
import com.noduq.domain.payments.port.PaymentNoticeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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

	private static final String FILTER = """
			where organization_id = ?
			  and (?::timestamptz is null or coalesce(occurred_at, received_at) >= ?)
			  and (?::timestamptz is null or coalesce(occurred_at, received_at) < ?)
			  and (?::text is null or lower(coalesce(payer_name, '')) like ?)
			  and (?::text is null or source = ?)
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
			String source,
			Instant before,
			UUID beforeId) {
		String trimmedQuery = like(query);
		String sourceFilter = sourceFilter(source);
		Timestamp sinceAt = stamp(since);
		Timestamp untilAt = stamp(until);
		Timestamp beforeAt = stamp(before);
		return jdbc.query(
				"select " + COLUMNS + """
						from payment_notices
						""" + FILTER + """
						  and (
						    ?::timestamptz is null
						    or coalesce(occurred_at, received_at) < ?
						    or (coalesce(occurred_at, received_at) = ? and id < ?)
						  )
						order by coalesce(occurred_at, received_at) desc, id desc
						limit ?
						""",
				this::notice,
				organizationId,
				sinceAt,
				sinceAt,
				untilAt,
				untilAt,
				trimmedQuery,
				trimmedQuery,
				sourceFilter,
				sourceFilter,
				beforeAt,
				beforeAt,
				beforeAt,
				beforeId,
				limit);
	}

	@Override
	public PaymentTally tally(
			UUID organizationId,
			Instant since,
			Instant until,
			String query,
			String source) {
		Timestamp sinceAt = stamp(since);
		Timestamp untilAt = stamp(until);
		PaymentTally row = jdbc.queryForObject(
				"select count(*)::bigint, coalesce(sum(amount), 0) from payment_notices " + FILTER,
				(rs, ignored) -> new PaymentTally(rs.getLong(1), rs.getBigDecimal(2)),
				organizationId,
				sinceAt,
				sinceAt,
				untilAt,
				untilAt,
				like(query),
				like(query),
				sourceFilter(source),
				sourceFilter(source));
		if (row == null || row.totalAmount() == null) {
			return new PaymentTally(0, BigDecimal.ZERO);
		}
		return row;
	}

	@Override
	public Optional<Instant> earliest(UUID organizationId) {
		Timestamp stamp = jdbc.queryForObject(
				"""
						select min(coalesce(occurred_at, received_at))
						from payment_notices
						where organization_id = ?
						""",
				Timestamp.class,
				organizationId);
		return stamp == null ? Optional.empty() : Optional.of(stamp.toInstant());
	}

	private static Timestamp stamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	private static String like(String query) {
		return query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase() + "%";
	}

	private static String sourceFilter(String source) {
		return source == null || source.isBlank() ? null : source.trim().toLowerCase();
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
