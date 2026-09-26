package com.noduq.adapter.outbound.persistence;

import com.noduq.domain.payments.PaymentHistoryImport;
import com.noduq.domain.payments.port.PaymentHistoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
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

	@Override
	public void rememberMessages(UUID organizationId, List<String> gmailIds) {
		if (gmailIds.isEmpty()) {
			return;
		}
		jdbc.batchUpdate(
				"""
						insert into payment_history_messages (organization_id, gmail_id)
						values (?, ?)
						on conflict (organization_id, gmail_id) do nothing
						""",
				new BatchPreparedStatementSetter() {
					@Override
					public void setValues(PreparedStatement statement, int index) throws SQLException {
						statement.setObject(1, organizationId);
						statement.setString(2, gmailIds.get(index));
					}

					@Override
					public int getBatchSize() {
						return gmailIds.size();
					}
				});
	}

	@Override
	public int countMessages(UUID organizationId) {
		Integer count = jdbc.queryForObject(
				"select count(*) from payment_history_messages where organization_id = ?",
				Integer.class,
				organizationId);
		return count == null ? 0 : count;
	}

	@Override
	public List<String> nextMessages(UUID organizationId, int limit) {
		return jdbc.query(
				"""
						select gmail_id from payment_history_messages
						where organization_id = ?
						order by gmail_id
						limit ?
						""",
				(rs, rowNum) -> rs.getString("gmail_id"),
				organizationId,
				limit);
	}

	@Override
	public void forgetMessages(UUID organizationId, List<String> gmailIds) {
		for (String gmailId : gmailIds) {
			jdbc.update(
					"delete from payment_history_messages where organization_id = ? and gmail_id = ?",
					organizationId,
					gmailId);
		}
	}

	@Override
	public void clearMessages(UUID organizationId) {
		jdbc.update("delete from payment_history_messages where organization_id = ?", organizationId);
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
