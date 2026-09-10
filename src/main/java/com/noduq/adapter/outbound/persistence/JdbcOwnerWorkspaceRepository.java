package com.noduq.adapter.outbound.persistence;

import com.noduq.domain.identity.Branch;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.MemberRole;
import com.noduq.domain.identity.Organization;
import com.noduq.domain.identity.OrganizationMember;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.identity.Profile;
import com.noduq.domain.identity.port.OwnerWorkspaceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcOwnerWorkspaceRepository implements OwnerWorkspaceRepository {

	private final JdbcTemplate jdbc;

	public JdbcOwnerWorkspaceRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<OwnerWorkspace> findByProfileId(UUID profileId) {
		UUID organizationId = jdbc.query(
				"""
						select organization_id
						from organization_members
						where profile_id = ?
						""",
				rs -> rs.next() ? rs.getObject("organization_id", UUID.class) : null,
				profileId);
		if (organizationId == null) {
			return Optional.empty();
		}
		return findByOrganizationId(organizationId);
	}

	@Override
	public Optional<OwnerWorkspace> findByOrganizationId(UUID organizationId) {
		Organization organization = jdbc.query(
				"select id, name, sms_phone, created_at from organizations where id = ?",
				rs -> rs.next() ? organization(rs) : null,
				organizationId);
		if (organization == null) {
			return Optional.empty();
		}
		OrganizationMember membership = jdbc.query(
				"""
						select id, organization_id, profile_id, role::text as role, created_at
						from organization_members
						where organization_id = ?
						order by created_at
						limit 1
						""",
				rs -> rs.next() ? member(rs) : null,
				organizationId);
		if (membership == null) {
			return Optional.empty();
		}
		Profile profile = jdbc.query(
				"select id, display_name, created_at from profiles where id = ?",
				rs -> rs.next() ? profile(rs) : null,
				membership.profileId());
		List<Branch> branches = jdbc.query(
				"select id, organization_id, name, created_at from branches where organization_id = ? order by created_at",
				this::branch,
				organizationId);
		return Optional.of(new OwnerWorkspace(profile, organization, membership, branches));
	}

	@Override
	public OwnerWorkspace createOwnerBusiness(UUID profileId, String displayName, String organizationName, String branchName) {
		jdbc.update(
				"""
						insert into profiles (id, display_name)
						values (?, ?)
						on conflict (id) do update
						  set display_name = excluded.display_name,
						      updated_at = now()
						""",
				profileId,
				displayName);
		UUID organizationId = UUID.randomUUID();
		jdbc.update(
				"insert into organizations (id, name) values (?, ?)",
				organizationId,
				organizationName);
		jdbc.update(
				"""
						insert into organization_members (organization_id, profile_id, role)
						values (?, ?, cast(? as member_role))
						""",
				organizationId,
				profileId,
				MemberRole.OWNER.dbValue());
		jdbc.update(
				"insert into branches (organization_id, name) values (?, ?)",
				organizationId,
				branchName);
		return findByProfileId(profileId).orElseThrow(() -> IdentityException.notFound("No se pudo crear el local."));
	}

	@Override
	public Profile updateDisplayName(UUID profileId, String displayName) {
		jdbc.update("update profiles set display_name = ?, updated_at = now() where id = ?", displayName, profileId);
		return jdbc.query(
				"select id, display_name, created_at from profiles where id = ?",
				rs -> rs.next() ? profile(rs) : null,
				profileId);
	}

	@Override
	public void renameOrganization(UUID organizationId, String name) {
		jdbc.update("update organizations set name = ?, updated_at = now() where id = ?", name, organizationId);
	}

	@Override
	public void deleteBusiness(UUID organizationId, UUID profileId) {
		jdbc.update("delete from employees where organization_id = ?", organizationId);
		jdbc.update("delete from branches where organization_id = ?", organizationId);
		jdbc.update("delete from organization_members where organization_id = ?", organizationId);
		jdbc.update("delete from organizations where id = ?", organizationId);
		jdbc.update("delete from profiles where id = ?", profileId);
	}

	private Profile profile(ResultSet rs) throws SQLException {
		return new Profile(
				rs.getObject("id", UUID.class),
				rs.getString("display_name"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	private Organization organization(ResultSet rs) throws SQLException {
		return new Organization(
				rs.getObject("id", UUID.class),
				rs.getString("name"),
				rs.getString("sms_phone"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	private OrganizationMember member(ResultSet rs) throws SQLException {
		return new OrganizationMember(
				rs.getObject("id", UUID.class),
				rs.getObject("organization_id", UUID.class),
				rs.getObject("profile_id", UUID.class),
				MemberRole.fromDb(rs.getString("role")),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	private Branch branch(ResultSet rs, int ignored) throws SQLException {
		return new Branch(
				rs.getObject("id", UUID.class),
				rs.getObject("organization_id", UUID.class),
				rs.getString("name"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}
}
