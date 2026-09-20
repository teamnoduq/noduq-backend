package com.noduq.domain.payments;

import java.util.UUID;

/** Gmail linked during onboarding, before the shop exists. */
public record PendingGmail(UUID profileId, String gmailAddress, String refreshToken, String historyId) {
}
