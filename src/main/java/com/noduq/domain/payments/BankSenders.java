package com.noduq.domain.payments;

import java.util.Locale;
import java.util.Set;

/**
 * Which senders NODUQ trusts, straight out of {@code payments/bancolombia-senders.json}.
 *
 * <p>For SMS the sender is the whole filter: the short code {@code 85540} is the QR receipt.
 * The {@code requireQrHintInBody} rule is an email rule, because anybody can write to a
 * mailbox while a short code cannot be forged over the carrier.
 */
public record BankSenders(
		Set<String> qrPaymentSmsSenders,
		Set<String> knownSmsSenders,
		Set<String> qrPaymentEmailFrom,
		Set<String> ignoredEmailFrom,
		boolean requireQrHintInBody) {

	public boolean isQrPaymentSms(String sender) {
		return matchesShortCode(sender, qrPaymentSmsSenders);
	}

	public boolean isKnownSms(String sender) {
		return matchesShortCode(sender, knownSmsSenders);
	}

	public boolean isQrPaymentEmail(String from) {
		String address = emailAddress(from);
		return address != null && !ignoredEmailFrom.contains(address) && qrPaymentEmailFrom.contains(address);
	}

	/** The bank's own mailbox is not enough: the body has to mention the QR. */
	public boolean looksLikeQrPayment(String body) {
		if (!requireQrHintInBody) {
			return true;
		}
		if (body == null) {
			return false;
		}
		return body.toLowerCase(Locale.ROOT).contains("qr");
	}

	private static boolean matchesShortCode(String sender, Set<String> codes) {
		if (sender == null) {
			return false;
		}
		String digits = sender.replaceAll("\\D", "");
		if (digits.isEmpty()) {
			return false;
		}
		if (codes.contains(digits)) {
			return true;
		}
		// Some carriers hand the short code over with the country code glued in front.
		return digits.startsWith("57") && codes.contains(digits.substring(2));
	}

	/** Pulls {@code someone@example.com} out of {@code "Name" <someone@example.com>}. */
	private static String emailAddress(String from) {
		if (from == null || from.isBlank()) {
			return null;
		}
		String value = from.trim();
		int open = value.lastIndexOf('<');
		int close = value.lastIndexOf('>');
		if (open >= 0 && close > open) {
			value = value.substring(open + 1, close);
		}
		value = value.trim().toLowerCase(Locale.ROOT);
		return value.contains("@") ? value : null;
	}
}
