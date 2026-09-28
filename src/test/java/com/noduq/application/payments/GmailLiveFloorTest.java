package com.noduq.application.payments;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GmailLiveFloorTest {

	@Test
	void aMailboxThatHasNeverBeenReadStartsNow() {
		Instant now = Instant.parse("2026-09-28T22:00:00Z");

		assertEquals(now, GmailLinkService.liveFloor(null, now));
	}

	@Test
	void aRunningMailboxOverlapsAFewMinutes() {
		Instant now = Instant.parse("2026-09-28T22:00:00Z");
		Instant last = now.minus(Duration.ofSeconds(45));

		assertEquals(last.minus(Duration.ofMinutes(5)), GmailLinkService.liveFloor(last, now));
	}
}
