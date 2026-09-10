package com.noduq.application.identity;

import com.noduq.domain.identity.Employee;
import com.noduq.domain.identity.EmployeeCode;
import com.noduq.domain.identity.EmployeeSession;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.identity.Username;
import com.noduq.domain.identity.port.EmployeeCodeHasher;
import com.noduq.domain.identity.port.EmployeeRepository;
import com.noduq.domain.identity.port.EmployeeTokenIssuer;
import com.noduq.domain.identity.port.LoginThrottle;
import com.noduq.domain.identity.port.OwnerWorkspaceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class EmployeeSessionService {

	private final EmployeeRepository employees;
	private final OwnerWorkspaceRepository workspaces;
	private final EmployeeCodeHasher codes;
	private final EmployeeTokenIssuer tokens;
	private final LoginThrottle throttle;
	private final Duration sessionTtl;
	private final Clock clock;

	public EmployeeSessionService(
			EmployeeRepository employees,
			OwnerWorkspaceRepository workspaces,
			EmployeeCodeHasher codes,
			EmployeeTokenIssuer tokens,
			LoginThrottle throttle,
			@Value("${noduq.employee.session-days}") long sessionDays) {
		this.employees = employees;
		this.workspaces = workspaces;
		this.codes = codes;
		this.tokens = tokens;
		this.throttle = throttle;
		this.sessionTtl = Duration.ofDays(sessionDays);
		this.clock = Clock.systemUTC();
	}

	@Transactional
	public OpenedSession login(String rawUsername, String rawCode) {
		Username username;
		try {
			username = Username.parse(rawUsername);
		} catch (IdentityException ex) {
			throw IdentityException.unauthorized("Usuario o código incorrecto.");
		}
		throttle.guard(username.value());
		EmployeeCode code = EmployeeCode.parse(rawCode);
		if (code == null) {
			throttle.recordFailure(username.value());
			throw IdentityException.unauthorized("Usuario o código incorrecto.");
		}
		Employee employee = employees.findByCodeLookup(codes.lookup(code)).orElse(null);
		boolean matches = employee != null
				&& employee.username().equals(username.value())
				&& codes.matches(code, employee.codeHash());
		if (!matches) {
			throttle.recordFailure(username.value());
			throw IdentityException.unauthorized("Usuario o código incorrecto.");
		}
		if (!employee.active()) {
			throw IdentityException.forbidden("Este empleado está desactivado.");
		}
		throttle.clear(username.value());
		Instant expiresAt = Instant.now(clock).plus(sessionTtl);
		EmployeeSession session = employees.insertSession(employee.id(), expiresAt);
		String token = tokens.issue(session.id(), employee.id(), employee.organizationId(), expiresAt);
		OwnerWorkspace workspace = workspaces.findByOrganizationId(employee.organizationId())
				.orElseThrow(() -> IdentityException.unauthorized("Usuario o código incorrecto."));
		return new OpenedSession(token, expiresAt, employee, workspace);
	}

	@Transactional(readOnly = true)
	public AuthenticatedEmployee authenticate(String token) {
		EmployeeTokenIssuer.EmployeeTokenPayload payload;
		try {
			payload = tokens.parse(token);
		} catch (RuntimeException ex) {
			throw IdentityException.unauthorized("Sesión inválida.");
		}
		EmployeeSession session = employees.findSession(payload.sessionId())
				.orElseThrow(() -> IdentityException.unauthorized("Sesión inválida."));
		if (!session.usable(Instant.now(clock)) || !session.employeeId().equals(payload.employeeId())) {
			throw IdentityException.unauthorized("Sesión inválida.");
		}
		Employee employee = employees.findById(payload.employeeId())
				.orElseThrow(() -> IdentityException.unauthorized("Sesión inválida."));
		if (!employee.active()) {
			throw IdentityException.forbidden("Este empleado está desactivado.");
		}
		OwnerWorkspace workspace = workspaces.findByOrganizationId(employee.organizationId())
				.orElseThrow(() -> IdentityException.unauthorized("Sesión inválida."));
		return new AuthenticatedEmployee(employee, session, workspace);
	}

	@Transactional
	public void logout(UUID sessionId) {
		employees.revokeSession(sessionId);
	}

	public record OpenedSession(String token, Instant expiresAt, Employee employee, OwnerWorkspace workspace) {
	}

	public record AuthenticatedEmployee(Employee employee, EmployeeSession session, OwnerWorkspace workspace) {
	}
}
