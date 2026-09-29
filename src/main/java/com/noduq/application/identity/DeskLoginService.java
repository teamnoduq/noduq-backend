package com.noduq.application.identity;

import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.port.AuthUserDirectory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class DeskLoginService {

	static final Duration LIFE = Duration.ofSeconds(30);
	private static final Duration CLAIM_GRACE = Duration.ofSeconds(15);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final DeskTicketStore tickets;
	private final AuthUserDirectory authUsers;
	private final EmployeeSessionService sessions;
	private final Clock clock;

	public DeskLoginService(
			DeskTicketStore tickets,
			AuthUserDirectory authUsers,
			EmployeeSessionService sessions) {
		this(tickets, authUsers, sessions, Clock.systemUTC());
	}

	DeskLoginService(
			DeskTicketStore tickets,
			AuthUserDirectory authUsers,
			EmployeeSessionService sessions,
			Clock clock) {
		this.tickets = tickets;
		this.authUsers = authUsers;
		this.sessions = sessions;
		this.clock = clock;
	}

	public Issued issue() {
		Instant now = Instant.now(clock);
		tickets.evictExpired(now);
		if (tickets.size() >= 8_000) {
			throw IdentityException.locked("Espera un momento y vuelve a intentar.");
		}
		UUID id = UUID.randomUUID();
		String secret = newSecret();
		Instant expiresAt = now.plus(LIFE);
		tickets.put(new DeskTicket(id, secret, expiresAt, null, null, null), now);
		return new Issued(id, secret, "noduq://desk/" + id, expiresAt);
	}

	public Snapshot poll(UUID id, String secret) {
		Instant now = Instant.now(clock);
		DeskTicket ticket = tickets.find(id).orElse(null);
		if (ticket == null || !ticket.secret().equals(secret)) {
			return Snapshot.expired();
		}
		if (ticket.expired(now)) {
			tickets.remove(id);
			return Snapshot.expired();
		}
		if (!ticket.claimed()) {
			return Snapshot.pending(ticket.expiresAt());
		}
		return Snapshot.claimed(ticket);
	}

	public void claimByOwner(UUID ticketId, UUID ownerUserId) {
		DeskTicket pending = pending(ticketId);
		String email = authUsers.emailOf(ownerUserId);
		String hash = authUsers.issueEmailLoginHash(email);
		Instant keepUntil = Instant.now(clock).plus(CLAIM_GRACE);
		if (keepUntil.isBefore(pending.expiresAt())) {
			keepUntil = pending.expiresAt();
		}
		tickets.replace(pending.claimedOwner(hash, keepUntil));
	}

	public void claimByEmployee(UUID ticketId, UUID employeeId) {
		DeskTicket pending = pending(ticketId);
		EmployeeSessionService.OpenedSession opened = sessions.openFor(employeeId);
		Instant keepUntil = Instant.now(clock).plus(CLAIM_GRACE);
		if (keepUntil.isBefore(pending.expiresAt())) {
			keepUntil = pending.expiresAt();
		}
		tickets.replace(pending.claimedEmployee(opened, keepUntil));
	}

	private DeskTicket pending(UUID ticketId) {
		Instant now = Instant.now(clock);
		DeskTicket ticket = tickets.find(ticketId).orElse(null);
		if (ticket == null || ticket.expired(now)) {
			if (ticket != null) {
				tickets.remove(ticketId);
			}
			throw IdentityException.notFound("Ese código ya venció. Espera el siguiente.");
		}
		if (ticket.claimed()) {
			throw IdentityException.conflict("DESK_CLAIMED", "Ese código ya se usó.");
		}
		return ticket;
	}

	private static String newSecret() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	public record Issued(UUID id, String secret, String payload, Instant expiresAt) {
	}

	public record Snapshot(
			String status,
			Instant expiresAt,
			String kind,
			String hashedToken,
			EmployeeSessionService.OpenedSession employeeSession) {

		static Snapshot pending(Instant expiresAt) {
			return new Snapshot("pending", expiresAt, null, null, null);
		}

		static Snapshot expired() {
			return new Snapshot("expired", null, null, null, null);
		}

		static Snapshot claimed(DeskTicket ticket) {
			return new Snapshot(
					"claimed",
					ticket.expiresAt(),
					ticket.kind(),
					ticket.hashedToken(),
					ticket.employeeSession());
		}
	}
}
