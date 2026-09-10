package com.noduq.domain.identity;

import java.security.SecureRandom;
import java.util.Locale;

public final class EmployeeCode {

	private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
	private static final int LENGTH = 10;

	private final String normalized;

	private EmployeeCode(String normalized) {
		this.normalized = normalized;
	}

	public String normalized() {
		return normalized;
	}

	public String display() {
		return normalized.substring(0, 5) + "-" + normalized.substring(5);
	}

	public static EmployeeCode generate(SecureRandom random) {
		char[] chars = new char[LENGTH];
		for (int i = 0; i < LENGTH; i++) {
			chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
		}
		return new EmployeeCode(new String(chars));
	}

	public static EmployeeCode parse(String raw) {
		if (raw == null) {
			return null;
		}
		String normalized = raw.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
		if (normalized.length() != LENGTH) {
			return null;
		}
		for (int i = 0; i < normalized.length(); i++) {
			if (indexOf(normalized.charAt(i)) < 0) {
				return null;
			}
		}
		return new EmployeeCode(normalized);
	}

	private static int indexOf(char c) {
		for (int i = 0; i < ALPHABET.length; i++) {
			if (ALPHABET[i] == c) {
				return i;
			}
		}
		return -1;
	}
}
