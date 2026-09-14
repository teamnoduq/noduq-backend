package com.noduq.domain.payments.port;

import com.noduq.domain.payments.PushMessage;

import java.util.Collection;
import java.util.Set;

public interface PushSender {

	/** False when no credentials are configured, so callers can skip the work and say so. */
	boolean enabled();

	/**
	 * @return the tokens the provider rejected for good, so the caller can forget those phones
	 */
	Set<String> send(Collection<String> pushTokens, PushMessage message);
}
