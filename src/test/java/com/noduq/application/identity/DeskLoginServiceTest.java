package com.noduq.application.identity;

import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.port.AuthUserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeskLoginServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-29T04:10:00Z");

	@Mock
	private AuthUserDirectory authUsers;

	@Mock
	private EmployeeSessionService sessions;

	private DeskLoginService desks;

	@BeforeEach
	void setUp() {
		desks = new DeskLoginService(
				new DeskTicketStore(),
				authUsers,
				sessions,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void pollStaysPendingUntilThePhoneClaims() {
		var issued = desks.issue();

		DeskLoginService.Snapshot snapshot = desks.poll(issued.id(), issued.secret());

		assertEquals("pending", snapshot.status());
		assertEquals(NOW.plus(DeskLoginService.LIFE), snapshot.expiresAt());
		assertTrue(issued.payload().startsWith("noduq://desk/"));
	}

	@Test
	void wrongSecretLooksExpired() {
		var issued = desks.issue();

		assertEquals("expired", desks.poll(issued.id(), "nope").status());
	}

	@Test
	void ownerClaimHandsTheWebAMagicLinkHash() {
		var issued = desks.issue();
		UUID ownerId = UUID.randomUUID();
		when(authUsers.emailOf(ownerId)).thenReturn("dueño@noduq.app");
		when(authUsers.issueEmailLoginHash("dueño@noduq.app")).thenReturn("hash-1");

		desks.claimByOwner(issued.id(), ownerId);
		DeskLoginService.Snapshot snapshot = desks.poll(issued.id(), issued.secret());

		assertEquals("claimed", snapshot.status());
		assertEquals("owner", snapshot.kind());
		assertEquals("hash-1", snapshot.hashedToken());
		verify(authUsers).issueEmailLoginHash("dueño@noduq.app");
	}

	@Test
	void secondClaimIsRejected() {
		var issued = desks.issue();
		UUID ownerId = UUID.randomUUID();
		when(authUsers.emailOf(ownerId)).thenReturn("dueño@noduq.app");
		when(authUsers.issueEmailLoginHash("dueño@noduq.app")).thenReturn("hash-1");
		desks.claimByOwner(issued.id(), ownerId);

		IdentityException ex = assertThrows(IdentityException.class, () -> desks.claimByOwner(issued.id(), ownerId));
		assertEquals("DESK_CLAIMED", ex.code());
	}

	@Test
	void missingTicketLooksGone() {
		IdentityException ex = assertThrows(
				IdentityException.class,
				() -> desks.claimByEmployee(UUID.randomUUID(), UUID.randomUUID()));
		assertEquals("NOT_FOUND", ex.code());
	}

	@Test
	void pendingSnapshotHidesCredentials() {
		var issued = desks.issue();
		DeskLoginService.Snapshot snapshot = desks.poll(issued.id(), issued.secret());
		assertNull(snapshot.hashedToken());
		assertNull(snapshot.kind());
	}
}
