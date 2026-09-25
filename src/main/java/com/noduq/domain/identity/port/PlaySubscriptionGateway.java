package com.noduq.domain.identity.port;

import java.util.UUID;

/** Stops a Google Play subscription from renewing. The current period can still run out. */
public interface PlaySubscriptionGateway {

	void cancelRenewal(UUID appUserId);
}
