package com.noduq.domain.payments;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the payer, the amount and the moment out of a Bancolombia QR receipt SMS.
 *
 * <p>The bank rewords these messages without warning, so the parser is deliberately
 * forgiving and every field is optional. A notice we cannot read is still a payment that
 * arrived: the caller stores it and shows it, rather than dropping it on the floor.
 */
public final class SmsPaymentParser {

	public static final ZoneId COLOMBIA = ZoneId.of("America/Bogota");

	/** Beyond this gap we assume we misread the date, not that the bank is time travelling. */
	private static final Duration PLAUSIBLE_GAP = Duration.ofDays(7);

	private static final Pattern PESO_AMOUNT = Pattern.compile("\\$\\s*(\\d[\\d.,]*)");

	private static final Pattern LABELLED_AMOUNT = Pattern.compile(
			"(?i)\\b(?:COP|por|valor|monto)\\b\\s*\\$?\\s*(\\d[\\d.,]*)");

	private static final Pattern PAYER_MARKER = Pattern.compile("(?i)\\bde:?\\s+");

	private static final Pattern NAME_AT_START = Pattern.compile("^([\\p{L}][\\p{L}\\s.'\\-]{2,60})");

	// The bank writes "el 12/09/2026 a las 15:11", so the gap between date and time is words,
	// not a single separator.
	private static final Pattern DATE_TIME_FULL_YEAR = Pattern.compile(
			"(\\d{1,2})/(\\d{1,2})/(\\d{4})\\D{0,12}(\\d{1,2}):(\\d{2})");

	private static final Pattern DATE_TIME_SHORT_YEAR = Pattern.compile(
			"(\\d{1,2})/(\\d{1,2})/(\\d{2})\\D{0,12}(\\d{1,2}):(\\d{2})");

	private static final Pattern DATE_TIME_NO_YEAR = Pattern.compile(
			"(\\d{1,2})/(\\d{1,2})\\D{1,12}(\\d{1,2}):(\\d{2})");

	private static final Pattern TIME_ONLY = Pattern.compile("\\b(\\d{1,2}):(\\d{2})\\b");

	/** "en tu cuenta *8186": the only digits of the account we ever look at, and never keep. */
	private static final Pattern ACCOUNT_LAST4 = Pattern.compile("(?i)cuenta\\s*\\*?\\s*(\\d{4})\\b");

	/** Words that follow the payer's name and mark where it ends. */
	private static final List<String> NAME_STOPS = List.of(
			" el ", " a las ", " a la ", " en ", " por ", " con ", " para ", " hora", " fecha",
			" cta", " cuenta", " ref", " valor", " monto", " id ", " tel");

	/** After "de", these mean the sentence is talking about something other than a person. */
	private static final Set<String> NOT_A_PAYER = Set.of(
			"bancolombia", "tu", "su", "sus", "tus", "la", "el", "los", "las", "un", "una", "unos",
			"cuenta", "cuentas", "ahorro", "ahorros", "corriente", "nequi", "pago", "pagos", "qr",
			"transferencia", "transaccion", "transacción", "cliente", "seguridad", "dinero", "app",
			"forma", "manera", "esta", "este", "nuestro", "nuestra");

	private SmsPaymentParser() {
	}

	public record Reading(String payerName, BigDecimal amount, Instant occurredAt) {

		public boolean anything() {
			return payerName != null || amount != null;
		}
	}

	public static Reading read(String body, Instant receivedAt) {
		return read(body, receivedAt, COLOMBIA);
	}

	public static Reading read(String body, Instant receivedAt, ZoneId zone) {
		String text = flatten(body);
		if (text.isEmpty()) {
			return new Reading(null, null, null);
		}
		return new Reading(payer(text), amount(text), occurredAt(text, receivedAt, zone));
	}

	/** Collapses the whitespace so the patterns do not have to care about line breaks. */
	public static String flatten(String body) {
		return body == null ? "" : body.replaceAll("\\s+", " ").trim();
	}

	/**
	 * Replaces every run of three or more digits, so a message we failed to parse can be
	 * kept for debugging without carrying account numbers around.
	 */
	public static String maskDigits(String body) {
		return flatten(body).replaceAll("\\d{3,}", "###");
	}

	/**
	 * The last four digits of the account the money landed in, used only to confirm the
	 * notice belongs to this shop. Never stored.
	 */
	public static String accountLast4(String body) {
		Matcher matcher = ACCOUNT_LAST4.matcher(flatten(body));
		return matcher.find() ? matcher.group(1) : null;
	}

	static BigDecimal amount(String text) {
		BigDecimal fromPesoSign = firstMatch(PESO_AMOUNT, text);
		return fromPesoSign != null ? fromPesoSign : firstMatch(LABELLED_AMOUNT, text);
	}

	private static BigDecimal firstMatch(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			BigDecimal parsed = number(matcher.group(1));
			if (parsed != null && parsed.signum() > 0) {
				return parsed;
			}
		}
		return null;
	}

	/**
	 * Pesos are written {@code 48.000} and cents {@code 48,50}. The rule that separates
	 * them: a trailing group of one or two digits is a fraction, three digits is a
	 * thousands group.
	 */
	static BigDecimal number(String raw) {
		String value = raw.trim().replaceAll("[.,]$", "");
		if (value.isEmpty()) {
			return null;
		}
		int cut = Math.max(value.lastIndexOf('.'), value.lastIndexOf(','));
		int tailLength = cut < 0 ? 0 : value.length() - cut - 1;
		String integerDigits;
		String fractionDigits = null;
		if (cut >= 0 && tailLength >= 1 && tailLength <= 2) {
			integerDigits = value.substring(0, cut).replaceAll("\\D", "");
			fractionDigits = value.substring(cut + 1).replaceAll("\\D", "");
		} else {
			integerDigits = value.replaceAll("\\D", "");
		}
		if (integerDigits.isEmpty()) {
			return null;
		}
		BigDecimal amount = new BigDecimal(integerDigits);
		if (fractionDigits != null && !fractionDigits.isEmpty()) {
			amount = amount.add(new BigDecimal(fractionDigits).movePointLeft(fractionDigits.length()));
		}
		return amount.setScale(2, RoundingMode.HALF_UP);
	}

	/**
	 * Walks every "de" in the sentence. "recibiste $30.000 de tu cuenta de ahorros de PEDRO
	 * GOMEZ" has three of them and only the last one introduces a person, so a single match
	 * is not enough.
	 */
	static String payer(String text) {
		Matcher marker = PAYER_MARKER.matcher(text);
		// Resuming right after the marker, rather than after the rejected name, keeps the
		// "de" nested inside a discarded phrase reachable.
		int from = 0;
		while (marker.find(from)) {
			Matcher name = NAME_AT_START.matcher(text.substring(marker.end()));
			if (name.find()) {
				String candidate = trimName(name.group(1));
				if (candidate != null) {
					return candidate;
				}
			}
			from = marker.end();
		}
		return null;
	}

	private static String trimName(String raw) {
		// Padded on both sides so a stop word sitting at either edge is still spotted: the
		// capture often ends right after "... NARVAEZ por".
		String padded = " " + raw.replaceAll("\\s+", " ").trim() + " ";
		String lower = padded.toLowerCase(Locale.ROOT);
		int end = padded.length();
		for (String stop : NAME_STOPS) {
			int at = lower.indexOf(stop);
			if (at > 0 && at < end) {
				end = at;
			}
		}
		String name = padded.substring(0, end).trim().replaceAll("[.,;:\\-']+$", "").trim();
		if (name.length() < 3) {
			return null;
		}
		String firstWord = name.split(" ")[0].toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
		if (NOT_A_PAYER.contains(firstWord)) {
			return null;
		}
		return name;
	}

	static Instant occurredAt(String text, Instant receivedAt, ZoneId zone) {
		LocalDate receivedDate = receivedAt.atZone(zone).toLocalDate();

		Matcher fullYear = DATE_TIME_FULL_YEAR.matcher(text);
		if (fullYear.find()) {
			return plausible(
					at(number(fullYear, 3), number(fullYear, 2), number(fullYear, 1),
							number(fullYear, 4), number(fullYear, 5), zone),
					receivedAt);
		}

		Matcher shortYear = DATE_TIME_SHORT_YEAR.matcher(text);
		if (shortYear.find()) {
			return plausible(
					at(2000 + number(shortYear, 3), number(shortYear, 2), number(shortYear, 1),
							number(shortYear, 4), number(shortYear, 5), zone),
					receivedAt);
		}

		Matcher noYear = DATE_TIME_NO_YEAR.matcher(text);
		if (noYear.find()) {
			return plausible(
					at(receivedDate.getYear(), number(noYear, 2), number(noYear, 1),
							number(noYear, 3), number(noYear, 4), zone),
					receivedAt);
		}

		Matcher timeOnly = TIME_ONLY.matcher(text);
		if (timeOnly.find()) {
			Instant sameDay = at(
					receivedDate.getYear(), receivedDate.getMonthValue(), receivedDate.getDayOfMonth(),
					number(timeOnly, 1), number(timeOnly, 2), zone);
			if (sameDay == null) {
				return null;
			}
			// A receipt cannot be about the future: just before midnight it belongs to yesterday.
			Instant corrected = sameDay.isAfter(receivedAt.plus(Duration.ofMinutes(5)))
					? sameDay.minus(Duration.ofDays(1))
					: sameDay;
			return plausible(corrected, receivedAt);
		}

		return null;
	}

	private static int number(Matcher matcher, int group) {
		return Integer.parseInt(matcher.group(group));
	}

	private static Instant at(int year, int month, int day, int hour, int minute, ZoneId zone) {
		try {
			return ZonedDateTime.of(LocalDate.of(year, month, day), LocalTime.of(hour, minute), zone).toInstant();
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static Instant plausible(Instant candidate, Instant receivedAt) {
		if (candidate == null) {
			return null;
		}
		return Duration.between(candidate, receivedAt).abs().compareTo(PLAUSIBLE_GAP) > 0 ? null : candidate;
	}
}
