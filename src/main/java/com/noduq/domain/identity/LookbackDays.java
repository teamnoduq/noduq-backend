package com.noduq.domain.identity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Calendar days the employee may see, counted in Bogotá from the start of today. */
public record LookbackDays(int days) {

	public static final ZoneId ZONE = ZoneId.of("America/Bogota");

	public static LookbackDays of(Integer raw) {
		int days = raw == null ? 1 : raw;
		if (days != 1 && days != 3 && days != 7) {
			throw IdentityException.validation(
					"LOOKBACK_INVALID", "Los avisos del empleado son de hoy, 3 días o 7 días.");
		}
		return new LookbackDays(days);
	}

	public Instant floor(Instant now) {
		LocalDate today = LocalDate.ofInstant(now, ZONE);
		return today.minusDays(days - 1L).atStartOfDay(ZONE).toInstant();
	}
}
