package com.noduq.application.identity;

import java.time.Instant;
import java.util.UUID;

record DeskTicket(
		UUID id,
		String secret,
		Instant expiresAt,
		String kind,
		String hashedToken,
		EmployeeSessionService.OpenedSession employeeSession) {

	boolean expired(Instant now) {
		return !now.isBefore(expiresAt);
	}

	boolean claimed() {
		return kind != null;
	}

	DeskTicket claimedOwner(String hash, Instant keepUntil) {
		return new DeskTicket(id, secret, keepUntil, "owner", hash, null);
	}

	DeskTicket claimedEmployee(EmployeeSessionService.OpenedSession opened, Instant keepUntil) {
		return new DeskTicket(id, secret, keepUntil, "employee", null, opened);
	}
}
