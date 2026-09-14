package com.noduq.adapter.inbound.http;

import com.noduq.application.identity.EmployeeManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
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
	List<IdentityResponses.EmployeeResponse> list(Authentication authentication) {
		return employees.list(OwnerAuth.userId(authentication)).stream().map(IdentityResponses.EmployeeResponse::from).toList();
	}

	@PostMapping
	IdentityResponses.CreatedEmployeeResponse create(
			Authentication authentication,
			@Valid @RequestBody CreateEmployeeRequest body) {
		var created = employees.create(OwnerAuth.userId(authentication), body.displayName(), body.username(), body.branchId());
		return IdentityResponses.CreatedEmployeeResponse.from(created.employee(), created.code());
	}

	@PatchMapping("/{id}")
	IdentityResponses.EmployeeResponse update(
			Authentication authentication,
			@PathVariable UUID id,
			@RequestBody PatchEmployeeRequest body) {
		return IdentityResponses.EmployeeResponse.from(
				employees.update(OwnerAuth.userId(authentication), id, body.displayName(), body.username(), body.active(), body.lookbackDays()));
	}

	@PostMapping("/{id}/code")
	IdentityResponses.CreatedEmployeeResponse regenerate(Authentication authentication, @PathVariable UUID id) {
		var created = employees.regenerateCode(OwnerAuth.userId(authentication), id);
		return IdentityResponses.CreatedEmployeeResponse.from(created.employee(), created.code());
	}

	@DeleteMapping("/{id}")
	void delete(Authentication authentication, @PathVariable UUID id) {
		employees.delete(OwnerAuth.userId(authentication), id);
	}

	public record CreateEmployeeRequest(@NotBlank String displayName, String username, UUID branchId) {
	}

	public record PatchEmployeeRequest(String displayName, String username, Boolean active, Integer lookbackDays) {
	}
}
