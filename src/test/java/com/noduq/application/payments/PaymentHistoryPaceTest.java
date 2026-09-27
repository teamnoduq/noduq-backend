package com.noduq.application.payments;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentHistoryPaceTest {

	@Test
	void cooldownWaitsOutTheRestOfTheMinute() {
		Duration atFiveSeconds = PaymentHistoryService.quotaCooldown(5_000L);
		assertEquals(Duration.ofMillis(56_500L), atFiveSeconds);

		Duration atZero = PaymentHistoryService.quotaCooldown(120_000L);
		assertEquals(Duration.ofMillis(61_500L), atZero);
	}

	@Test
	void cooldownNeverGoesBackwardsInsideAMinute() {
		Duration early = PaymentHistoryService.quotaCooldown(1_000L);
		Duration late = PaymentHistoryService.quotaCooldown(50_000L);
		assertTrue(early.compareTo(late) > 0);
		assertTrue(late.toMillis() >= 1_500L);
	}
}
