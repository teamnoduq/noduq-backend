package com.noduq.application.payments;

import com.noduq.domain.payments.PaymentBucket;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PaymentStatsServiceTest {

	@Test
	void currentMonthStopsOnTodayAndKeepsTheEmptyDay() {
		LocalDate today = LocalDate.of(2026, 9, 3);
		PaymentStatsService.Window window = PaymentStatsService.window(2026, 9, today);
		PaymentStatsService.Report report = PaymentStatsService.build(
				"day",
				2026,
				9,
				window,
				List.of(
						new PaymentBucket(LocalDate.of(2026, 9, 1), 2, new BigDecimal("10000")),
						new PaymentBucket(LocalDate.of(2026, 9, 3), 1, new BigDecimal("4000"))),
				List.of(new PaymentBucket(LocalDate.of(2026, 8, 1), 1, new BigDecimal("2000"))),
				2);

		assertEquals(3, report.bucketCount());
		assertEquals(3, report.count());
		assertEquals(1, report.bucketsEmpty());
		assertEquals(2, report.bucketsWithSales());
		assertEquals("3 sep", report.points().get(2).detail());
		assertEquals(0, report.points().get(1).count());
		assertEquals(200.0, report.countChangePercent());
	}

	@Test
	void aQuietPreviousPeriodHasNoPercent() {
		assertNull(PaymentStatsService.changePercent(10, 0));
	}

	@Test
	void aPastYearIncludesEveryMonth() {
		PaymentStatsService.Window window = PaymentStatsService.window(2025, null, LocalDate.of(2026, 9, 28));
		assertEquals(LocalDate.of(2025, 1, 1), window.start());
		assertEquals(LocalDate.of(2025, 12, 31), window.endInclusive());
		assertEquals(LocalDate.of(2024, 12, 31), window.previous().endInclusive());
	}
}
