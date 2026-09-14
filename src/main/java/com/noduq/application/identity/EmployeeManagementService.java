package com.noduq.application.identity;

import com.noduq.domain.identity.Employee;
import com.noduq.domain.identity.EmployeeCode;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.LookbackDays;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.identity.Username;
import com.noduq.domain.identity.port.EmployeeCodeHasher;
import com.noduq.domain.identity.port.EmployeeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class EmployeeManagementService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final OwnerAccountService owners;
	private final EmployeeRepository employees;
	private final EmployeeCodeHasher codes;

	public EmployeeManagementService(
			OwnerAccountService owners,
			EmployeeRepository employees,
			EmployeeCodeHasher codes) {
		this.owners = owners;
		this.employees = employees;
		this.codes = codes;
	}

	@Transactional(readOnly = true)
	public List<Employee> list(UUID profileId) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		return employees.listByOrganization(workspace.organization().id());
	}

	@Transactional
	public CreatedEmployee create(UUID profileId, String displayName, String rawUsername, UUID branchId) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		String name = requireDisplayName(displayName);
		var taken = employees.usernamesInOrganization(workspace.organization().id());
		Username username = rawUsername == null || rawUsername.isBlank()
				? Username.fromDisplayName(name, taken)
				: Username.parse(rawUsername);
		if (taken.contains(username.value())) {
			throw IdentityException.conflict("USERNAME_TAKEN", "Ese usuario ya existe en este local.");
		}
		UUID resolvedBranch = branchId == null ? workspace.primaryBranch().id() : workspace.requireBranch(branchId).id();
		EmployeeCode plaintext = uniqueCode();
		Employee stored = employees.insert(new Employee(
				UUID.randomUUID(),
				workspace.organization().id(),
				resolvedBranch,
				name,
				username.value(),
				codes.hash(plaintext),
				codes.lookup(plaintext),
				true,
				1,
				Instant.now()));
		return new CreatedEmployee(stored, plaintext.display());
	}

	@Transactional
	public Employee update(
			UUID profileId,
			UUID employeeId,
			String displayName,
			String rawUsername,
			Boolean active,
			Integer lookbackDays) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		Employee employee = requireInOrganization(workspace.organization().id(), employeeId);
		String name = displayName == null ? employee.displayName() : requireDisplayName(displayName);
		String username = employee.username();
		if (rawUsername != null && !rawUsername.isBlank()) {
			username = Username.parse(rawUsername).value();
			if (!username.equals(employee.username())
					&& employees.usernamesInOrganization(workspace.organization().id()).contains(username)) {
				throw IdentityException.conflict("USERNAME_TAKEN", "Ese usuario ya existe en este local.");
			}
		}
		boolean nextActive = active == null ? employee.active() : active;
		int lookback = lookbackDays == null ? employee.lookbackDays() : LookbackDays.of(lookbackDays).days();
		return employees.update(new Employee(
				employee.id(),
				employee.organizationId(),
				employee.branchId(),
				name,
				username,
				employee.codeHash(),
				employee.codeLookup(),
				nextActive,
				lookback,
				employee.createdAt()));
	}

	@Transactional
	public CreatedEmployee regenerateCode(UUID profileId, UUID employeeId) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		Employee employee = requireInOrganization(workspace.organization().id(), employeeId);
		EmployeeCode plaintext = uniqueCode();
		employees.revokeSessionsOf(employee.id());
		Employee stored = employees.update(new Employee(
				employee.id(),
				employee.organizationId(),
				employee.branchId(),
				employee.displayName(),
				employee.username(),
				codes.hash(plaintext),
				codes.lookup(plaintext),
				employee.active(),
				employee.lookbackDays(),
				employee.createdAt()));
		return new CreatedEmployee(stored, plaintext.display());
	}

	@Transactional
	public void delete(UUID profileId, UUID employeeId) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		requireInOrganization(workspace.organization().id(), employeeId);
		employees.delete(employeeId);
	}

	private Employee requireInOrganization(UUID organizationId, UUID employeeId) {
		Employee employee = employees.findById(employeeId)
				.orElseThrow(() -> IdentityException.notFound("Ese empleado no existe."));
		if (!employee.organizationId().equals(organizationId)) {
			throw IdentityException.notFound("Ese empleado no existe.");
		}
		return employee;
	}

	private EmployeeCode uniqueCode() {
		for (int i = 0; i < 8; i++) {
			EmployeeCode code = EmployeeCode.generate(RANDOM);
			if (employees.findByCodeLookup(codes.lookup(code)).isEmpty()) {
				return code;
			}
		}
		throw IdentityException.conflict("CODE_RETRY", "No se pudo generar un código. Inténtalo de nuevo.");
	}

	private static String requireDisplayName(String displayName) {
		if (displayName == null || displayName.isBlank()) {
			throw IdentityException.validation("DISPLAY_NAME_INVALID", "El nombre del empleado es obligatorio.");
		}
		String trimmed = displayName.trim();
		if (trimmed.length() > 80) {
			throw IdentityException.validation("DISPLAY_NAME_INVALID", "El nombre es demasiado largo.");
		}
		return trimmed;
	}

	public record CreatedEmployee(Employee employee, String code) {
	}
}
