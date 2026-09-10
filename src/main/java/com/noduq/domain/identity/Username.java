package com.noduq.domain.identity;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class Username {

	private static final Pattern PATTERN = Pattern.compile("^[a-z0-9_]{3,32}$");

	private final String value;

	private Username(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static Username parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw IdentityException.validation("USERNAME_INVALID", "El usuario es obligatorio.");
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		if (!PATTERN.matcher(normalized).matches()) {
			throw IdentityException.validation(
					"USERNAME_INVALID",
					"El usuario debe tener entre 3 y 32 caracteres: letras, números o guion bajo.");
		}
		return new Username(normalized);
	}

	public static Username fromDisplayName(String displayName, Set<String> takenInOrganization) {
		String base = slug(displayName);
		if (base.length() < 3) {
			base = "empleado";
		}
		if (base.length() > 28) {
			base = base.substring(0, 28);
		}
		String candidate = base;
		int suffix = 2;
		while (takenInOrganization.contains(candidate)) {
			candidate = base + suffix;
			suffix++;
		}
		return parse(candidate);
	}

	private static String slug(String displayName) {
		if (displayName == null) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (char c : displayName.toLowerCase(Locale.ROOT).toCharArray()) {
			if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
				out.append(c);
			} else if (c == ' ' || c == '-' || c == '_') {
				if (!out.isEmpty() && out.charAt(out.length() - 1) != '_') {
					out.append('_');
				}
			}
		}
		while (!out.isEmpty() && out.charAt(out.length() - 1) == '_') {
			out.deleteCharAt(out.length() - 1);
		}
		return out.toString();
	}
}
