package com.noduq.application.identity;

import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.MemberRole;
import com.noduq.domain.identity.Organization;
import com.noduq.domain.identity.OrganizationMember;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.identity.Profile;
import com.noduq.domain.identity.port.AuthUserDirectory;
import com.noduq.domain.identity.port.OwnerWorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OwnerAccountServiceTest {

	@Mock
	private OwnerWorkspaceRepository workspaces;

	@Mock
	private AuthUserDirectory authUsers;

	@Mock
	private OrganizationPlanService plans;

	@InjectMocks
	private OwnerAccountService service;

	@Test
	void deleteAccountRequiresExactOrganizationName() {
		UUID profileId = UUID.randomUUID();
		UUID organizationId = UUID.randomUUID();
		OwnerWorkspace workspace = workspace(profileId, organizationId, "Café Central");
		when(workspaces.findByProfileId(profileId)).thenReturn(Optional.of(workspace));

		IdentityException error = assertThrows(
				IdentityException.class,
				() -> service.deleteAccount(profileId, "otro nombre"));
		assertEquals("CONFIRMATION_MISMATCH", error.code());
	}

	@Test
	void deleteAccountRemovesBusinessAndAuthUser() {
		UUID profileId = UUID.randomUUID();
		UUID organizationId = UUID.randomUUID();
		OwnerWorkspace workspace = workspace(profileId, organizationId, "Café Central");
		when(workspaces.findByProfileId(profileId)).thenReturn(Optional.of(workspace));
		when(plans.isActive(organizationId)).thenReturn(false);

		service.deleteAccount(profileId, "Café Central");

		verify(workspaces).deleteBusiness(organizationId, profileId);
		verify(authUsers).deleteAuthUser(profileId);
	}

	private static OwnerWorkspace workspace(UUID profileId, UUID organizationId, String name) {
		Instant now = Instant.parse("2026-09-10T12:00:00Z");
		return new OwnerWorkspace(
				new Profile(profileId, "Dueño", now),
				new Organization(organizationId, name, null, "8186", now),
				new OrganizationMember(UUID.randomUUID(), organizationId, profileId, MemberRole.OWNER, now),
				List.of());
	}
}
