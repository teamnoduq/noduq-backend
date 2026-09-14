package com.noduq.adapter.outbound.persistence;

import com.noduq.domain.payments.GmailConnection;
import com.noduq.domain.payments.port.GmailConnectionRepository;
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
public class JdbcGmailConnectionRepository implements GmailConnectionRepository {

	private static final String COLUMNS = """
			organization_id, profile_id, gmail_address, refresh_token, history_id, last_polled_at
			""";

	private final JdbcTemplate jdbc;

	public JdbcGmailConnectionRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void upsert(GmailConnection connection) {
		jdbc.update(
				"""
						insert into gmail_connections (
						  organization_id, profile_id, gmail_address, refresh_token, history_id, last_polled_at
						) values (?, ?, ?, ?, ?, ?)
						on conflict (organization_id) do update set
						  profile_id = excluded.profile_id,
						  gmail_address = excluded.gmail_address,
						  refresh_token = excluded.refresh_token,
						  history_id = excluded.history_id,
						  last_polled_at = excluded.last_polled_at
						""",
				connection.organizationId(),
				connection.profileId(),
				connection.gmailAddress(),
				connection.refreshToken(),
				connection.historyId(),
				connection.lastPolledAt() == null ? null : Timestamp.from(connection.lastPolledAt()));
	}

	@Override
	public Optional<GmailConnection> findByOrganization(UUID organizationId) {
		return jdbc.query(
				"select " + COLUMNS + " from gmail_connections where organization_id = ?",
				rs -> rs.next() ? Optional.of(row(rs)) : Optional.empty(),
				organizationId);
	}

	@Override
	public List<GmailConnection> all() {
		return jdbc.query("select " + COLUMNS + " from gmail_connections", (rs, ignored) -> row(rs));
	}

	@Override
	public void delete(UUID organizationId) {
		jdbc.update("delete from gmail_connections where organization_id = ?", organizationId);
	}

	@Override
	public void touched(UUID organizationId, String historyId, Instant polledAt) {
		jdbc.update(
				"""
						update gmail_connections
						set history_id = coalesce(?, history_id), last_polled_at = ?
						where organization_id = ?
						""",
				historyId,
				Timestamp.from(polledAt),
				organizationId);
	}

	private static GmailConnection row(ResultSet rs) throws SQLException {
		OffsetDateTime polled = rs.getObject("last_polled_at", OffsetDateTime.class);
		return new GmailConnection(
				rs.getObject("organization_id", UUID.class),
				rs.getObject("profile_id", UUID.class),
				rs.getString("gmail_address"),
				rs.getString("refresh_token"),
				rs.getString("history_id"),
				polled == null ? null : polled.toInstant());
	}
}
