package com.noduq.application.payments;

import com.noduq.application.identity.OwnerAccountService;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.payments.BankSenders;
import com.noduq.domain.payments.PaymentNotice;
import com.noduq.domain.payments.PaymentSource;
import com.noduq.domain.payments.port.PaymentNoticeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentIngestServiceTest {

	private static final String RECEIPT = "Bancolombia: DROGUERIA RICKY, recibiste un pago de "
			+ "JUAN DAVID MARRUGO NARVAEZ por $6,500.00 en tu cuenta *8186 conectado a la llave "
			+ "0089074729 el 12/09/2026 a las 15:11. Con codigo QR es facil y de una. "
			+ "Dudas al 018000912345.";

	private static final UUID ORGANIZATION = UUID.randomUUID();

	@Mock
	private OwnerAccountService owners;

	@Mock
	private PaymentNoticeRepository notices;

	@Mock
	private PaymentNotifier notifier;

	private PaymentIngestService service;

	@BeforeEach
	void setUp() {
		BankSenders senders = new BankSenders(
				Set.of("85540"),
				Set.of("85540", "891333"),
				Set.of("alertasynotificaciones@ayn.notificacionesbancolombia.com"),
				Set.of("validaciondeseguridad@notificacionesbancolombia.com"),
				true);
		service = new PaymentIngestService(owners, notices, notifier, senders);
	}

	@Test
	void storesAndAnnouncesAReceiptFromTheShortCode() {
		echoInsert();

		PaymentIngestService.Ingested ingested = service.ingestSms(ORGANIZATION, "8186", "85540", RECEIPT, null);

		assertEquals(PaymentIngestService.Outcome.STORED, ingested.outcome());
		assertEquals("JUAN DAVID MARRUGO NARVAEZ", ingested.notice().payerName());
		assertEquals(new BigDecimal("6500.00"), ingested.notice().amount());
		assertEquals(PaymentSource.SMS, ingested.notice().source());
		assertEquals(ORGANIZATION, ingested.notice().organizationId());
		verify(notifier).announce(ingested.notice());
	}

	@Test
	void acceptsTheReceiptWhenTheShopNeverToldUsItsAccountDigits() {
		echoInsert();

		PaymentIngestService.Ingested ingested = service.ingestSms(ORGANIZATION, null, "85540", RECEIPT, null);

		assertEquals(PaymentIngestService.Outcome.STORED, ingested.outcome());
	}

	@Test
	void ignoresAReceiptThatNamesADifferentAccount() {
		PaymentIngestService.Ingested ingested = service.ingestSms(ORGANIZATION, "9999", "85540", RECEIPT, null);

		assertEquals(PaymentIngestService.Outcome.IGNORED_OTHER_ACCOUNT, ingested.outcome());
		verifyNoInteractions(notices, notifier);
	}

	@Test
	void ignoresTheAlertShortCodeThatIsNotAReceipt() {
		PaymentIngestService.Ingested ingested = service.ingestSms(
				ORGANIZATION, "8186", "891333", "Bancolombia: responde SI o NO a esta compra", null);

		assertEquals(PaymentIngestService.Outcome.IGNORED_SENDER, ingested.outcome());
		verifyNoInteractions(notices, notifier);
	}

	@Test
	void ignoresAStrangerTexting() {
		PaymentIngestService.Ingested ingested = service.ingestSms(
				ORGANIZATION, "8186", "3001234567", RECEIPT, null);

		assertEquals(PaymentIngestService.Outcome.IGNORED_SENDER, ingested.outcome());
		verifyNoInteractions(notices, notifier);
	}

	@Test
	void staysQuietWhenTheSameMessageArrivesTwice() {
		when(notices.insertIfNew(any())).thenReturn(Optional.empty());

		PaymentIngestService.Ingested ingested = service.ingestSms(ORGANIZATION, "8186", "85540", RECEIPT, null);

		assertEquals(PaymentIngestService.Outcome.DUPLICATE, ingested.outcome());
		verifyNoInteractions(notifier);
	}

	@Test
	void keepsTheSameFingerprintForTheSameMessageSoRepeatsCanBeSpotted() {
		echoInsert();

		service.ingestSms(ORGANIZATION, "8186", "85540", RECEIPT, null);
		service.ingestSms(ORGANIZATION, "8186", "85540", "  " + RECEIPT + "  ", null);

		ArgumentCaptor<PaymentNotice> drafts = ArgumentCaptor.forClass(PaymentNotice.class);
		verify(notices, org.mockito.Mockito.times(2)).insertIfNew(drafts.capture());
		assertEquals(
				drafts.getAllValues().get(0).fingerprint(),
				drafts.getAllValues().get(1).fingerprint());
	}

	@Test
	void storesAMessageItCannotReadRatherThanLosingThePayment() {
		echoInsert();

		PaymentIngestService.Ingested ingested = service.ingestSms(
				ORGANIZATION, "8186", "85540", "Bancolombia informa una novedad en tu producto", null);

		assertEquals(PaymentIngestService.Outcome.STORED, ingested.outcome());
		assertFalse(ingested.notice().readable());
		assertNotNull(ingested.notice().unparsedExcerpt());
		verify(notifier).announce(ingested.notice());
	}

	@Test
	void doesNotKeepTheRawMessageWhenItCouldBeRead() {
		echoInsert();

		PaymentIngestService.Ingested ingested = service.ingestSms(ORGANIZATION, "8186", "85540", RECEIPT, null);

		assertTrue(ingested.notice().readable());
		assertEquals(null, ingested.notice().unparsedExcerpt());
	}

	@Test
	void refusesAnEmptyMessage() {
		assertThrows(IdentityException.class, () -> service.ingestSms(ORGANIZATION, "8186", "85540", "   ", null));
	}

	@Test
	void refusesAMessageLongerThanAnySms() {
		String tooLong = "x".repeat(2001);

		assertThrows(
				IdentityException.class,
				() -> service.ingestSms(ORGANIZATION, "8186", "85540", tooLong, null));
	}

	@Test
	void storesAReceiptThatArrivedOnlyByEmail() {
		when(notices.findByFingerprint(any(), any())).thenReturn(Optional.empty());
		echoInsert();

		PaymentIngestService.Ingested ingested = service.ingestEmail(
				ORGANIZATION,
				"8186",
				"alertasynotificaciones@ayn.notificacionesbancolombia.com",
				RECEIPT,
				null);

		assertEquals(PaymentIngestService.Outcome.STORED, ingested.outcome());
		assertEquals(PaymentSource.EMAIL, ingested.notice().source());
		assertTrue(ingested.notice().confirmedByEmail());
		verify(notifier).announce(ingested.notice());
	}

	@Test
	void marksTheSmsRowWhenTheSameWordingArrivesByEmail() {
		PaymentNotice sms = smsNotice();
		when(notices.findByFingerprint(any(), any())).thenReturn(Optional.of(sms));
		when(notices.markEmailConfirmed(any(), any(), any())).thenAnswer(invocation -> {
			PaymentNotice original = sms;
			return Optional.of(new PaymentNotice(
					original.id(),
					original.organizationId(),
					original.source(),
					original.payerName(),
					original.amount(),
					original.currency(),
					original.occurredAt(),
					original.receivedAt(),
					original.fingerprint(),
					invocation.getArgument(2),
					original.unparsedExcerpt()));
		});

		PaymentIngestService.Ingested ingested = service.ingestEmail(
				ORGANIZATION,
				"8186",
				"Alertas y Notificaciones <alertasynotificaciones@ayn.notificacionesbancolombia.com>",
				RECEIPT,
				null);

		assertEquals(PaymentIngestService.Outcome.CONFIRMED, ingested.outcome());
		assertTrue(ingested.notice().confirmedByEmail());
		verifyNoInteractions(notifier);
	}

	@Test
	void ignoresTheSecurityMailbox() {
		PaymentIngestService.Ingested ingested = service.ingestEmail(
				ORGANIZATION,
				"8186",
				"validaciondeseguridad@notificacionesbancolombia.com",
				RECEIPT,
				null);

		assertEquals(PaymentIngestService.Outcome.IGNORED_SENDER, ingested.outcome());
		verifyNoInteractions(notices, notifier);
	}

	@Test
	void ignoresMailThatNeverMentionsTheQr() {
		PaymentIngestService.Ingested ingested = service.ingestEmail(
				ORGANIZATION,
				"8186",
				"alertasynotificaciones@ayn.notificacionesbancolombia.com",
				"Bancolombia: extracto de tu cuenta *8186",
				null);

		assertEquals(PaymentIngestService.Outcome.IGNORED_NOT_QR, ingested.outcome());
		verifyNoInteractions(notices, notifier);
	}

	@Test
	void hashesSmsAndEmailTheSameSoTheSecondCopyIsAConfirmation() {
		echoInsert();

		PaymentNotice sms = service.ingestSms(ORGANIZATION, "8186", "85540", RECEIPT, null).notice();
		assertEquals(sms.fingerprint(), com.noduq.domain.payments.PaymentFingerprint.ofBody(RECEIPT));
	}

	private PaymentNotice smsNotice() {
		return new PaymentNotice(
				UUID.randomUUID(),
				ORGANIZATION,
				PaymentSource.SMS,
				"JUAN DAVID MARRUGO NARVAEZ",
				new BigDecimal("6500.00"),
				"COP",
				null,
				java.time.Instant.parse("2026-09-12T20:11:00Z"),
				com.noduq.domain.payments.PaymentFingerprint.ofBody(RECEIPT),
				null,
				null);
	}

	private void echoInsert() {
		when(notices.insertIfNew(any())).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
	}
}
