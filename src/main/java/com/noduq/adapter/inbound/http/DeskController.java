package com.noduq.adapter.inbound.http;

import com.noduq.application.identity.DeskLoginService;
import com.noduq.application.identity.EmployeeSessionService;
import com.noduq.domain.identity.Branch;
import com.noduq.domain.identity.Employee;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.OwnerWorkspace;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/desk")
public class DeskController {

	private final DeskLoginService desks;

	public DeskController(DeskLoginService desks) {
		this.desks = desks;
	}

	@PostMapping("/tickets")
	IssuedResponse issue() {
		var issued = desks.issue();
		return new IssuedResponse(issued.id(), issued.secret(), issued.payload(), issued.expiresAt());
	}

	@GetMapping("/tickets/{id}")
	PollResponse poll(@PathVariable String id, @RequestParam String secret) {
		var snapshot = desks.poll(parse(id), secret);
		if (!"claimed".equals(snapshot.status()) || snapshot.kind() == null) {
			return new PollResponse(snapshot.status(), snapshot.expiresAt(), snapshot.kind(), snapshot.hashedToken(), null);
		}
		if ("owner".equals(snapshot.kind())) {
			return new PollResponse("claimed", snapshot.expiresAt(), "owner", snapshot.hashedToken(), null);
		}
		return new PollResponse(
				"claimed",
				snapshot.expiresAt(),
				"employee",
				null,
				toEmployee(snapshot.employeeSession()));
	}

	@PostMapping("/tickets/{id}/claim")
	void claimAsOwner(Authentication authentication, @PathVariable String id) {
		desks.claimByOwner(parse(id), OwnerAuth.userId(authentication));
	}

	private static UUID parse(String id) {
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException ex) {
			throw IdentityException.notFound("Ese código no es de NODUQ.");
		}
	}

	private static IdentityResponses.EmployeeSessionResponse toEmployee(EmployeeSessionService.OpenedSession opened) {
		Employee employee = opened.employee();
		OwnerWorkspace workspace = opened.workspace();
		Branch branch = workspace.branches().stream()
				.filter(item -> item.id().equals(employee.branchId()))
				.findFirst()
				.orElseThrow(() -> IdentityException.notFound("Esa sucursal no existe en este local."));
		return new IdentityResponses.EmployeeSessionResponse(
				opened.token(),
				opened.expiresAt(),
				IdentityResponses.EmployeeResponse.from(employee),
				IdentityResponses.OrganizationResponse.from(workspace.organization()),
				IdentityResponses.BranchResponse.from(branch));
	}

	public record IssuedResponse(UUID id, String secret, String payload, Instant expiresAt) {
	}

	@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
	public record PollResponse(
			String status,
			Instant expiresAt,
			String kind,
			String hashedToken,
			IdentityResponses.EmployeeSessionResponse employee) {
	}
}
