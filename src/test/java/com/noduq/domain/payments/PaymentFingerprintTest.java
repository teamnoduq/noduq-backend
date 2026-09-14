package com.noduq.domain.payments;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PaymentFingerprintTest {

	private static final String RECEIPT = "Bancolombia: DROGUERIA RICKY, recibiste un pago de "
			+ "JUAN DAVID MARRUGO NARVAEZ por $6,500.00 en tu cuenta *8186 conectado a la llave "
			+ "0089074729 el 12/09/2026 a las 15:11. Con codigo QR es facil y de una. "
			+ "Dudas al 018000912345.";

	private static final Instant RECEIVED = ZonedDateTime.of(
			2026, 9, 12, 15, 20, 0, 0, SmsPaymentParser.COLOMBIA).toInstant();

	@Test
	void twoPaymentsOfTheSameAmountAtDifferentBankTimesAreNotTheSameNotice() {
		String later = RECEIPT.replace("15:11", "15:40");
		assertNotEquals(PaymentFingerprint.of(RECEIPT, RECEIVED), PaymentFingerprint.of(later, RECEIVED));
	}

	@Test
	void smsAndEmailOfTheSameReceiptShareAFingerprint() {
		assertEquals(PaymentFingerprint.of(RECEIPT, RECEIVED), PaymentFingerprint.of(RECEIPT, RECEIVED));
	}

	@Test
	void aRetransmitOfTheSameWordingIsStillTheSameNotice() {
		assertEquals(
				PaymentFingerprint.of(RECEIPT, RECEIVED),
				PaymentFingerprint.of("  " + RECEIPT + "  ", RECEIVED));
	}
}
