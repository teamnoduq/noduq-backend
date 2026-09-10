package com.noduq.domain.identity.port;

import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.identity.Profile;

import java.util.Optional;
import java.util.UUID;

public interface OwnerWorkspaceRepository {

	Optional<OwnerWorkspace> findByProfileId(UUID profileId);

	Optional<OwnerWorkspace> findByOrganizationId(UUID organizationId);

	OwnerWorkspace createOwnerBusiness(UUID profileId, String displayName, String organizationName, String branchName);

	Profile updateDisplayName(UUID profileId, String displayName);

	void renameOrganization(UUID organizationId, String name);

	void deleteBusiness(UUID organizationId, UUID profileId);
}
