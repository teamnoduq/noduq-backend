package com.noduq.domain.payments;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankSendersTest {

	private final BankSenders senders = new BankSenders(
			Set.of("85540"),
			Set.of("85540", "891333", "87554"),
			Set.of("alertasynotificaciones@ayn.notificacionesbancolombia.com"),
			Set.of("validaciondeseguridad@notificacionesbancolombia.com"),
			true);

	@Test
	void acceptsTheQrReceiptShortCode() {
		assertTrue(senders.isQrPaymentSms("85540"));
	}

	@Test
	void acceptsTheShortCodeWhenTheCarrierGluesTheCountryCodeInFront() {
		assertTrue(senders.isQrPaymentSms("+5785540"));
	}

	@Test
	void refusesTheOtherBancolombiaShortCodesThatAreNotReceipts() {
		assertFalse(senders.isQrPaymentSms("891333"));
		assertTrue(senders.isKnownSms("891333"));
	}

	@Test
	void refusesAnyoneElse() {
		assertFalse(senders.isQrPaymentSms("3001234567"));
		assertFalse(senders.isKnownSms("3001234567"));
		assertFalse(senders.isQrPaymentSms(null));
	}

	@Test
	void doesNotFallForANumberThatMerelyEndsInTheShortCode() {
		assertFalse(senders.isQrPaymentSms("1234585540"));
	}

	@Test
	void readsTheAddressOutOfAFullFromHeader() {
		assertTrue(senders.isQrPaymentEmail(
				"\"Alertas y Notificaciones\" <alertasynotificaciones@ayn.notificacionesbancolombia.com>"));
	}

	@Test
	void refusesTheSecurityMailboxEvenThoughItIsTheBank() {
		assertFalse(senders.isQrPaymentEmail("validaciondeseguridad@notificacionesbancolombia.com"));
	}

	@Test
	void wantsTheWordQrInTheBody() {
		assertTrue(senders.looksLikeQrPayment("Con codigo QR es facil y de una"));
		assertFalse(senders.looksLikeQrPayment("Bancolombia: extracto de tu cuenta"));
	}
}
