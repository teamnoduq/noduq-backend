package com.noduq.adapter.inbound.http;

import com.noduq.application.identity.OwnerAccountService;
import com.noduq.domain.identity.IdentityException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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

	private static final Logger log = LoggerFactory.getLogger(OwnerController.class);

	private final OwnerAccountService owners;

	public OwnerController(OwnerAccountService owners) {
		this.owners = owners;
	}

	@GetMapping("/me")
	ResponseEntity<?> me(@AuthenticationPrincipal Jwt jwt) {
		UUID id = userId(jwt);
		log.info("GET /v1/me sub={}", id);
		return owners.findWorkspace(id)
				.<ResponseEntity<?>>map(workspace -> ResponseEntity.ok(IdentityResponses.WorkspaceResponse.from(workspace)))
				.orElseGet(() -> {
					log.info("GET /v1/me not provisioned sub={}", id);
					return ResponseEntity.status(404).body(new ApiError(
							"NOT_PROVISIONED",
							"Esta cuenta todavía no tiene organización. Hay que crear el comercio."));
				});
	}

	@PostMapping("/me/bootstrap")
	IdentityResponses.WorkspaceResponse bootstrap(
			@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody BootstrapRequest body) {
		UUID id = userId(jwt);
		log.info("POST /v1/me/bootstrap sub={}", id);
		String displayName = body.displayName() != null ? body.displayName() : jwt.getClaimAsString("email");
		return IdentityResponses.WorkspaceResponse.from(owners.bootstrap(id, displayName, body.organizationName()));
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
		return IdentityResponses.WorkspaceResponse.from(
				owners.updateOrganization(userId(jwt), body.name(), body.merchantLast4(), body.smsPhone()));
	}

	private static UUID userId(Jwt jwt) {
		if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
			throw IdentityException.unauthorized("Sesión inválida.");
		}
		try {
			return UUID.fromString(jwt.getSubject());
		} catch (IllegalArgumentException ex) {
			throw IdentityException.unauthorized("Sesión inválida.");
		}
	}

	public record BootstrapRequest(String displayName, @NotBlank String organizationName) {
	}

	public record PatchMeRequest(String displayName) {
	}

	public record PatchOrganizationRequest(String name, String merchantLast4, String smsPhone) {
	}

	public record DeleteAccountRequest(@NotBlank String confirmation) {
	}
}
