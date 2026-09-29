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
		IGNORED_NOT_QR,
		IGNORED_NOT_RECEIPT,
		IGNORED_OTHER_SHOP,
		IGNORED_NO_PLAN,
		IGNORED_BEFORE_SHOP
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
		return ingestSms(workspace.organization().id(), sender, body, sentAt, true);
	}

	/** A receipt from the phone's old inbox. The counter is not rung; the date on the message is kept. */
	public Ingested ingestHistoricalSms(
			UUID organizationId,
			String sender,
			String body,
			Instant sentAt) {
		if (!historicalMoment(sentAt)) {
			return new Ingested(Outcome.IGNORED_SENDER, null);
		}
		return ingestSms(organizationId, sender, body, sentAt, false);
	}

	public Ingested ingestSms(
			UUID organizationId,
			String sender,
			String body,
			Instant sentAt) {
		return ingestSms(organizationId, sender, body, sentAt, true);
	}

	private Ingested ingestSms(
			UUID organizationId,
			String sender,
			String body,
			Instant sentAt,
			boolean announce) {
		String text = require(body);
		if (!senders.isQrPaymentSms(sender)) {
			log.info(
					"SMS ignored org={} sender={} knownBankSender={}",
					organizationId,
					sender,
					senders.isKnownSms(sender));
			return new Ingested(Outcome.IGNORED_SENDER, null);
		}
		Ingested rejected = rejectUnlessThisShop(organizationId, text);
		if (rejected != null) {
			return rejected;
		}
		if (announce && beforeThisShop(organizationId, sentAt)) {
			log.info("SMS ignored org={}: the text is from before this shop existed", organizationId);
			return new Ingested(Outcome.IGNORED_BEFORE_SHOP, null);
		}

		Instant receivedAt = announce ? receivedAt(sentAt) : sentAt;
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
				PaymentFingerprint.of(text, receivedAt),
				null,
				reading.anything() ? null : SmsPaymentParser.maskDigits(text));

		Optional<PaymentNotice> stored = notices.insertIfNew(draft);
		if (stored.isEmpty()) {
			log.info("SMS already seen org={}", organizationId);
			PaymentNotice existing = notices.findByFingerprint(organizationId, draft.fingerprint()).orElse(null);
			if (existing != null && announce) {
				notifier.announce(existing);
			}
			return new Ingested(Outcome.DUPLICATE, existing);
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
		if (announce) {
			notifier.announce(notice);
		}
		return new Ingested(Outcome.STORED, notice);
	}

	public Ingested ingestOwnerEmail(UUID profileId, String from, String body, Instant sentAt) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		return ingestEmail(workspace.organization().id(), from, body, sentAt, true);
	}

	public Ingested ingestEmail(
			UUID organizationId,
			String from,
			String body,
			Instant sentAt) {
		return ingestEmail(organizationId, from, body, sentAt, true);
	}

	/** An old bank email. The date on the message is kept and the counter is not rung. */
	public Ingested ingestHistoricalEmail(
			UUID organizationId,
			String from,
			String body,
			Instant sentAt) {
		if (!historicalMoment(sentAt)) {
			return new Ingested(Outcome.IGNORED_SENDER, null);
		}
		return ingestEmail(organizationId, from, body, sentAt, false);
	}

	private Ingested ingestEmail(
			UUID organizationId,
			String from,
			String body,
			Instant sentAt,
			boolean announce) {
		String text = requireEmail(body);
		if (!senders.isQrPaymentEmail(from)) {
			if (announce) {
				log.info("Email ignored org={} from={}", organizationId, from);
			}
			return new Ingested(Outcome.IGNORED_SENDER, null);
		}
		if (!senders.looksLikeQrPayment(text)) {
			if (announce) {
				log.info("Email ignored org={}: body does not mention the QR", organizationId);
			}
			return new Ingested(Outcome.IGNORED_NOT_QR, null);
		}
		Ingested rejected = rejectUnlessThisShop(organizationId, text);
		if (rejected != null) {
			return rejected;
		}

		Instant receivedAt = announce ? receivedAt(sentAt) : sentAt;
		String fingerprint = PaymentFingerprint.of(text, receivedAt);
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
		if (announce) {
			notifier.announce(notice);
		}
		return new Ingested(Outcome.STORED, notice);
	}

	/**
	 * A notice is a sale for this shop only when the bank says the shop received the money
	 * and the shop on the message is this one. {@code pagaste} and another droguería's
	 * receipt are dropped here, for the live feed and for the history import alike.
	 */
	/** A parked text from the previous account must not land on a shop that did not exist yet. */
	private boolean beforeThisShop(UUID organizationId, Instant sentAt) {
		if (sentAt == null) {
			return false;
		}
		Instant opened = owners.findByOrganization(organizationId)
				.map(shop -> shop.organization().createdAt())
				.orElse(null);
		if (opened == null) {
			return false;
		}
		return sentAt.isBefore(opened.minus(Duration.ofMinutes(2)));
	}

	private Ingested rejectUnlessThisShop(UUID organizationId, String text) {
		String onReceipt = SmsPaymentParser.shopOnReceipt(text);
		if (onReceipt == null) {
			log.info("Payment ignored org={}: not a received payment", organizationId);
			return new Ingested(Outcome.IGNORED_NOT_RECEIPT, null);
		}
		String shopName = owners.findByOrganization(organizationId)
				.map(workspace -> workspace.organization().name())
				.orElse("");
		if (!SmsPaymentParser.sameShop(onReceipt, shopName)) {
			log.info("Payment ignored org={}: receipt is for \"{}\"", organizationId, onReceipt);
			return new Ingested(Outcome.IGNORED_OTHER_SHOP, null);
		}
		return null;
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

	static boolean historicalMoment(Instant sentAt) {
		if (sentAt == null) {
			return false;
		}
		return !sentAt.isAfter(Instant.now().plus(Duration.ofMinutes(10)));
	}

	private static Instant receivedAt(Instant sentAt) {
		Instant now = Instant.now();
		if (sentAt == null || Duration.between(sentAt, now).abs().compareTo(MAX_CLOCK_DRIFT) > 0) {
			return now;
		}
		return sentAt;
	}
}
