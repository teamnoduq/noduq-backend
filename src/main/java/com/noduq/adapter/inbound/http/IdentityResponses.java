package com.noduq.adapter.inbound.http;

import com.noduq.domain.identity.Branch;
import com.noduq.domain.identity.Employee;
import com.noduq.domain.identity.Organization;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.identity.Profile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class IdentityResponses {

	private IdentityResponses() {
	}

	public record ProfileResponse(UUID id, String displayName) {
		static ProfileResponse from(Profile profile) {
			return new ProfileResponse(profile.id(), profile.displayName());
		}
	}

	public record OrganizationResponse(UUID id, String name, String smsPhone, String merchantLast4) {
		static OrganizationResponse from(Organization organization) {
			return new OrganizationResponse(
					organization.id(),
					organization.name(),
					organization.smsPhone(),
					organization.merchantLast4());
		}
	}

	public record BranchResponse(UUID id, String name) {
		static BranchResponse from(Branch branch) {
			return new BranchResponse(branch.id(), branch.name());
		}
	}

	public record WorkspaceResponse(
			ProfileResponse profile,
			OrganizationResponse organization,
			String role,
			List<BranchResponse> branches,
			PlanResponse plan) {
		static WorkspaceResponse from(OwnerWorkspace workspace, PlanResponse plan) {
			return new WorkspaceResponse(
					ProfileResponse.from(workspace.profile()),
					OrganizationResponse.from(workspace.organization()),
					workspace.membership().role().dbValue(),
					workspace.branches().stream().map(BranchResponse::from).toList(),
					plan);
		}
	}

	public record PlanResponse(String entitlement, String status, Instant periodEndsAt, boolean active) {
		public static PlanResponse none() {
			return new PlanResponse(null, "none", null, false);
		}

		public static PlanResponse from(com.noduq.domain.identity.OrganizationPlan plan) {
			boolean active = plan.active(Instant.now());
			return new PlanResponse(plan.entitlement(), plan.status(), plan.periodEndsAt(), active);
		}
	}

	public record EmployeeResponse(
			UUID id,
			UUID branchId,
			String displayName,
			String username,
			boolean active,
			int lookbackDays,
			Instant createdAt) {
		static EmployeeResponse from(Employee employee) {
			return new EmployeeResponse(
					employee.id(),
					employee.branchId(),
					employee.displayName(),
					employee.username(),
					employee.active(),
					employee.lookbackDays(),
					employee.createdAt());
		}
	}

	public record CreatedEmployeeResponse(
			UUID id,
			UUID branchId,
			String displayName,
			String username,
			boolean active,
			int lookbackDays,
			String code) {
		static CreatedEmployeeResponse from(Employee employee, String code) {
			return new CreatedEmployeeResponse(
					employee.id(),
					employee.branchId(),
					employee.displayName(),
					employee.username(),
					employee.active(),
					employee.lookbackDays(),
					code);
		}
	}

	public record EmployeeSessionResponse(
			String token,
			Instant expiresAt,
			EmployeeResponse employee,
			OrganizationResponse organization,
			BranchResponse branch) {
	}
}
