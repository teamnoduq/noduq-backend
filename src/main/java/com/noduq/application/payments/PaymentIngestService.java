package com.noduq.application.payments;

import com.noduq.application.identity.OrganizationPlanService;
import com.noduq.application.identity.OwnerAccountService;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.payments.BankSenders;
import com.noduq.domain.payments.PaymentFingerprint;
import com.noduq.domain.payments.PaymentNotice;
import com.noduq.domain.payments.PaymentSource;
import com.noduq.domain.payments.SmsPaymentParser;
import com.noduq.domain.payments.port.PaymentNoticeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Takes a bank SMS the owner's phone caught and turns it into a notice the counter can see.
 *
 * <p>Not transactional on purpose: the write is a single conditional insert, so wrapping it
 * would only keep a connection open while we talk to Firebase.
 */
@Service
public class PaymentIngestService {

	private static final Logger log = LoggerFactory.getLogger(PaymentIngestService.class);

	/** Concatenated SMS can run long, but not this long. */
	private static final int MAX_BODY_LENGTH = 2000;

	/** A bank mail is still a short receipt; we refuse the rest of the thread. */
	private static final int MAX_EMAIL_LENGTH = 20_000;

	/** The phone's clock is not ours to trust beyond this. */
	private static final Duration MAX_CLOCK_DRIFT = Duration.ofDays(2);

	public enum Outcome {
		STORED,
		DUPLICATE,
		CONFIRMED,
		IGNORED_SENDER,
		IGNORED_OTHER_ACCOUNT,
		IGNORED_NOT_QR,
		IGNORED_NO_PLAN
	}

	public record Ingested(Outcome outcome, PaymentNotice notice) {
	}

	private final OwnerAccountService owners;
	private final OrganizationPlanService plans;
	private final PaymentNoticeRepository notices;
	private final PaymentNotifier notifier;
	private final BankSenders senders;

	public PaymentIngestService(
			OwnerAccountService owners,
			OrganizationPlanService plans,
			PaymentNoticeRepository notices,
			PaymentNotifier notifier,
			BankSenders senders) {
		this.owners = owners;
		this.plans = plans;
		this.notices = notices;
		this.notifier = notifier;
		this.senders = senders;
	}

	public Ingested ingestOwnerSms(UUID profileId, String sender, String body, Instant sentAt) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		if (!plans.allowsSms(workspace.organization().id())) {
			return new Ingested(Outcome.IGNORED_NO_PLAN, null);
		}
		return ingestSms(
				workspace.organization().id(),
				workspace.organization().merchantLast4(),
				sender,
				body,
				sentAt);
	}

	public Ingested ingestSms(
			UUID organizationId,
			String merchantLast4,
			String sender,
			String body,
			Instant sentAt) {
		String text = require(body);
		if (!senders.isQrPaymentSms(sender)) {
			log.info(
					"SMS ignored org={} sender={} knownBankSender={}",
					organizationId,
					sender,
					senders.isKnownSms(sender));
			return new Ingested(Outcome.IGNORED_SENDER, null);
		}

		// The message names the account it landed in. When the shop told us its four digits we
		// can tell its payments apart from anything else arriving at the same phone.
		String account = SmsPaymentParser.accountLast4(text);
		if (merchantLast4 != null && account != null && !merchantLast4.equals(account)) {
			log.info("SMS ignored org={}: it names another account", organizationId);
			return new Ingested(Outcome.IGNORED_OTHER_ACCOUNT, null);
		}

		Instant receivedAt = receivedAt(sentAt);
		SmsPaymentParser.Reading reading = SmsPaymentParser.read(text, receivedAt);
		PaymentNotice draft = new PaymentNotice(
				UUID.randomUUID(),
				organizationId,
				PaymentSource.SMS,
				reading.payerName(),
				reading.amount(),
				PaymentNotice.DEFAULT_CURRENCY,
				reading.occurredAt(),
				receivedAt,
				PaymentFingerprint.of(sender, text),
				null,
				reading.anything() ? null : SmsPaymentParser.maskDigits(text));

		Optional<PaymentNotice> stored = notices.insertIfNew(draft);
		if (stored.isEmpty()) {
			log.info("SMS already seen org={}", organizationId);
			return new Ingested(Outcome.DUPLICATE, null);
		}

		PaymentNotice notice = stored.get();
		if (notice.readable()) {
			log.info("Payment notice {} stored org={} amount={}", notice.id(), organizationId, notice.amount());
		} else {
			// Keep the shape of the message, with the digits masked, so the parser can be fixed.
			log.warn(
					"Payment notice {} stored but unreadable org={} shape=\"{}\"",
					notice.id(),
					organizationId,
					notice.unparsedExcerpt());
		}
		notifier.announce(notice);
		return new Ingested(Outcome.STORED, notice);
	}

	public Ingested ingestOwnerEmail(UUID profileId, String from, String body, Instant sentAt) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		return ingestEmail(
				workspace.organization().id(),
				workspace.organization().merchantLast4(),
				from,
				body,
				sentAt);
	}

	public Ingested ingestEmail(
			UUID organizationId,
			String merchantLast4,
			String from,
			String body,
			Instant sentAt) {
		String text = requireEmail(body);
		if (!senders.isQrPaymentEmail(from)) {
			log.info("Email ignored org={} from={}", organizationId, from);
			return new Ingested(Outcome.IGNORED_SENDER, null);
		}
		if (!senders.looksLikeQrPayment(text)) {
			log.info("Email ignored org={}: body does not mention the QR", organizationId);
			return new Ingested(Outcome.IGNORED_NOT_QR, null);
		}

		String account = SmsPaymentParser.accountLast4(text);
		if (merchantLast4 != null && account != null && !merchantLast4.equals(account)) {
			log.info("Email ignored org={}: it names another account", organizationId);
			return new Ingested(Outcome.IGNORED_OTHER_ACCOUNT, null);
		}

		Instant receivedAt = receivedAt(sentAt);
		String fingerprint = PaymentFingerprint.ofBody(text);
		Optional<PaymentNotice> existing = notices.findByFingerprint(organizationId, fingerprint);
		if (existing.isPresent()) {
			Optional<PaymentNotice> confirmed = notices.markEmailConfirmed(
					organizationId, fingerprint, receivedAt);
			PaymentNotice notice = confirmed.orElse(existing.get());
			if (existing.get().confirmedByEmail()) {
				return new Ingested(Outcome.DUPLICATE, notice);
			}
			log.info("Payment notice {} confirmed by email org={}", notice.id(), organizationId);
			return new Ingested(Outcome.CONFIRMED, notice);
		}

		SmsPaymentParser.Reading reading = SmsPaymentParser.read(text, receivedAt);
		PaymentNotice draft = new PaymentNotice(
				UUID.randomUUID(),
				organizationId,
				PaymentSource.EMAIL,
				reading.payerName(),
				reading.amount(),
				PaymentNotice.DEFAULT_CURRENCY,
				reading.occurredAt(),
				receivedAt,
				fingerprint,
				receivedAt,
				reading.anything() ? null : SmsPaymentParser.maskDigits(text));

		Optional<PaymentNotice> stored = notices.insertIfNew(draft);
		if (stored.isEmpty()) {
			return new Ingested(Outcome.DUPLICATE, null);
		}
		PaymentNotice notice = stored.get();
		log.info("Payment notice {} stored from email org={} amount={}", notice.id(), organizationId, notice.amount());
		notifier.announce(notice);
		return new Ingested(Outcome.STORED, notice);
	}

	private static String require(String body) {
		if (body == null || body.isBlank()) {
			throw IdentityException.validation("SMS_BODY_REQUIRED", "El mensaje llegó vacío.");
		}
		if (body.length() > MAX_BODY_LENGTH) {
			throw IdentityException.validation("SMS_BODY_TOO_LONG", "Ese mensaje es demasiado largo.");
		}
		return body;
	}

	private static String requireEmail(String body) {
		if (body == null || body.isBlank()) {
			throw IdentityException.validation("EMAIL_BODY_REQUIRED", "El correo llegó vacío.");
		}
		if (body.length() > MAX_EMAIL_LENGTH) {
			throw IdentityException.validation("EMAIL_BODY_TOO_LONG", "Ese correo es demasiado largo.");
		}
		return body;
	}

	private static Instant receivedAt(Instant sentAt) {
		Instant now = Instant.now();
		if (sentAt == null || Duration.between(sentAt, now).abs().compareTo(MAX_CLOCK_DRIFT) > 0) {
			return now;
		}
		return sentAt;
	}
}
