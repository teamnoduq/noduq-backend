package com.noduq.domain.identity.port;

import java.util.UUID;

public interface AuthUserDirectory {

	void deleteAuthUser(UUID authUserId);
}
