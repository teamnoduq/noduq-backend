package com.noduq.adapter.outbound.persistence;

import com.noduq.domain.identity.OrganizationPlan;
import com.noduq.domain.identity.port.OrganizationPlanRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcOrganizationPlanRepository implements OrganizationPlanRepository {

	private final JdbcTemplate jdbc;

	public JdbcOrganizationPlanRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<OrganizationPlan> find(UUID organizationId) {
		return jdbc.query(
				"""
						select organization_id, entitlement, status, period_ends_at, store_product_id, last_event_id
						from subscriptions
						where organization_id = ?
						""",
				rs -> rs.next() ? Optional.of(plan(rs)) : Optional.empty(),
				organizationId);
	}

	@Override
	public void upsert(OrganizationPlan plan) {
		jdbc.update(
				"""
						insert into subscriptions (
						  organization_id, entitlement, status, period_ends_at, store_product_id, last_event_id, updated_at
						) values (?, ?, ?, ?, ?, ?, now())
						on conflict (organization_id) do update set
						  entitlement = excluded.entitlement,
						  status = excluded.status,
						  period_ends_at = excluded.period_ends_at,
						  store_product_id = excluded.store_product_id,
						  last_event_id = excluded.last_event_id,
						  updated_at = now()
						""",
				plan.organizationId(),
				plan.entitlement(),
				plan.status(),
				plan.periodEndsAt() == null ? null : Timestamp.from(plan.periodEndsAt()),
				plan.storeProductId(),
				plan.lastEventId());
	}

	private static OrganizationPlan plan(ResultSet rs) throws SQLException {
		OffsetDateTime ends = rs.getObject("period_ends_at", OffsetDateTime.class);
		return new OrganizationPlan(
				rs.getObject("organization_id", UUID.class),
				rs.getString("entitlement"),
				rs.getString("status"),
				ends == null ? null : ends.toInstant(),
				rs.getString("store_product_id"),
				rs.getString("last_event_id"));
	}
}
