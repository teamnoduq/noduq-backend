package com.noduq.application.payments;

import com.noduq.application.identity.OwnerAccountService;
import com.noduq.domain.payments.PaymentBucket;
import com.noduq.domain.payments.port.PaymentNoticeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentStatsService {

	static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

	private static final String[] MONTHS = {
			"", "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
			"Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
	};
	private static final String[] SHORT = {
			"", "ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"
	};

	private final OwnerAccountService owners;
	private final PaymentNoticeRepository notices;

	public PaymentStatsService(OwnerAccountService owners, PaymentNoticeRepository notices) {
		this.owners = owners;
		this.notices = notices;
	}

	@Transactional(readOnly = true)
	public Report forOwner(UUID profileId, int year, Integer month) {
		if (year < 2000 || year > 2100 || (month != null && (month < 1 || month > 12))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Periodo inválido");
		}
		UUID organizationId = owners.requireWorkspace(profileId).organization().id();
		LocalDate today = LocalDate.now(BOGOTA);
		Window window = window(year, month, today);
		Window previous = window.previous();
		List<PaymentBucket> current = notices.buckets(organizationId, window.since(), window.until(), month == null);
		List<PaymentBucket> prior = notices.buckets(organizationId, previous.since(), previous.until(), month == null);
		long payers = notices.distinctPayers(organizationId, window.since(), window.until());
		return build(month == null ? "month" : "day", year, month, window, current, prior, payers);
	}

	static Report build(
			String grain,
			int year,
			Integer month,
			Window window,
			List<PaymentBucket> current,
			List<PaymentBucket> prior,
			long uniquePayers) {
		Map<LocalDate, PaymentBucket> byStart = new HashMap<>();
		for (PaymentBucket bucket : current) {
			if (bucket.start() != null) {
				byStart.put(bucket.start(), bucket);
			}
		}
		List<Point> points = new ArrayList<>();
		if ("day".equals(grain)) {
			for (LocalDate day = window.start(); !day.isAfter(window.endInclusive()); day = day.plusDays(1)) {
				PaymentBucket found = byStart.get(day);
				long count = found == null ? 0 : found.count();
				BigDecimal amount = found == null ? BigDecimal.ZERO : found.amount();
				points.add(new Point(
						day.toString(),
						Integer.toString(day.getDayOfMonth()),
						day.getDayOfMonth() + " " + SHORT[day.getMonthValue()],
						count,
						amount));
			}
		} else {
			YearMonth cursor = YearMonth.from(window.start());
			YearMonth last = YearMonth.from(window.endInclusive());
			while (!cursor.isAfter(last)) {
				PaymentBucket found = byStart.get(cursor.atDay(1));
				long count = found == null ? 0 : found.count();
				BigDecimal amount = found == null ? BigDecimal.ZERO : found.amount();
				points.add(new Point(
						cursor.toString(),
						capitalize(SHORT[cursor.getMonthValue()]),
						MONTHS[cursor.getMonthValue()],
						count,
						amount));
				cursor = cursor.plusMonths(1);
			}
		}
		long count = 0;
		BigDecimal amount = BigDecimal.ZERO;
		Point peak = null;
		Point low = null;
		int withSales = 0;
		for (Point point : points) {
			count += point.count();
			amount = amount.add(point.amount());
			if (point.count() <= 0) {
				continue;
			}
			withSales++;
			if (peak == null || point.amount().compareTo(peak.amount()) > 0) {
				peak = point;
			}
			if (low == null || point.amount().compareTo(low.amount()) < 0) {
				low = point;
			}
		}
		long previousCount = 0;
		BigDecimal previousAmount = BigDecimal.ZERO;
		for (PaymentBucket bucket : prior) {
			previousCount += bucket.count();
			if (bucket.amount() != null) {
				previousAmount = previousAmount.add(bucket.amount());
			}
		}
		int buckets = points.size();
		return new Report(
				grain,
				year,
				month,
				buckets,
				count,
				amount,
				uniquePayers,
				average(count, buckets),
				average(amount, buckets),
				count == 0 ? BigDecimal.ZERO : amount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP),
				previousCount,
				previousAmount,
				changePercent(count, previousCount),
				changePercent(amount, previousAmount),
				peak,
				low,
				withSales,
				Math.max(0, buckets - withSales),
				points);
	}

	static Window window(int year, Integer month, LocalDate today) {
		if (month == null) {
			LocalDate start = LocalDate.of(year, 1, 1);
			LocalDate end = year == today.getYear()
					? today
					: year > today.getYear() ? start.minusDays(1) : LocalDate.of(year, 12, 31);
			if (end.isBefore(start)) {
				end = start.minusDays(1);
			}
			return new Window(start, end, true);
		}
		YearMonth period = YearMonth.of(year, month);
		LocalDate start = period.atDay(1);
		LocalDate end;
		if (period.equals(YearMonth.from(today))) {
			end = today;
		} else if (period.isAfter(YearMonth.from(today))) {
			end = start.minusDays(1);
		} else {
			end = period.atEndOfMonth();
		}
		return new Window(start, end, false);
	}

	static Double changePercent(long current, long previous) {
		if (previous <= 0) {
			return null;
		}
		return Math.round((current - previous) * 1000.0 / previous) / 10.0;
	}

	static Double changePercent(BigDecimal current, BigDecimal previous) {
		if (previous == null || previous.signum() <= 0) {
			return null;
		}
		BigDecimal delta = current.subtract(previous).multiply(BigDecimal.valueOf(100));
		return delta.divide(previous, 1, RoundingMode.HALF_UP).doubleValue();
	}

	private static BigDecimal average(long total, int buckets) {
		if (buckets <= 0) {
			return BigDecimal.ZERO;
		}
		return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(buckets), 2, RoundingMode.HALF_UP);
	}

	private static BigDecimal average(BigDecimal total, int buckets) {
		if (buckets <= 0) {
			return BigDecimal.ZERO;
		}
		return total.divide(BigDecimal.valueOf(buckets), 2, RoundingMode.HALF_UP);
	}

	private static String capitalize(String word) {
		if (word.isEmpty()) {
			return word;
		}
		return Character.toUpperCase(word.charAt(0)) + word.substring(1);
	}

	public record Window(LocalDate start, LocalDate endInclusive, boolean year) {
		Instant since() {
			return start.atStartOfDay(BOGOTA).toInstant();
		}

		Instant until() {
			LocalDate end = endInclusive.isBefore(start) ? start : endInclusive.plusDays(1);
			return end.atStartOfDay(BOGOTA).toInstant();
		}

		Window previous() {
			if (year) {
				return new Window(start.minusYears(1), endInclusive.minusYears(1), true);
			}
			return new Window(start.minusMonths(1), endInclusive.minusMonths(1), false);
		}
	}

	public record Point(String key, String label, String detail, long count, BigDecimal amount) {
	}

	public record Report(
			String grain,
			int year,
			Integer month,
			int bucketCount,
			long count,
			BigDecimal amount,
			long uniquePayers,
			BigDecimal averageCount,
			BigDecimal averageAmount,
			BigDecimal averagePerPayment,
			long previousCount,
			BigDecimal previousAmount,
			Double countChangePercent,
			Double amountChangePercent,
			Point peak,
			Point low,
			int bucketsWithSales,
			int bucketsEmpty,
			List<Point> points) {
	}
}
