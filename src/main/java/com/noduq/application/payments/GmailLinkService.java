package com.noduq.application.payments;

import com.noduq.adapter.outbound.payments.GmailMailbox;
import com.noduq.application.identity.OrganizationPlanService;
import com.noduq.application.identity.OwnerAccountService;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.payments.GmailConnection;
import com.noduq.domain.payments.PendingGmail;
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
	private final OrganizationPlanService plans;
	private final String stateSecret;
	private final String appRedirect;
	private final String webRedirect;

	public GmailLinkService(
			OwnerAccountService owners,
			GmailConnectionRepository connections,
			GmailMailbox gmail,
			PaymentIngestService ingest,
			OrganizationPlanService plans,
			@Value("${noduq.employee.jwt-secret}") String stateSecret,
			@Value("${noduq.gmail.app-redirect:com.noduq.app://gmail-callback}") String appRedirect,
			@Value("${noduq.gmail.web-redirect:https://noduq.app/cuenta}") String webRedirect) {
		this.owners = owners;
		this.connections = connections;
		this.gmail = gmail;
		this.ingest = ingest;
		this.plans = plans;
		this.stateSecret = stateSecret;
		this.appRedirect = appRedirect;
		this.webRedirect = webRedirect == null || webRedirect.isBlank()
				? "https://noduq.app/cuenta"
				: webRedirect.trim();
	}

	public boolean configured() {
		return gmail.configured();
	}

	public Status status(UUID profileId) {
		Optional<OwnerWorkspace> workspace = owners.findWorkspace(profileId);
		if (workspace.isPresent()) {
			workspace.get().requireOwner();
			Optional<GmailConnection> linked = connections.findByOrganization(workspace.get().organization().id());
			return new Status(
					gmail.configured(),
					linked.isPresent(),
					linked.map(GmailConnection::gmailAddress).orElse(null));
		}
		return connections.findPending(profileId)
				.map(pending -> new Status(gmail.configured(), true, pending.gmailAddress()))
				.orElseGet(() -> new Status(gmail.configured(), false, null));
	}

	public String authorizationUrl(UUID profileId) {
		return authorizationUrl(profileId, false);
	}

	public String authorizationUrl(UUID profileId, boolean web) {
		if (!gmail.configured()) {
			throw IdentityException.validation(
					"GMAIL_NOT_CONFIGURED",
					"Gmail todavía no está configurado en el servidor.");
		}
		owners.findWorkspace(profileId).ifPresent(OwnerWorkspace::requireOwner);
		return gmail.authorizationUrl(sign(profileId, web));
	}

	public String finish(String code, String state) {
		SignedState signed = readState(state);
		UUID profileId = signed.profileId();
		GmailMailbox.Tokens tokens = gmail.exchange(code);
		if (tokens.accessToken() == null) {
			throw IdentityException.validation("GMAIL_DENIED", "Google no entregó el acceso a Gmail.");
		}
		GmailMailbox.Profile profile = gmail.profile(tokens.accessToken());
		Optional<OwnerWorkspace> workspace = owners.findWorkspace(profileId);
		String refresh = tokens.refreshToken();
		if (refresh == null) {
			refresh = workspace
					.flatMap(shop -> connections.findByOrganization(shop.organization().id()))
					.map(GmailConnection::refreshToken)
					.orElseGet(() -> connections.findPending(profileId).map(PendingGmail::refreshToken).orElse(null));
			if (refresh == null) {
				throw IdentityException.validation(
						"GMAIL_DENIED",
						"Google no entregó el permiso persistente. Vuelve a conectar Gmail.");
			}
		}
		if (workspace.isPresent()) {
			OwnerWorkspace shop = workspace.get();
			shop.requireOwner();
			connections.upsert(new GmailConnection(
					shop.organization().id(),
					profileId,
					profile.emailAddress(),
					refresh,
					profile.historyId(),
					null));
			log.info("Gmail linked org={} address={}", shop.organization().id(), profile.emailAddress());
			poll(connections.findByOrganization(shop.organization().id()).orElseThrow());
		} else {
			connections.upsertPending(new PendingGmail(
					profileId,
					profile.emailAddress(),
					refresh,
					profile.historyId()));
			log.info("Gmail pending profile={} address={}", profileId, profile.emailAddress());
		}
		String target = signed.web() ? webRedirect : appRedirect;
		return target.contains("?") ? target + "&gmail=ok" : target + "?gmail=ok";
	}

	public void disconnect(UUID profileId) {
		Optional<OwnerWorkspace> workspace = owners.findWorkspace(profileId);
		if (workspace.isPresent()) {
			workspace.get().requireOwner();
			connections.delete(workspace.get().organization().id());
		}
		connections.deletePending(profileId);
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
		if (!plans.allowsEmail(connection.organizationId())) {
			return;
		}
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

	private String sign(UUID profileId, boolean web) {
		long expires = Instant.now().plus(STATE_TTL).getEpochSecond();
		String payload = profileId + "." + expires + "." + (web ? "web" : "app");
		return payload + "." + hmac(payload);
	}

	private SignedState readState(String state) {
		if (state == null || state.isBlank()) {
			throw IdentityException.unauthorized("El enlace de Gmail expiró. Vuelve a conectarlo.");
		}
		String[] parts = state.split("\\.");
		if (parts.length != 3 && parts.length != 4) {
			throw IdentityException.unauthorized("El enlace de Gmail no es válido.");
		}
		boolean web = parts.length == 4 && "web".equals(parts[2]);
		String payload = parts.length == 4 ? parts[0] + "." + parts[1] + "." + parts[2] : parts[0] + "." + parts[1];
		String signature = parts[parts.length - 1];
		if (!hmac(payload).equals(signature)) {
			throw IdentityException.unauthorized("El enlace de Gmail no es válido.");
		}
		try {
			if (Long.parseLong(parts[1]) < Instant.now().getEpochSecond()) {
				throw IdentityException.unauthorized("El enlace de Gmail expiró. Vuelve a conectarlo.");
			}
			return new SignedState(UUID.fromString(parts[0]), web);
		} catch (IllegalArgumentException ex) {
			throw IdentityException.unauthorized("El enlace de Gmail no es válido.");
		}
	}

	private record SignedState(UUID profileId, boolean web) {
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
