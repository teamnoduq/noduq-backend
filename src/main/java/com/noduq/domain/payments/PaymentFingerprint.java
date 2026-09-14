package com.noduq.domain.payments;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
	 * Same wording on SMS and email must land on the same row, so the sender is not part of
	 * the hash: 85540 and the bank mailbox are two doors into one payment.
	 */
	public static String of(String sender, String body) {
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
