package com.noduq.domain.identity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LookbackDaysTest {

	@Test
	void todayStartsAtBogotaMidnight() {
		Instant noonUtc = Instant.parse("2026-09-14T17:00:00Z");
		Instant floor = LookbackDays.of(1).floor(noonUtc);
		assertEquals(
				LocalDate.of(2026, 9, 14).atStartOfDay(LookbackDays.ZONE).toInstant(),
				floor);
	}

	@Test
	void sevenDaysReachBackSixCalendarDays() {
		Instant now = Instant.parse("2026-09-14T12:00:00Z");
		Instant floor = LookbackDays.of(7).floor(now);
		assertEquals(
				LocalDate.of(2026, 9, 8).atStartOfDay(LookbackDays.ZONE).toInstant(),
				floor);
	}

	@Test
	void rejectsOtherWindows() {
		IdentityException error = assertThrows(IdentityException.class, () -> LookbackDays.of(2));
		assertEquals("LOOKBACK_INVALID", error.code());
	}
}
