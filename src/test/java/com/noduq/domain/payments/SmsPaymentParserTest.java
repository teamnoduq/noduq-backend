package com.noduq.domain.payments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bancolombia rewords these messages whenever it feels like it, so these cases cover the
 * shapes the parser is built to survive rather than one blessed wording.
 */
class SmsPaymentParserTest {

	private static final Instant NOON_IN_BOGOTA = ZonedDateTime
			.of(2026, 9, 13, 12, 0, 0, 0, SmsPaymentParser.COLOMBIA)
			.toInstant();

	@Nested
	@DisplayName("amount")
	class Amounts {

		@Test
		void readsPesosWrittenWithDotsAsThousands() {
			assertEquals(new BigDecimal("48000.00"), SmsPaymentParser.amount("por $48.000 hoy"));
		}

		@Test
		void readsMillionsWithSeveralGroups() {
			assertEquals(new BigDecimal("1234567.00"), SmsPaymentParser.amount("valor $1.234.567"));
		}

		@Test
		void readsCentsAfterAComma() {
			assertEquals(new BigDecimal("1234.50"), SmsPaymentParser.amount("$1.234,50"));
		}

		@Test
		void treatsAThreeDigitGroupAfterACommaAsThousands() {
			assertEquals(new BigDecimal("48000.00"), SmsPaymentParser.amount("$48,000"));
		}

		@Test
		void findsTheAmountWhenTheBankOnlyLabelsIt() {
			assertEquals(new BigDecimal("15000.00"), SmsPaymentParser.amount("Pago QR por 15.000 recibido"));
		}

		@Test
		void ignoresAccountDigitsThatAreNotMoney() {
			assertNull(SmsPaymentParser.amount("Novedad en tu cuenta *8186"));
		}
	}

	@Nested
	@DisplayName("payer")
	class Payers {

		@Test
		void readsTheNameAfterDe() {
			assertEquals("LAURA MENDEZ", SmsPaymentParser.payer("Recibiste $48.000 de LAURA MENDEZ"));
		}

		@Test
		void stopsTheNameBeforeTheRestOfTheSentence() {
			assertEquals(
					"ANDRES RUIZ",
					SmsPaymentParser.payer("Recibiste $22.500 de ANDRES RUIZ el 13/09/2026 a las 10:35"));
		}

		@Test
		void skipsTheBankTalkingAboutYourOwnAccount() {
			assertEquals(
					"PEDRO GOMEZ",
					SmsPaymentParser.payer("Recibiste $30.000 de tu cuenta de ahorros de PEDRO GOMEZ"));
		}

		@Test
		void keepsAccentsAndShortSurnames() {
			assertEquals("CAMILA ORTÍZ", SmsPaymentParser.payer("Pago de CAMILA ORTÍZ, gracias"));
		}

		@Test
		void doesNotInventANameWhenThereIsNone() {
			assertNull(SmsPaymentParser.payer("Bancolombia informa una novedad en tu producto"));
		}
	}

	@Nested
	@DisplayName("moment")
	class Moments {

		@Test
		void readsAFullDateAndTime() {
			Instant occurred = SmsPaymentParser.occurredAt(
					"Pago QR el 13/09/2026 10:35", NOON_IN_BOGOTA, SmsPaymentParser.COLOMBIA);
			assertEquals(
					ZonedDateTime.of(2026, 9, 13, 10, 35, 0, 0, SmsPaymentParser.COLOMBIA).toInstant(),
					occurred);
		}

		@Test
		void fillsInTodayWhenOnlyTheTimeIsGiven() {
			Instant occurred = SmsPaymentParser.occurredAt(
					"Pago QR a las 10:35", NOON_IN_BOGOTA, SmsPaymentParser.COLOMBIA);
			assertEquals(
					ZonedDateTime.of(2026, 9, 13, 10, 35, 0, 0, SmsPaymentParser.COLOMBIA).toInstant(),
					occurred);
		}

		@Test
		void rollsBackADayRatherThanClaimingAPaymentFromTheFuture() {
			Instant occurred = SmsPaymentParser.occurredAt(
					"Pago QR a las 23:50", NOON_IN_BOGOTA, SmsPaymentParser.COLOMBIA);
			assertEquals(
					ZonedDateTime.of(2026, 9, 12, 23, 50, 0, 0, SmsPaymentParser.COLOMBIA).toInstant(),
					occurred);
		}

		@Test
		void refusesADateTooFarFromWhenTheMessageArrived() {
			assertNull(SmsPaymentParser.occurredAt(
					"Pago QR el 01/01/2020 10:35", NOON_IN_BOGOTA, SmsPaymentParser.COLOMBIA));
		}
	}

	@Nested
	@DisplayName("the real Bancolombia receipt")
	class RealReceipt {

		/** Captured from the 85540 short code. The email carries the same wording. */
		private static final String MESSAGE = "Bancolombia: DROGUERIA RICKY, recibiste un pago de "
				+ "JUAN DAVID MARRUGO NARVAEZ por $6,500.00 en tu cuenta *8186 conectado a la llave "
				+ "0089074729 el 12/09/2026 a las 15:11. Con codigo QR es facil y de una. "
				+ "Dudas al 018000912345.";

		@Test
		void readsThePayerWithoutTheShopNameOrTheRestOfTheSentence() {
			assertEquals("JUAN DAVID MARRUGO NARVAEZ", SmsPaymentParser.read(MESSAGE, NOON_IN_BOGOTA).payerName());
		}

		@Test
		void readsSixThousandFiveHundredEvenThoughTheCommaIsTheThousandsMark() {
			assertEquals(new BigDecimal("6500.00"), SmsPaymentParser.read(MESSAGE, NOON_IN_BOGOTA).amount());
		}

		@Test
		void readsTheMomentAcrossTheWordsBetweenDateAndTime() {
			assertEquals(
					ZonedDateTime.of(2026, 9, 12, 15, 11, 0, 0, SmsPaymentParser.COLOMBIA).toInstant(),
					SmsPaymentParser.read(MESSAGE, NOON_IN_BOGOTA).occurredAt());
		}

		@Test
		void findsTheAccountItLandedInWithoutConfusingItWithTheKeyOrTheHelpLine() {
			assertEquals("8186", SmsPaymentParser.accountLast4(MESSAGE));
		}

		@Test
		void neverKeepsTheKeyOrTheHelpLineWhenTheMessageHasToBeSavedForDebugging() {
			String masked = SmsPaymentParser.maskDigits(MESSAGE);
			assertFalse(masked.contains("0089074729"), "the QR key must not survive masking");
			assertFalse(masked.contains("018000912345"), "the help line must not survive masking");
			assertFalse(masked.contains("8186"), "the account digits must not survive masking");
		}
	}

	@Nested
	@DisplayName("whole message")
	class WholeMessage {

		@Test
		void readsATypicalReceipt() {
			SmsPaymentParser.Reading reading = SmsPaymentParser.read(
					"Bancolombia le informa recibio un pago QR por $48.000 de LAURA MENDEZ el 13/09/2026 10:35",
					NOON_IN_BOGOTA);
			assertEquals("LAURA MENDEZ", reading.payerName());
			assertEquals(new BigDecimal("48000.00"), reading.amount());
			assertEquals(
					ZonedDateTime.of(2026, 9, 13, 10, 35, 0, 0, SmsPaymentParser.COLOMBIA).toInstant(),
					reading.occurredAt());
			assertTrue(reading.anything());
		}

		@Test
		void survivesLineBreaksAndDoubleSpaces() {
			SmsPaymentParser.Reading reading = SmsPaymentParser.read(
					"Pago QR\n  por $20.500\n  de ANDRES RUIZ", NOON_IN_BOGOTA);
			assertEquals("ANDRES RUIZ", reading.payerName());
			assertEquals(new BigDecimal("20500.00"), reading.amount());
		}

		@Test
		void reportsNothingRatherThanGuessingOnAMessageItCannotRead() {
			SmsPaymentParser.Reading reading = SmsPaymentParser.read(
					"Bancolombia informa una novedad en tu producto", NOON_IN_BOGOTA);
			assertNull(reading.payerName());
			assertNull(reading.amount());
			assertFalse(reading.anything());
		}
	}

	@Test
	void masksLongDigitRunsSoAnUnreadableMessageCanBeKeptSafely() {
		assertEquals(
				"Pago de cuenta ### por $###",
				SmsPaymentParser.maskDigits("Pago de cuenta 8186123 por $48000"));
	}
}
