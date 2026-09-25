package com.noduq.adapter.outbound.persistence;

import com.noduq.domain.payments.PaymentHistoryImport;
import com.noduq.domain.payments.port.PaymentHistoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcPaymentHistoryRepository implements PaymentHistoryRepository {

	private final JdbcTemplate jdbc;

	public JdbcPaymentHistoryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<PaymentHistoryImport> find(UUID organizationId) {
		return jdbc.query(
				"""
						select organization_id, status, window_from, window_until,
						       total_messages, processed_messages, stored_messages,
						       page_token, started_at, finished_at
						from payment_history_imports
						where organization_id = ?
						""",
				rs -> rs.next() ? Optional.of(row(rs)) : Optional.empty(),
				organizationId);
	}

	@Override
	public void save(PaymentHistoryImport row) {
		jdbc.update(
				"""
						insert into payment_history_imports (
						  organization_id, status, window_from, window_until,
						  total_messages, processed_messages, stored_messages,
						  page_token, started_at, finished_at, updated_at
						) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
						on conflict (organization_id) do update set
						  status = excluded.status,
						  window_from = excluded.window_from,
						  window_until = excluded.window_until,
						  total_messages = excluded.total_messages,
						  processed_messages = excluded.processed_messages,
						  stored_messages = excluded.stored_messages,
						  page_token = excluded.page_token,
						  started_at = excluded.started_at,
						  finished_at = excluded.finished_at,
						  updated_at = now()
						""",
				row.organizationId(),
				row.status(),
				Timestamp.from(row.windowFrom()),
				Timestamp.from(row.windowUntil()),
				row.totalMessages(),
				row.processedMessages(),
				row.storedMessages(),
				row.pageToken(),
				row.startedAt() == null ? null : Timestamp.from(row.startedAt()),
				row.finishedAt() == null ? null : Timestamp.from(row.finishedAt()));
	}

	private static PaymentHistoryImport row(ResultSet rs) throws SQLException {
		return new PaymentHistoryImport(
				rs.getObject("organization_id", UUID.class),
				rs.getString("status"),
				instant(rs, "window_from"),
				instant(rs, "window_until"),
				rs.getInt("total_messages"),
				rs.getInt("processed_messages"),
				rs.getInt("stored_messages"),
				rs.getString("page_token"),
				instant(rs, "started_at"),
				instant(rs, "finished_at"));
	}

	private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
