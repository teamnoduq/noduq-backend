package com.noduq.domain.payments;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Identifies a message without keeping it. The bank re-sends the same SMS when the network
 * hiccups and the phone can hand us the same one twice, so the hash is what stops repeats.
 */
public final class PaymentFingerprint {

	private PaymentFingerprint() {
	}

	/**
	 * Same payer, amount and bank minute must land on one row, whether they arrived as SMS
	 * or mail. A second QR of the same pesos a few minutes later is a different payment.
	 */
	public static String of(String sender, String body) {
		return of(body, Instant.now());
	}

	public static String of(String body, Instant receivedAt) {
		SmsPaymentParser.Reading reading = SmsPaymentParser.read(body, receivedAt == null ? Instant.now() : receivedAt);
		if (reading.occurredAt() != null && (reading.payerName() != null || reading.amount() != null)) {
			String payer = reading.payerName() == null
					? ""
					: SmsPaymentParser.flatten(reading.payerName()).toLowerCase(Locale.ROOT);
			String amount = reading.amount() == null ? "" : reading.amount().toPlainString();
			long minute = reading.occurredAt().getEpochSecond() / 60;
			return sha256("v2|" + payer + "|" + amount + "|" + minute);
		}
		return ofBody(body);
	}

	public static String ofBody(String body) {
		return sha256(SmsPaymentParser.flatten(body).toLowerCase(Locale.ROOT));
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is missing from this JVM", ex);
		}
	}
}
