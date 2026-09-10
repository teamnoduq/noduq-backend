package com.noduq.adapter.inbound.http;

import com.noduq.adapter.inbound.security.EmployeeCaller;
import com.noduq.application.identity.EmployeeSessionService;
import com.noduq.domain.identity.Branch;
import com.noduq.domain.identity.Employee;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.OwnerWorkspace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/employee")
public class EmployeeAuthController {

	private final EmployeeSessionService sessions;

	public EmployeeAuthController(EmployeeSessionService sessions) {
		this.sessions = sessions;
	}

	@PostMapping("/sessions")
	IdentityResponses.EmployeeSessionResponse login(@Valid @RequestBody LoginRequest body) {
		var opened = sessions.login(body.username(), body.code());
		return toResponse(opened.token(), opened.expiresAt(), opened.employee(), opened.workspace());
	}

	@GetMapping("/me")
	IdentityResponses.EmployeeSessionResponse me(
			@AuthenticationPrincipal EmployeeCaller caller,
			org.springframework.http.HttpHeaders headers) {
		String token = bearer(headers);
		var authenticated = sessions.authenticate(token);
		return toResponse(token, authenticated.session().expiresAt(), authenticated.employee(), authenticated.workspace());
	}

	@DeleteMapping("/sessions/me")
	void logout(@AuthenticationPrincipal EmployeeCaller caller) {
		sessions.logout(caller.sessionId());
	}

	private static IdentityResponses.EmployeeSessionResponse toResponse(
			String token,
			java.time.Instant expiresAt,
			Employee employee,
			OwnerWorkspace workspace) {
		Branch branch = workspace.branches().stream()
				.filter(item -> item.id().equals(employee.branchId()))
				.findFirst()
				.orElseThrow(() -> IdentityException.notFound("Esa sucursal no existe en este local."));
		return new IdentityResponses.EmployeeSessionResponse(
				token,
				expiresAt,
				IdentityResponses.EmployeeResponse.from(employee),
				IdentityResponses.OrganizationResponse.from(workspace.organization()),
				IdentityResponses.BranchResponse.from(branch));
	}

	private static String bearer(org.springframework.http.HttpHeaders headers) {
		String header = headers.getFirst(org.springframework.http.HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith("Bearer ")) {
			throw IdentityException.unauthorized("Sesión inválida.");
		}
		return header.substring("Bearer ".length());
	}

	public record LoginRequest(@NotBlank String username, @NotBlank String code) {
	}
}
