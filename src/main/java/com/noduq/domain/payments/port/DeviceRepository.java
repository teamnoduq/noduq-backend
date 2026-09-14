package com.noduq.domain.payments.port;

import com.noduq.domain.payments.Device;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DeviceRepository {

	/**
	 * Saves the device, or moves the token over when the same phone signs in as somebody
	 * else. Firebase hands the same token back to a reinstalled app, so the token is the key.
	 */
	Device register(Device device);

	List<Device> listByOrganization(UUID organizationId);

	/** Scoped to the organization so knowing a token is not enough to unregister a phone. */
	void deleteByPushToken(UUID organizationId, String pushToken);

	void deleteByPushTokens(Collection<String> pushTokens);
}
