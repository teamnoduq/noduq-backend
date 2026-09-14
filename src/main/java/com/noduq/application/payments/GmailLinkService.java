package com.noduq.application.payments;

import com.noduq.adapter.outbound.payments.GmailMailbox;
import com.noduq.application.identity.OwnerAccountService;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.payments.GmailConnection;
import com.noduq.domain.payments.port.GmailConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class GmailLinkService {

	private static final Logger log = LoggerFactory.getLogger(GmailLinkService.class);

	private static final Duration STATE_TTL = Duration.ofMinutes(15);

	private final OwnerAccountService owners;
	private final GmailConnectionRepository connections;
	private final GmailMailbox gmail;
	private final PaymentIngestService ingest;
	private final String stateSecret;
	private final String appRedirect;

	public GmailLinkService(
			OwnerAccountService owners,
			GmailConnectionRepository connections,
			GmailMailbox gmail,
			PaymentIngestService ingest,
			@Value("${noduq.employee.jwt-secret}") String stateSecret,
			@Value("${noduq.gmail.app-redirect:com.noduq.app://gmail-callback}") String appRedirect) {
		this.owners = owners;
		this.connections = connections;
		this.gmail = gmail;
		this.ingest = ingest;
		this.stateSecret = stateSecret;
		this.appRedirect = appRedirect;
	}

	public boolean configured() {
		return gmail.configured();
	}

	public Status status(UUID profileId) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		Optional<GmailConnection> linked = connections.findByOrganization(workspace.organization().id());
		return new Status(
				gmail.configured(),
				linked.isPresent(),
				linked.map(GmailConnection::gmailAddress).orElse(null));
	}

	public String authorizationUrl(UUID profileId) {
		if (!gmail.configured()) {
			throw IdentityException.validation(
					"GMAIL_NOT_CONFIGURED",
					"Gmail todavía no está configurado en el servidor.");
		}
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		return gmail.authorizationUrl(sign(profileId));
	}

	public String finish(String code, String state) {
		UUID profileId = readState(state);
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		GmailMailbox.Tokens tokens = gmail.exchange(code);
		if (tokens.accessToken() == null) {
			throw IdentityException.validation("GMAIL_DENIED", "Google no entregó el acceso a Gmail.");
		}
		GmailMailbox.Profile profile = gmail.profile(tokens.accessToken());
		String refresh = tokens.refreshToken();
		if (refresh == null) {
			refresh = connections.findByOrganization(workspace.organization().id())
					.map(GmailConnection::refreshToken)
					.orElseThrow(() -> IdentityException.validation(
							"GMAIL_DENIED",
							"Google no entregó el permiso persistente. Vuelve a conectar Gmail."));
		}
		connections.upsert(new GmailConnection(
				workspace.organization().id(),
				profileId,
				profile.emailAddress(),
				refresh,
				profile.historyId(),
				null));
		log.info("Gmail linked org={} address={}", workspace.organization().id(), profile.emailAddress());
		poll(connections.findByOrganization(workspace.organization().id()).orElseThrow());
		return appRedirect.contains("?") ? appRedirect + "&ok=1" : appRedirect + "?ok=1";
	}

	public void disconnect(UUID profileId) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		workspace.requireOwner();
		connections.delete(workspace.organization().id());
	}

	public void pollAll() {
		if (!gmail.configured()) {
			return;
		}
		for (GmailConnection connection : connections.all()) {
			try {
				poll(connection);
			} catch (RuntimeException ex) {
				log.warn("Gmail poll failed org={}: {}", connection.organizationId(), ex.toString());
			}
		}
	}

	private void poll(GmailConnection connection) {
		String access = gmail.refreshAccessToken(connection.refreshToken());
		if (access == null) {
			log.warn("Gmail refresh failed org={}", connection.organizationId());
			return;
		}
		GmailMailbox.Profile profile = gmail.profile(access);
		Instant floor = connection.lastPolledAt() == null
				? Instant.now().minus(Duration.ofHours(36))
				: connection.lastPolledAt().minus(Duration.ofMinutes(5));
		OwnerWorkspace workspace = owners.requireWorkspace(connection.profileId());
		for (GmailMailbox.BankMail mail : gmail.recentReceipts(access, floor)) {
			ingest.ingestEmail(
					connection.organizationId(),
					workspace.organization().merchantLast4(),
					mail.from(),
					mail.body(),
					mail.sentAt());
		}
		connections.touched(connection.organizationId(), profile.historyId(), Instant.now());
	}

	private String sign(UUID profileId) {
		long expires = Instant.now().plus(STATE_TTL).getEpochSecond();
		String payload = profileId + "." + expires;
		return payload + "." + hmac(payload);
	}

	private UUID readState(String state) {
		if (state == null || state.isBlank()) {
			throw IdentityException.unauthorized("El enlace de Gmail expiró. Vuelve a conectarlo.");
		}
		String[] parts = state.split("\\.");
		if (parts.length != 3) {
			throw IdentityException.unauthorized("El enlace de Gmail no es válido.");
		}
		String payload = parts[0] + "." + parts[1];
		if (!hmac(payload).equals(parts[2])) {
			throw IdentityException.unauthorized("El enlace de Gmail no es válido.");
		}
		try {
			if (Long.parseLong(parts[1]) < Instant.now().getEpochSecond()) {
				throw IdentityException.unauthorized("El enlace de Gmail expiró. Vuelve a conectarlo.");
			}
			return UUID.fromString(parts[0]);
		} catch (IllegalArgumentException ex) {
			throw IdentityException.unauthorized("El enlace de Gmail no es válido.");
		}
	}

	private String hmac(String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(stateSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException("Cannot sign Gmail state", ex);
		}
	}

	public record Status(boolean configured, boolean connected, String address) {
	}
}
