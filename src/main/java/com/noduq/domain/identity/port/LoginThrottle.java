package com.noduq.domain.identity.port;

public interface LoginThrottle {

	void guard(String key);

	void recordFailure(String key);

	void clear(String key);
}
