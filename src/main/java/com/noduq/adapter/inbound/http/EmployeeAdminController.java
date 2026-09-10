package com.noduq.adapter.inbound.http;

import com.noduq.application.identity.EmployeeManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/employees")
public class EmployeeAdminController {

	private final EmployeeManagementService employees;

	public EmployeeAdminController(EmployeeManagementService employees) {
		this.employees = employees;
	}

	@GetMapping
	List<IdentityResponses.EmployeeResponse> list(@AuthenticationPrincipal Jwt jwt) {
		return employees.list(userId(jwt)).stream().map(IdentityResponses.EmployeeResponse::from).toList();
	}

	@PostMapping
	IdentityResponses.CreatedEmployeeResponse create(
			@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody CreateEmployeeRequest body) {
		var created = employees.create(userId(jwt), body.displayName(), body.username(), body.branchId());
		return IdentityResponses.CreatedEmployeeResponse.from(created.employee(), created.code());
	}

	@PatchMapping("/{id}")
	IdentityResponses.EmployeeResponse update(
			@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID id,
			@RequestBody PatchEmployeeRequest body) {
		return IdentityResponses.EmployeeResponse.from(
				employees.update(userId(jwt), id, body.displayName(), body.username(), body.active()));
	}

	@PostMapping("/{id}/code")
	IdentityResponses.CreatedEmployeeResponse regenerate(
			@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID id) {
		var created = employees.regenerateCode(userId(jwt), id);
		return IdentityResponses.CreatedEmployeeResponse.from(created.employee(), created.code());
	}

	@DeleteMapping("/{id}")
	void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		employees.delete(userId(jwt), id);
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	public record CreateEmployeeRequest(@NotBlank String displayName, String username, UUID branchId) {
	}

	public record PatchEmployeeRequest(String displayName, String username, Boolean active) {
	}
}
