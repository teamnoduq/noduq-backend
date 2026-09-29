package com.noduq.domain.identity.port;

import java.util.UUID;

public interface AuthUserDirectory {

	void deleteAuthUser(UUID authUserId);

	String emailOf(UUID authUserId);

	/** One-use hash the web exchanges with Supabase Auth. Does not send mail. */
	String issueEmailLoginHash(String email);
}
