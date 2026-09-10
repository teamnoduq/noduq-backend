package com.noduq.domain.identity;

import java.util.List;
import java.util.UUID;

public record OwnerWorkspace(
		Profile profile,
		Organization organization,
		OrganizationMember membership,
		List<Branch> branches) {

	public void requireOwner() {
		if (membership.role() != MemberRole.OWNER) {
			throw IdentityException.forbidden("Solo el dueño puede hacer esto.");
		}
	}

	public Branch requireBranch(UUID branchId) {
		return branches.stream()
				.filter(branch -> branch.id().equals(branchId))
				.findFirst()
				.orElseThrow(() -> IdentityException.notFound("Esa sucursal no existe en este local."));
	}

	public Branch primaryBranch() {
		if (branches.isEmpty()) {
			throw IdentityException.notFound("Este local no tiene sucursal.");
		}
		return branches.getFirst();
	}
}
