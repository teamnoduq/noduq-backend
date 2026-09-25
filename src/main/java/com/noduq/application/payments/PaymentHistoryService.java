package com.noduq.application.payments;

import com.noduq.adapter.outbound.payments.GmailMailbox;
import com.noduq.application.identity.OrganizationPlanService;
import com.noduq.application.identity.OwnerAccountService;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.payments.GmailConnection;
import com.noduq.domain.payments.PaymentHistoryImport;
import com.noduq.domain.payments.port.GmailConnectionRepository;
import com.noduq.domain.payments.port.PaymentHistoryRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
public class PaymentHistoryService {

	static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

	private final OwnerAccountService owners;
	private final OrganizationPlanService plans;
	private final PaymentHistoryRepository history;
	private final GmailConnectionRepository connections;
	private final GmailMailbox gmail;
	private final PaymentIngestService ingest;

	public PaymentHistoryService(
			OwnerAccountService owners,
			OrganizationPlanService plans,
			PaymentHistoryRepository history,
			GmailConnectionRepository connections,
			GmailMailbox gmail,
			PaymentIngestService ingest) {
		this.owners = owners;
		this.plans = plans;
		this.history = history;
		this.connections = connections;
		this.gmail = gmail;
		this.ingest = ingest;
	}

	public PaymentHistoryImport status(java.util.UUID profileId) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		return history.find(workspace.organization().id()).orElse(null);
	}

	public PaymentHistoryImport defer(java.util.UUID profileId) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		java.util.UUID organizationId = workspace.organization().id();
		PaymentHistoryImport current = history.find(organizationId).orElse(null);
		if (current != null) {
			return current;
		}
		Instant now = Instant.now();
		PaymentHistoryImport deferred = new PaymentHistoryImport(
				organizationId,
				PaymentHistoryImport.DEFERRED,
				windowFrom(),
				now,
				0,
				0,
				0,
				null,
				null,
				null);
		history.save(deferred);
		return deferred;
	}

	public PaymentHistoryImport start(java.util.UUID profileId) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		java.util.UUID organizationId = workspace.organization().id();
		if (!plans.allowsEmail(organizationId)) {
			throw IdentityException.planRequired();
		}
		requireMailbox(organizationId);
		PaymentHistoryImport current = history.find(organizationId).orElse(null);
		if (current != null && PaymentHistoryImport.DONE.equals(current.status())) {
			return current;
		}
		if (current != null && PaymentHistoryImport.RUNNING.equals(current.status())) {
			return current;
		}
		Instant now = Instant.now();
		PaymentHistoryImport running = new PaymentHistoryImport(
				organizationId,
				PaymentHistoryImport.RUNNING,
				windowFrom(),
				now,
				0,
				0,
				0,
				null,
				now,
				null);
		history.save(running);
		return running;
	}

	/** Reads the next page of bank email. The phone calls this until the import is done. */
	public PaymentHistoryImport pullNext(java.util.UUID profileId) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		java.util.UUID organizationId = workspace.organization().id();
		if (!plans.allowsEmail(organizationId)) {
			throw IdentityException.planRequired();
		}
		PaymentHistoryImport current = history.find(organizationId)
				.filter(row -> PaymentHistoryImport.RUNNING.equals(row.status()))
				.orElseThrow(() -> IdentityException.validation(
						"HISTORY_NOT_RUNNING",
						"El histórico no está en curso."));
		GmailConnection connection = requireMailbox(organizationId);
		String access = gmail.refreshAccessToken(connection.refreshToken());
		if (access == null) {
			throw IdentityException.validation(
					"GMAIL_REFRESH",
					"No se pudo entrar al correo. Vuelve a conectarlo.");
		}
		GmailMailbox.MailPage page = gmail.pageReceipts(
				access,
				current.windowFrom(),
				current.windowUntil(),
				current.pageToken());
		int stored = current.storedMessages();
		String last4 = workspace.organization().merchantLast4();
		Instant liveFrom = liveFloor(connection.lastPolledAt());
		for (GmailMailbox.BankMail mail : page.receipts()) {
			// Mail the live poller still owns stays with it, so a payment that lands
			// while this import runs is stored once and the counter still rings.
			if (!mail.sentAt().isBefore(liveFrom)) {
				continue;
			}
			PaymentIngestService.Ingested ingested = ingest.ingestHistoricalEmail(
					organizationId,
					last4,
					mail.from(),
					mail.body(),
					mail.sentAt());
			if (ingested.outcome() == PaymentIngestService.Outcome.STORED) {
				stored++;
			}
		}
		String nextToken = page.nextPageToken();
		if (nextToken != null && nextToken.equals(current.pageToken())) {
			nextToken = null;
		}
		boolean done = nextToken == null;
		int processed = current.processedMessages() + page.listed();
		int knownTotal = Math.max(current.totalMessages(), Math.max(page.estimate(), processed));
		Instant now = Instant.now();
		PaymentHistoryImport next = new PaymentHistoryImport(
				organizationId,
				done ? PaymentHistoryImport.DONE : PaymentHistoryImport.RUNNING,
				current.windowFrom(),
				current.windowUntil(),
				done ? processed : knownTotal,
				processed,
				stored,
				done ? null : nextToken,
				current.startedAt() == null ? now : current.startedAt(),
				done ? now : null);
		history.save(next);
		return next;
	}

	private GmailConnection requireMailbox(java.util.UUID organizationId) {
		return connections.findByOrganization(organizationId)
				.orElseThrow(() -> IdentityException.validation(
						"GMAIL_REQUIRED",
						"Conecta el correo para traer el histórico."));
	}

	/** Same cutoff the live poller uses, so the two paths never insert the same receipt. */
	private static Instant liveFloor(Instant lastPolledAt) {
		if (lastPolledAt == null) {
			return Instant.now().minus(Duration.ofHours(36));
		}
		return lastPolledAt.minus(Duration.ofMinutes(5));
	}

	static Instant windowFrom() {
		return ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, BOGOTA).toInstant();
	}
}
