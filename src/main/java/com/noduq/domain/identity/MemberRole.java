package com.noduq.domain.identity;

public enum MemberRole {
	OWNER,
	ADMIN;

	public String dbValue() {
		return name().toLowerCase();
	}

	public static MemberRole fromDb(String value) {
		return MemberRole.valueOf(value.toUpperCase());
	}
}
