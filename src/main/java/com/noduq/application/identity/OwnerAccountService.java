package com.noduq.application.identity;

import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.identity.Profile;
import com.noduq.domain.identity.port.AuthUserDirectory;
import com.noduq.domain.identity.port.OwnerWorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OwnerAccountService {

	private final OwnerWorkspaceRepository workspaces;
	private final AuthUserDirectory authUsers;

	public OwnerAccountService(OwnerWorkspaceRepository workspaces, AuthUserDirectory authUsers) {
		this.workspaces = workspaces;
		this.authUsers = authUsers;
	}

	@Transactional(readOnly = true)
	public OwnerWorkspace requireWorkspace(UUID profileId) {
		return workspaces.findByProfileId(profileId).orElseThrow(IdentityException::notProvisioned);
	}

	@Transactional
	public OwnerWorkspace bootstrap(UUID profileId, String displayName, String organizationName) {
		return workspaces.findByProfileId(profileId).orElseGet(() -> {
			String name = requiredName(organizationName, "ORGANIZATION_NAME_INVALID", "El nombre del local es obligatorio.");
			String profileName = displayName == null || displayName.isBlank() ? name : displayName.trim();
			return workspaces.createOwnerBusiness(profileId, profileName, name, "Principal");
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
	public OwnerWorkspace renameOrganization(UUID profileId, String organizationName) {
		OwnerWorkspace workspace = requireWorkspace(profileId);
		workspace.requireOwner();
		String name = requiredName(organizationName, "ORGANIZATION_NAME_INVALID", "El nombre del local es obligatorio.");
		workspaces.renameOrganization(workspace.organization().id(), name);
		return requireWorkspace(profileId);
	}

	@Transactional
	public void deleteAccount(UUID profileId, String confirmation) {
		OwnerWorkspace workspace = requireWorkspace(profileId);
		workspace.requireOwner();
		String expected = workspace.organization().name().trim();
		if (confirmation == null || !equalsIgnoreCaseAndSpace(expected, confirmation)) {
			throw IdentityException.validation(
					"CONFIRMATION_MISMATCH",
					"Escribe el nombre del local para borrar la cuenta.");
		}
		workspaces.deleteBusiness(workspace.organization().id(), profileId);
		authUsers.deleteAuthUser(profileId);
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
