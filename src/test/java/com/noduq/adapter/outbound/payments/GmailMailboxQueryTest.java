package com.noduq.adapter.outbound.payments;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmailMailboxQueryTest {

	@Test
	void asksOnlyForMailThatNamesThisShopAndWasReceived() {
		String query = GmailMailbox.historyQuery(
				"alertasynotificaciones@notificacionesbancolombia.com",
				LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 2, 1),
				"Droguería Ricky");

		assertTrue(query.startsWith("from:alertasynotificaciones@notificacionesbancolombia.com recibiste "));
		assertTrue(query.contains("\"Droguería Ricky\""));
		assertTrue(query.contains("\"Drogueria Ricky\""));
		assertTrue(query.contains(" after:2026/01/01 before:2026/02/01"));
	}

	@Test
	void quotesAShopWhoseNameHasNoAccent() {
		assertEquals("\"Prueba\"", GmailMailbox.shopPhrase("Prueba"));
	}

	@Test
	void matchesNothingWhenTheShopHasNoName() {
		assertNull(GmailMailbox.historyQuery(
				"alertasynotificaciones@notificacionesbancolombia.com",
				LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 2, 1),
				"  "));
	}
}
