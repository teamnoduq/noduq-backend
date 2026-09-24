package com.noduq.application.identity;

import com.noduq.domain.identity.CommerceDetails;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.identity.Profile;
import com.noduq.domain.identity.port.AuthUserDirectory;
import com.noduq.domain.identity.port.OwnerWorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

@Service
public class OwnerAccountService {

	private static final Logger log = LoggerFactory.getLogger(OwnerAccountService.class);

	private final OwnerWorkspaceRepository workspaces;
	private final AuthUserDirectory authUsers;
	private final OrganizationPlanService plans;

	public OwnerAccountService(
			OwnerWorkspaceRepository workspaces, AuthUserDirectory authUsers, OrganizationPlanService plans) {
		this.workspaces = workspaces;
		this.authUsers = authUsers;
		this.plans = plans;
	}

	@Transactional(readOnly = true)
	public Optional<OwnerWorkspace> findWorkspace(UUID profileId) {
		return workspaces.findByProfileId(profileId);
	}

	@Transactional(readOnly = true)
	public OwnerWorkspace requireWorkspace(UUID profileId) {
		return findWorkspace(profileId).orElseThrow(IdentityException::notProvisioned);
	}

	@Transactional
	public OwnerWorkspace bootstrap(UUID profileId, String displayName, String organizationName) {
		return workspaces.findByProfileId(profileId).orElseGet(() -> {
			String name = requiredName(
					organizationName, "ORGANIZATION_NAME_INVALID", "El nombre de la organización es obligatorio.");
			String profileName = displayName == null || displayName.isBlank() ? name : displayName.trim();
			return workspaces.createOwnerBusiness(profileId, profileName, name, null, null, "Principal");
		});
	}

	@Transactional
	public Profile renameOwner(UUID profileId, String displayName) {
		requireWorkspace(profileId);
		if (displayName == null || displayName.isBlank()) {
			throw IdentityException.validation("DISPLAY_NAME_INVALID", "El nombre es obligatorio.");
		}
		return workspaces.updateDisplayName(profileId, displayName.trim());
	}

	@Transactional
	public OwnerWorkspace updateOrganization(
			UUID profileId, String organizationName, String merchantLast4, String smsPhone) {
		OwnerWorkspace workspace = requireWorkspace(profileId);
		workspace.requireOwner();
		String name = requiredName(
				organizationName, "ORGANIZATION_NAME_INVALID", "El nombre de la organización es obligatorio.");
		String last4 = merchantLast4 == null || merchantLast4.isBlank()
				? workspace.organization().merchantLast4()
				: CommerceDetails.merchantLast4(merchantLast4);
		String phone = smsPhone == null ? workspace.organization().smsPhone() : CommerceDetails.smsPhone(smsPhone);
		workspaces.updateOrganization(workspace.organization().id(), name, last4, phone);
		return requireWorkspace(profileId);
	}

	public void deleteAccount(UUID profileId, String confirmation) {
		OwnerWorkspace workspace = requireWorkspace(profileId);
		workspace.requireOwner();
		if (plans.isActive(workspace.organization().id())) {
			throw IdentityException.conflict(
					"SUBSCRIPTION_ACTIVE",
					"Cancela el plan y espera a que se acabe el periodo antes de borrar la cuenta.");
		}
		String expected = workspace.organization().name().trim();
		if (confirmation == null || !equalsIgnoreCaseAndSpace(expected, confirmation)) {
			throw IdentityException.validation(
					"CONFIRMATION_MISMATCH",
					"Escribe el nombre de la organización para borrar la cuenta.");
		}
		workspaces.deleteBusiness(workspace.organization().id(), profileId);
		try {
			authUsers.deleteAuthUser(profileId);
		} catch (RuntimeException ex) {
			log.warn("Shop wiped; Auth delete failed profile={}", profileId, ex);
		}
	}

	private static String requiredName(String value, String code, String message) {
		if (value == null || value.isBlank()) {
			throw IdentityException.validation(code, message);
		}
		String trimmed = value.trim();
		if (trimmed.length() < 2 || trimmed.length() > 80) {
			throw IdentityException.validation(code, "El nombre debe tener entre 2 y 80 caracteres.");
		}
		return trimmed;
	}

	private static boolean equalsIgnoreCaseAndSpace(String expected, String actual) {
		String left = expected.replaceAll("\\s+", " ").trim();
		String right = actual.replaceAll("\\s+", " ").trim();
		return left.equalsIgnoreCase(right);
	}
}
