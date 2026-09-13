package com.noduq.domain.identity.port;

import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.identity.Profile;

import java.util.Optional;
import java.util.UUID;

public interface OwnerWorkspaceRepository {

	Optional<OwnerWorkspace> findByProfileId(UUID profileId);

	Optional<OwnerWorkspace> findByOrganizationId(UUID organizationId);

	OwnerWorkspace createOwnerBusiness(
			UUID profileId,
			String displayName,
			String organizationName,
			String merchantLast4,
			String smsPhone,
			String branchName);

	Profile updateDisplayName(UUID profileId, String displayName);

	void updateOrganization(UUID organizationId, String name, String merchantLast4, String smsPhone);

	void deleteBusiness(UUID organizationId, UUID profileId);
}
