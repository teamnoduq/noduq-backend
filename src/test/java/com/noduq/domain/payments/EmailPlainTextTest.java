package com.noduq.domain.payments;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailPlainTextTest {

	@Test
	void keepsTheReceiptPhraseWhenTheBankOnlySentHtml() {
		String html = "<html><body><p>Bancolombia: DROGUERIA RICKY, recibiste un pago de "
				+ "JUAN DAVID MARRUGO NARVAEZ por $6,500.00<br>Con codigo QR es facil y de una.</p></body></html>";
		String plain = EmailPlainText.fromHtml(html);
		assertTrue(plain.contains("JUAN DAVID MARRUGO NARVAEZ"));
		assertTrue(plain.toLowerCase().contains("qr"));
		SmsPaymentParser.Reading reading = SmsPaymentParser.read(plain, java.time.Instant.parse("2026-09-13T17:00:00Z"));
		org.junit.jupiter.api.Assertions.assertEquals("JUAN DAVID MARRUGO NARVAEZ", reading.payerName());
	}
}
