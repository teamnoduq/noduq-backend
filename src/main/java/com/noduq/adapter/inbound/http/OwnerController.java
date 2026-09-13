package com.noduq.adapter.inbound.http;

import com.noduq.application.identity.OwnerAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
	ResponseEntity<?> me(Authentication authentication) {
		log.info("GET /v1/me auth={}", authentication == null ? "null" : authentication.getClass().getSimpleName());
		UUID id = OwnerAuth.userId(authentication);
		log.info("GET /v1/me sub={}", id);
		return owners.findWorkspace(id)
				.<ResponseEntity<?>>map(workspace -> ResponseEntity.ok(IdentityResponses.WorkspaceResponse.from(workspace)))
				.orElseGet(() -> {
					log.info("GET /v1/me not provisioned sub={}", id);
					return ResponseEntity.status(422).body(new ApiError(
							"NOT_PROVISIONED",
							"Esta cuenta todavía no tiene organización. Hay que crear el comercio."));
				});
	}

	@PostMapping("/me/bootstrap")
	IdentityResponses.WorkspaceResponse bootstrap(
			Authentication authentication,
			@Valid @RequestBody BootstrapRequest body) {
		UUID id = OwnerAuth.userId(authentication);
		log.info("POST /v1/me/bootstrap sub={}", id);
		String displayName = body.displayName() != null
				? body.displayName()
				: OwnerAuth.jwt(authentication).getClaimAsString("email");
		return IdentityResponses.WorkspaceResponse.from(owners.bootstrap(id, displayName, body.organizationName()));
	}

	@PatchMapping("/me")
	IdentityResponses.ProfileResponse patchMe(Authentication authentication, @RequestBody PatchMeRequest body) {
		return IdentityResponses.ProfileResponse.from(owners.renameOwner(OwnerAuth.userId(authentication), body.displayName()));
	}

	@DeleteMapping("/me")
	void deleteMe(Authentication authentication, @Valid @RequestBody DeleteAccountRequest body) {
		owners.deleteAccount(OwnerAuth.userId(authentication), body.confirmation());
	}

	@GetMapping("/organization")
	IdentityResponses.OrganizationResponse organization(Authentication authentication) {
		return IdentityResponses.OrganizationResponse.from(
				owners.requireWorkspace(OwnerAuth.userId(authentication)).organization());
	}

	@PatchMapping("/organization")
	IdentityResponses.WorkspaceResponse renameOrganization(
			Authentication authentication,
			@RequestBody PatchOrganizationRequest body) {
		return IdentityResponses.WorkspaceResponse.from(
				owners.updateOrganization(
						OwnerAuth.userId(authentication), body.name(), body.merchantLast4(), body.smsPhone()));
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
