package com.noduq.domain.identity.port;

import com.noduq.domain.identity.Employee;
import com.noduq.domain.identity.EmployeeSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface EmployeeRepository {

	List<Employee> listByOrganization(UUID organizationId);

	Set<String> usernamesInOrganization(UUID organizationId);

	Optional<Employee> findById(UUID employeeId);

	Optional<Employee> findByCodeLookup(String codeLookup);

	Employee insert(Employee employee);

	Employee update(Employee employee);

	void delete(UUID employeeId);

	EmployeeSession insertSession(UUID employeeId, Instant expiresAt);

	Optional<EmployeeSession> findSession(UUID sessionId);

	void revokeSession(UUID sessionId);

	void revokeSessionsOf(UUID employeeId);
}
