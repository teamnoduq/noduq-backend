package com.noduq.domain.identity;

public class IdentityException extends RuntimeException {

	private final String code;
	private final int httpStatus;

	private IdentityException(String code, int httpStatus, String message) {
		super(message);
		this.code = code;
		this.httpStatus = httpStatus;
	}

	public String code() {
		return code;
	}

	public int httpStatus() {
		return httpStatus;
	}

	public static IdentityException validation(String code, String message) {
		return new IdentityException(code, 400, message);
	}

	public static IdentityException unauthorized(String message) {
		return new IdentityException("UNAUTHORIZED", 401, message);
	}

	public static IdentityException forbidden(String message) {
		return new IdentityException("FORBIDDEN", 403, message);
	}

	public static IdentityException notFound(String message) {
		return new IdentityException("NOT_FOUND", 404, message);
	}

	public static IdentityException conflict(String code, String message) {
		return new IdentityException(code, 409, message);
	}

	public static IdentityException locked(String message) {
		return new IdentityException("TOO_MANY_ATTEMPTS", 429, message);
	}

	public static IdentityException notProvisioned() {
		return new IdentityException(
				"NOT_PROVISIONED",
				404,
				"Esta cuenta todavía no tiene organización. Hay que crear el comercio.");
	}
}
