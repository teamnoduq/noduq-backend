package com.noduq.adapter.outbound.payments;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmailMailboxQueryTest {

	@Test
	void asksOnlyForMailThatNamesThisShopAndWasReceived() {
		String query = GmailMailbox.historyQuery(
				"alertasynotificaciones@notificacionesbancolombia.com",
				"Droguería Ricky");

		assertTrue(query.startsWith("from:alertasynotificaciones@notificacionesbancolombia.com recibiste "));
		assertTrue(query.contains("\"Droguería Ricky\""));
		assertTrue(query.contains("\"Drogueria Ricky\""));
		assertFalse(query.contains(" after:"));
		assertFalse(query.contains(" before:"));
	}

	@Test
	void quotesAShopWhoseNameHasNoAccent() {
		assertEquals("\"Prueba\"", GmailMailbox.shopPhrase("Prueba"));
	}

	@Test
	void matchesNothingWhenTheShopHasNoName() {
		assertNull(GmailMailbox.historyQuery(
				"alertasynotificaciones@notificacionesbancolombia.com",
				"  "));
	}
}
