package com.noduq.adapter.outbound.persistence;

import com.noduq.domain.identity.Employee;
import com.noduq.domain.identity.EmployeeSession;
import com.noduq.domain.identity.port.EmployeeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcEmployeeRepository implements EmployeeRepository {

	private final JdbcTemplate jdbc;

	public JdbcEmployeeRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<Employee> listByOrganization(UUID organizationId) {
		return jdbc.query(
				"""
						select id, organization_id, branch_id, display_name, username, code_hash, code_lookup, active, lookback_days, created_at
						from employees
						where organization_id = ?
						order by created_at
						""",
				this::employee,
				organizationId);
	}

	@Override
	public Set<String> usernamesInOrganization(UUID organizationId) {
		return new HashSet<>(jdbc.query(
				"select username from employees where organization_id = ?",
				(rs, ignored) -> rs.getString("username"),
				organizationId));
	}

	@Override
	public Optional<Employee> findById(UUID employeeId) {
		return jdbc.query(
				"""
						select id, organization_id, branch_id, display_name, username, code_hash, code_lookup, active, lookback_days, created_at
						from employees
						where id = ?
						""",
				rs -> rs.next() ? Optional.of(employee(rs, 0)) : Optional.empty(),
				employeeId);
	}

	@Override
	public Optional<Employee> findByCodeLookup(String codeLookup) {
		return jdbc.query(
				"""
						select id, organization_id, branch_id, display_name, username, code_hash, code_lookup, active, lookback_days, created_at
						from employees
						where code_lookup = ?
						""",
				rs -> rs.next() ? Optional.of(employee(rs, 0)) : Optional.empty(),
				codeLookup);
	}

	@Override
	public Employee insert(Employee employee) {
		jdbc.update(
				"""
						insert into employees (
						  id, organization_id, branch_id, display_name, username, code_hash, code_lookup, active, lookback_days
						) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
						""",
				employee.id(),
				employee.organizationId(),
				employee.branchId(),
				employee.displayName(),
				employee.username(),
				employee.codeHash(),
				employee.codeLookup(),
				employee.active(),
				employee.lookbackDays());
		return employee;
	}

	@Override
	public Employee update(Employee employee) {
		jdbc.update(
				"""
						update employees
						set display_name = ?, username = ?, code_hash = ?, code_lookup = ?, active = ?, lookback_days = ?, updated_at = now()
						where id = ?
						""",
				employee.displayName(),
				employee.username(),
				employee.codeHash(),
				employee.codeLookup(),
				employee.active(),
				employee.lookbackDays(),
				employee.id());
		return employee;
	}

	@Override
	public void delete(UUID employeeId) {
		jdbc.update("delete from employees where id = ?", employeeId);
	}

	@Override
	public EmployeeSession insertSession(UUID employeeId, Instant expiresAt) {
		UUID id = UUID.randomUUID();
		jdbc.update(
				"insert into employee_sessions (id, employee_id, expires_at) values (?, ?, ?)",
				id,
				employeeId,
				Timestamp.from(expiresAt));
		return new EmployeeSession(id, employeeId, expiresAt, null);
	}

	@Override
	public Optional<EmployeeSession> findSession(UUID sessionId) {
		return jdbc.query(
				"select id, employee_id, expires_at, revoked_at from employee_sessions where id = ?",
				rs -> rs.next() ? Optional.of(session(rs)) : Optional.empty(),
				sessionId);
	}

	@Override
	public void revokeSession(UUID sessionId) {
		jdbc.update(
				"update employee_sessions set revoked_at = now() where id = ? and revoked_at is null",
				sessionId);
	}

	@Override
	public void revokeSessionsOf(UUID employeeId) {
		jdbc.update(
				"update employee_sessions set revoked_at = now() where employee_id = ? and revoked_at is null",
				employeeId);
	}

	private Employee employee(ResultSet rs, int ignored) throws SQLException {
		return new Employee(
				rs.getObject("id", UUID.class),
				rs.getObject("organization_id", UUID.class),
				rs.getObject("branch_id", UUID.class),
				rs.getString("display_name"),
				rs.getString("username"),
				rs.getString("code_hash"),
				rs.getString("code_lookup"),
				rs.getBoolean("active"),
				rs.getInt("lookback_days"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	private EmployeeSession session(ResultSet rs) throws SQLException {
		OffsetDateTime revoked = rs.getObject("revoked_at", OffsetDateTime.class);
		return new EmployeeSession(
				rs.getObject("id", UUID.class),
				rs.getObject("employee_id", UUID.class),
				rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
				revoked == null ? null : revoked.toInstant());
	}
}
