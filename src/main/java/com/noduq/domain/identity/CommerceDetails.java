package com.noduq.domain.identity;

public final class CommerceDetails {

	private CommerceDetails() {
	}

	public static String merchantLast4(String raw) {
		if (raw == null || raw.isBlank()) {
			throw IdentityException.validation(
					"MERCHANT_LAST4_INVALID",
					"Escribe los 4 dígitos de tu cuenta, tal como salen en el correo de Bancolombia.");
		}
		String digits = raw.replaceAll("\\D", "");
		if (digits.length() != 4) {
			throw IdentityException.validation(
					"MERCHANT_LAST4_INVALID",
					"Solo los 4 dígitos que ves en el correo, por ejemplo *8186.");
		}
		return digits;
	}

	public static String smsPhone(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String compact = raw.replaceAll("[\\s\\-().]", "");
		if (compact.startsWith("00")) {
			compact = "+" + compact.substring(2);
		}
		String national;
		if (compact.startsWith("+57")) {
			national = compact.substring(3);
		} else if (compact.startsWith("57") && compact.length() == 12) {
			national = compact.substring(2);
		} else {
			national = compact;
		}
		if (!national.matches("3\\d{9}")) {
			throw IdentityException.validation(
					"SMS_PHONE_INVALID",
					"Escribe el celular colombiano donde llega el SMS 85540, 10 dígitos.");
		}
		return "+57" + national;
	}
}
