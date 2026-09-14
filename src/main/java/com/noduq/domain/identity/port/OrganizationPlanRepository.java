package com.noduq.domain.identity.port;

import com.noduq.domain.identity.OrganizationPlan;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationPlanRepository {

	Optional<OrganizationPlan> find(UUID organizationId);

	void upsert(OrganizationPlan plan);
}
