package com.noduq.adapter.outbound.persistence;

import com.noduq.domain.payments.Device;
import com.noduq.domain.payments.port.DeviceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcDeviceRepository implements DeviceRepository {

	private static final String COLUMNS =
			"id, organization_id, profile_id, employee_id, push_token, platform, sms_reader, last_seen_at";

	private final JdbcTemplate jdbc;

	public JdbcDeviceRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Device register(Device device) {
		return jdbc.queryForObject(
				"""
						insert into devices (
						  id, organization_id, profile_id, employee_id, push_token, platform, sms_reader, last_seen_at
						) values (?, ?, ?, ?, ?, ?, ?, now())
						on conflict (push_token) do update set
						  organization_id = excluded.organization_id,
						  profile_id = excluded.profile_id,
						  employee_id = excluded.employee_id,
						  platform = excluded.platform,
						  sms_reader = excluded.sms_reader,
						  last_seen_at = now()
						returning
						"""
						+ " " + COLUMNS,
				this::device,
				device.id(),
				device.organizationId(),
				device.profileId(),
				device.employeeId(),
				device.pushToken(),
				device.platform(),
				device.smsReader());
	}

	@Override
	public List<Device> listByOrganization(UUID organizationId) {
		return jdbc.query(
				"select " + COLUMNS + " from devices where organization_id = ? order by last_seen_at desc",
				this::device,
				organizationId);
	}

	@Override
	public void deleteByPushToken(UUID organizationId, String pushToken) {
		jdbc.update("delete from devices where organization_id = ? and push_token = ?", organizationId, pushToken);
	}

	@Override
	public void deleteByPushTokens(Collection<String> pushTokens) {
		if (pushTokens.isEmpty()) {
			return;
		}
		jdbc.batchUpdate(
				"delete from devices where push_token = ?",
				pushTokens.stream().map(token -> new Object[] { token }).toList());
	}

	private Device device(ResultSet rs, int ignored) throws SQLException {
		return new Device(
				rs.getObject("id", UUID.class),
				rs.getObject("organization_id", UUID.class),
				rs.getObject("profile_id", UUID.class),
				rs.getObject("employee_id", UUID.class),
				rs.getString("push_token"),
				rs.getString("platform"),
				rs.getBoolean("sms_reader"),
				rs.getObject("last_seen_at", OffsetDateTime.class).toInstant());
	}
}
