package com.noduq.application.identity;

import com.noduq.domain.identity.MemberRole;
import com.noduq.domain.identity.Organization;
import com.noduq.domain.identity.OrganizationMember;
import com.noduq.domain.identity.OrganizationPlan;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.identity.Profile;
import com.noduq.domain.identity.port.AuthUserDirectory;
import com.noduq.domain.identity.port.OrganizationPlanRepository;
import com.noduq.domain.identity.port.OwnerWorkspaceRepository;
import com.noduq.domain.identity.port.PlaySubscriptionGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OwnerAccountServiceTest {

	@Mock
	private OwnerWorkspaceRepository workspaces;

	@Mock
	private AuthUserDirectory authUsers;

	@Mock
	private OrganizationPlanRepository plans;

	@Mock
	private PlaySubscriptionGateway play;

	@InjectMocks
	private OwnerAccountService service;

	@Test
	void deleteAccountWipesShopWhenThereIsNoStoreSubscription() {
		UUID profileId = UUID.randomUUID();
		UUID organizationId = UUID.randomUUID();
		when(workspaces.findByProfileId(profileId)).thenReturn(Optional.of(workspace(profileId, organizationId)));
		when(plans.find(organizationId)).thenReturn(Optional.empty());

		service.deleteAccount(profileId);

		verify(play, never()).cancelRenewal(organizationId);
		verify(workspaces).deleteBusiness(organizationId, profileId);
		verify(authUsers).deleteAuthUser(profileId);
	}

	@Test
	void deleteAccountCancelsPlayRenewalBeforeWipingTheShop() {
		UUID profileId = UUID.randomUUID();
		UUID organizationId = UUID.randomUUID();
		when(workspaces.findByProfileId(profileId)).thenReturn(Optional.of(workspace(profileId, organizationId)));
		when(plans.find(organizationId)).thenReturn(Optional.of(storePlan(organizationId)));

		service.deleteAccount(profileId);

		InOrder order = inOrder(play, workspaces);
		order.verify(play).cancelRenewal(organizationId);
		order.verify(workspaces).deleteBusiness(organizationId, profileId);
		verify(authUsers).deleteAuthUser(profileId);
	}

	@Test
	void deleteAccountStaysWhenPlayCancelFails() {
		UUID profileId = UUID.randomUUID();
		UUID organizationId = UUID.randomUUID();
		when(workspaces.findByProfileId(profileId)).thenReturn(Optional.of(workspace(profileId, organizationId)));
		when(plans.find(organizationId)).thenReturn(Optional.of(storePlan(organizationId)));
		doThrow(com.noduq.domain.identity.IdentityException.validation("BILLING_CANCEL_FAILED", "no"))
				.when(play).cancelRenewal(organizationId);

		assertThrows(com.noduq.domain.identity.IdentityException.class, () -> service.deleteAccount(profileId));

		verify(workspaces, never()).deleteBusiness(organizationId, profileId);
		verify(authUsers, never()).deleteAuthUser(profileId);
	}

	private static OrganizationPlan storePlan(UUID organizationId) {
		return new OrganizationPlan(
				organizationId,
				OrganizationPlan.SMS,
				"active",
				Instant.now().plusSeconds(86_400),
				"noduq_sms_monthly",
				"evt-1");
	}

	private static OwnerWorkspace workspace(UUID profileId, UUID organizationId) {
		Instant now = Instant.parse("2026-09-10T12:00:00Z");
		return new OwnerWorkspace(
				new Profile(profileId, "Dueño", now),
				new Organization(organizationId, "Café Central", null, "8186", now),
				new OrganizationMember(UUID.randomUUID(), organizationId, profileId, MemberRole.OWNER, now),
				List.of());
	}
}
