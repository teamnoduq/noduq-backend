package com.noduq.application.payments;

import com.noduq.adapter.outbound.payments.GmailMailbox;
import com.noduq.adapter.outbound.payments.GmailQuotaException;
import com.noduq.application.identity.OrganizationPlanService;
import com.noduq.application.identity.OwnerAccountService;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.payments.GmailConnection;
import com.noduq.domain.payments.PaymentHistoryImport;
import com.noduq.domain.payments.port.GmailConnectionRepository;
import com.noduq.domain.payments.port.PaymentHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PaymentHistoryService {

	private static final Logger log = LoggerFactory.getLogger(PaymentHistoryService.class);

	static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
	private static final String LIST = "L";
	private static final String SAVE = "S";
	private static final String SEP = "\u001f";
	private static final int SAVE_BATCH = 5;
	/**
	 * Opens stay under Gmail's 6,000 units per minute. Each read costs several units, so a
	 * steady gap beats a burst: a bigger mailbox just takes longer, and a 403 keeps the id.
	 */
	private static final int SERVER_SAVE_BATCH = 12;
	private static final Duration READ_GAP = Duration.ofMillis(280);

	private final OwnerAccountService owners;
	private final OrganizationPlanService plans;
	private final PaymentHistoryRepository history;
	private final GmailConnectionRepository connections;
	private final GmailMailbox gmail;
	private final PaymentIngestService ingest;
	private final ConcurrentHashMap<UUID, Object> gates = new ConcurrentHashMap<>();

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
		history.clearMessages(organizationId);
		PaymentHistoryImport running = new PaymentHistoryImport(
				organizationId,
				PaymentHistoryImport.RUNNING,
				windowFrom(),
				now,
				0,
				0,
				0,
				listCursor(0, YearMonth.from(windowFrom().atZone(BOGOTA)), null),
				now,
				null);
		history.save(running);
		return running;
	}

	/**
	 * One step, for a phone that still asks. The import keeps going from the server
	 * even when this is never called.
	 */
	public PaymentHistoryImport pullNext(java.util.UUID profileId) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		java.util.UUID organizationId = workspace.organization().id();
		if (!plans.allowsEmail(organizationId)) {
			throw IdentityException.planRequired();
		}
		return advanceOne(organizationId, SAVE_BATCH);
	}

	/** Keeps every running import moving. The phone is only a window onto the progress. */
	public void advanceRunning(Duration budget) {
		Instant deadline = Instant.now().plus(budget);
		for (java.util.UUID organizationId : history.runningIds()) {
			if (!Instant.now().isBefore(deadline)) {
				return;
			}
			try {
				advance(organizationId, deadline, SERVER_SAVE_BATCH);
			} catch (RuntimeException ex) {
				log.warn("History advance failed org={}: {}", organizationId, ex.toString());
			}
		}
	}

	private void advance(java.util.UUID organizationId, Instant deadline, int saveBatch) {
		synchronized (gate(organizationId)) {
			while (Instant.now().isBefore(deadline)) {
				PaymentHistoryImport current = history.find(organizationId)
						.filter(row -> PaymentHistoryImport.RUNNING.equals(row.status()))
						.orElse(null);
				if (current == null) {
					return;
				}
				PaymentHistoryImport next = step(organizationId, current, saveBatch);
				if (PaymentHistoryImport.DONE.equals(next.status())) {
					return;
				}
			}
		}
	}

	private PaymentHistoryImport advanceOne(java.util.UUID organizationId, int saveBatch) {
		synchronized (gate(organizationId)) {
			PaymentHistoryImport current = history.find(organizationId)
					.filter(row -> PaymentHistoryImport.RUNNING.equals(row.status()))
					.orElseThrow(() -> IdentityException.validation(
							"HISTORY_NOT_RUNNING",
							"El histórico no está en curso."));
			return step(organizationId, current, saveBatch);
		}
	}

	private PaymentHistoryImport step(java.util.UUID organizationId, PaymentHistoryImport current, int saveBatch) {
		GmailConnection connection = requireMailbox(organizationId);
		String access = gmail.refreshAccessToken(connection.refreshToken());
		if (access == null) {
			throw IdentityException.validation(
					"GMAIL_REFRESH",
					"No se pudo entrar al correo. Vuelve a conectarlo.");
		}
		if (SAVE.equals(current.pageToken())) {
			return saveBatch(organizationId, current, access, saveBatch);
		}
		return listBatch(organizationId, current, access);
	}

	private Object gate(java.util.UUID organizationId) {
		return gates.computeIfAbsent(organizationId, ignored -> new Object());
	}

	/** One page of ids. The total stays unknown until every sender and month has been listed. */
	private PaymentHistoryImport listBatch(java.util.UUID organizationId, PaymentHistoryImport current, String access) {
		if (current.pageToken() == null || !current.pageToken().startsWith(LIST + SEP)) {
			history.clearMessages(organizationId);
		}
		ListSpot spot = parseList(current.pageToken(), current.windowFrom());
		if (spot.sender() >= GmailMailbox.BANK_SENDERS.size()) {
			return beginSave(organizationId, current);
		}
		String sender = GmailMailbox.BANK_SENDERS.get(spot.sender());
		String shopName = owners.findByOrganization(organizationId)
				.map(shop -> shop.organization().name())
				.orElse("");
		LocalDate afterDay = spot.month().atDay(1).minusDays(1);
		YearMonth lastMonth = YearMonth.from(current.windowUntil().atZone(BOGOTA));
		LocalDate beforeDay = spot.month().equals(lastMonth)
				? current.windowUntil().atZone(BOGOTA).toLocalDate().plusDays(1)
				: spot.month().plusMonths(1).atDay(1);
		GmailMailbox.IdPage page = gmail.listIds(access, sender, afterDay, beforeDay, shopName, spot.pageToken());
		history.rememberMessages(organizationId, page.ids());
		String nextToken = page.nextPageToken();
		if (nextToken != null && nextToken.equals(spot.pageToken())) {
			nextToken = null;
		}
		String cursor;
		if (nextToken != null) {
			cursor = listCursor(spot.sender(), spot.month(), nextToken);
		} else {
			YearMonth nextMonth = spot.month().plusMonths(1);
			if (!nextMonth.isAfter(lastMonth)) {
				cursor = listCursor(spot.sender(), nextMonth, null);
			} else if (spot.sender() + 1 < GmailMailbox.BANK_SENDERS.size()) {
				cursor = listCursor(spot.sender() + 1, YearMonth.from(current.windowFrom().atZone(BOGOTA)), null);
			} else {
				return beginSave(organizationId, current);
			}
		}
		int found = history.countMessages(organizationId);
		PaymentHistoryImport next = running(current, 0, found, 0, cursor);
		history.save(next);
		return next;
	}

	private PaymentHistoryImport beginSave(java.util.UUID organizationId, PaymentHistoryImport current) {
		int total = history.countMessages(organizationId);
		if (total == 0) {
			PaymentHistoryImport done = done(current, 0, 0);
			history.save(done);
			return done;
		}
		PaymentHistoryImport next = running(current, total, 0, current.storedMessages(), SAVE);
		history.save(next);
		return next;
	}

	/** Reads a handful of listed messages and stores them without ringing the counter. */
	private PaymentHistoryImport saveBatch(
			java.util.UUID organizationId,
			PaymentHistoryImport current,
			String access,
			int saveBatch) {
		List<String> ids = history.nextMessages(organizationId, saveBatch);
		if (ids.isEmpty()) {
			PaymentHistoryImport done = done(current, current.totalMessages(), current.storedMessages());
			history.save(done);
			return done;
		}
		int stored = current.storedMessages();
		List<String> opened = new ArrayList<>();
		for (String id : ids) {
			if (!pause(READ_GAP)) {
				break;
			}
			try {
				GmailMailbox.BankMail mail = gmail.readMail(access, id);
				if (mail != null
						&& !mail.sentAt().isBefore(current.windowFrom())
						&& !mail.sentAt().isAfter(current.windowUntil())) {
					PaymentIngestService.Ingested ingested = ingest.ingestHistoricalEmail(
							organizationId,
							mail.from(),
							mail.body(),
							mail.sentAt());
					if (ingested.outcome() == PaymentIngestService.Outcome.STORED) {
						stored++;
					}
				}
				opened.add(id);
			} catch (GmailQuotaException ex) {
				log.warn("Gmail quota reached; message {} stays queued", id);
				history.forgetMessages(organizationId, opened);
				pause(quotaCooldown(System.currentTimeMillis()));
				return rememberProgress(organizationId, current, stored);
			} catch (RuntimeException ex) {
				log.warn("Could not store historical message {}: {}", id, ex.toString());
				opened.add(id);
			}
		}
		history.forgetMessages(organizationId, opened);
		return rememberProgress(organizationId, current, stored);
	}

	private PaymentHistoryImport rememberProgress(
			java.util.UUID organizationId,
			PaymentHistoryImport current,
			int stored) {
		int remaining = history.countMessages(organizationId);
		int processed = current.totalMessages() - remaining;
		if (remaining == 0) {
			PaymentHistoryImport done = done(current, current.totalMessages(), stored);
			history.save(done);
			return done;
		}
		PaymentHistoryImport next = running(current, current.totalMessages(), processed, stored, SAVE);
		history.save(next);
		return next;
	}

	/** Wait out the rest of this minute, plus a little, so the next read starts on a fresh window. */
	static Duration quotaCooldown(long epochMillis) {
		long intoMinute = Math.floorMod(epochMillis, 60_000L);
		return Duration.ofMillis(60_000L - intoMinute + 1_500L);
	}

	private static boolean pause(Duration wait) {
		try {
			Thread.sleep(Math.max(0L, wait.toMillis()));
			return true;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static PaymentHistoryImport running(
			PaymentHistoryImport current,
			int total,
			int processed,
			int stored,
			String pageToken) {
		return new PaymentHistoryImport(
				current.organizationId(),
				PaymentHistoryImport.RUNNING,
				current.windowFrom(),
				current.windowUntil(),
				total,
				processed,
				stored,
				pageToken,
				current.startedAt() == null ? Instant.now() : current.startedAt(),
				null);
	}

	private static PaymentHistoryImport done(PaymentHistoryImport current, int total, int stored) {
		Instant now = Instant.now();
		return new PaymentHistoryImport(
				current.organizationId(),
				PaymentHistoryImport.DONE,
				current.windowFrom(),
				current.windowUntil(),
				total,
				total,
				stored,
				null,
				current.startedAt() == null ? now : current.startedAt(),
				now);
	}

	private GmailConnection requireMailbox(java.util.UUID organizationId) {
		return connections.findByOrganization(organizationId)
				.orElseThrow(() -> IdentityException.validation(
						"GMAIL_REQUIRED",
						"Conecta el correo para traer el histórico."));
	}

	private static String listCursor(int sender, YearMonth month, String pageToken) {
		return LIST + SEP + sender + SEP + month + SEP + (pageToken == null ? "" : pageToken);
	}

	private static ListSpot parseList(String token, Instant windowFrom) {
		YearMonth first = YearMonth.from(windowFrom.atZone(BOGOTA));
		if (token == null || !token.startsWith(LIST + SEP)) {
			return new ListSpot(0, first, null);
		}
		String[] parts = token.split(SEP, 4);
		if (parts.length < 4) {
			return new ListSpot(0, first, null);
		}
		try {
			int sender = Integer.parseInt(parts[1]);
			YearMonth month = YearMonth.parse(parts[2]);
			String page = parts[3].isBlank() ? null : parts[3];
			return new ListSpot(sender, month, page);
		} catch (RuntimeException ex) {
			return new ListSpot(0, first, null);
		}
	}

	private record ListSpot(int sender, YearMonth month, String pageToken) {
	}

	static Instant windowFrom() {
		return ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, BOGOTA).toInstant();
	}
}
