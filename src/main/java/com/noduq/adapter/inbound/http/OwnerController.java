package com.noduq.adapter.inbound.http;

import com.noduq.application.identity.OwnerAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class OwnerController {

	private final OwnerAccountService owners;

	public OwnerController(OwnerAccountService owners) {
		this.owners = owners;
	}

	@GetMapping("/me")
	IdentityResponses.WorkspaceResponse me(@AuthenticationPrincipal Jwt jwt) {
		return IdentityResponses.WorkspaceResponse.from(owners.requireWorkspace(userId(jwt)));
	}

	@PostMapping("/me/bootstrap")
	IdentityResponses.WorkspaceResponse bootstrap(
			@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody BootstrapRequest body) {
		String displayName = body.displayName() != null ? body.displayName() : jwt.getClaimAsString("email");
		return IdentityResponses.WorkspaceResponse.from(
				owners.bootstrap(userId(jwt), displayName, body.organizationName()));
	}

	@PatchMapping("/me")
	IdentityResponses.ProfileResponse patchMe(
			@AuthenticationPrincipal Jwt jwt,
			@RequestBody PatchMeRequest body) {
		return IdentityResponses.ProfileResponse.from(owners.renameOwner(userId(jwt), body.displayName()));
	}

	@DeleteMapping("/me")
	void deleteMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeleteAccountRequest body) {
		owners.deleteAccount(userId(jwt), body.confirmation());
	}

	@GetMapping("/organization")
	IdentityResponses.OrganizationResponse organization(@AuthenticationPrincipal Jwt jwt) {
		return IdentityResponses.OrganizationResponse.from(owners.requireWorkspace(userId(jwt)).organization());
	}

	@PatchMapping("/organization")
	IdentityResponses.WorkspaceResponse renameOrganization(
			@AuthenticationPrincipal Jwt jwt,
			@RequestBody PatchOrganizationRequest body) {
		return IdentityResponses.WorkspaceResponse.from(owners.renameOrganization(userId(jwt), body.name()));
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	public record BootstrapRequest(String displayName, @NotBlank String organizationName) {
	}

	public record PatchMeRequest(String displayName) {
	}

	public record PatchOrganizationRequest(String name) {
	}

	public record DeleteAccountRequest(@NotBlank String confirmation) {
	}
}
