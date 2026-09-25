package com.noduq.adapter.outbound.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noduq.domain.payments.EmailPlainText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Talks to Gmail with the shop's own OAuth grant. Empty client secrets mean the poller
 * stays off rather than crashing the API.
 */
@Component
public class GmailMailbox {

	private static final Logger log = LoggerFactory.getLogger(GmailMailbox.class);

	private static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
	private static final String TOKEN = "https://oauth2.googleapis.com/token";
	private static final String GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me";
	private static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
	private static final String QUERY = "from:notificacionesbancolombia.com newer_than:2d";

	private final RestClient http;
	private final ObjectMapper json;
	private final String clientId;
	private final String clientSecret;
	private final String redirectUri;

	public GmailMailbox(
			ObjectMapper json,
			@Value("${noduq.gmail.client-id:}") String clientId,
			@Value("${noduq.gmail.client-secret:}") String clientSecret,
			@Value("${noduq.gmail.redirect-uri:https://api.noduq.app/v1/gmail/callback}") String redirectUri) {
		this.http = RestClient.create();
		this.json = json;
		this.clientId = clientId.trim();
		this.clientSecret = clientSecret.trim();
		this.redirectUri = redirectUri.trim();
	}

	public boolean configured() {
		return !clientId.isEmpty() && !clientSecret.isEmpty();
	}

	public String authorizationUrl(String state) {
		return AUTH
				+ "?client_id=" + url(clientId)
				+ "&redirect_uri=" + url(redirectUri)
				+ "&response_type=code"
				+ "&scope=" + url(SCOPE)
				+ "&access_type=offline"
				+ "&prompt=consent"
				+ "&state=" + url(state);
	}

	public Tokens exchange(String code) {
		JsonNode body = postToken("authorization_code", code, null);
		return new Tokens(
				text(body, "access_token"),
				text(body, "refresh_token"),
				text(body, "id_token"));
	}

	public String refreshAccessToken(String refreshToken) {
		return text(postToken("refresh_token", null, refreshToken), "access_token");
	}

	public Profile profile(String accessToken) {
		JsonNode body = get(accessToken, GMAIL + "/profile");
		return new Profile(text(body, "emailAddress"), text(body, "historyId"));
	}

	/**
	 * One page of bank mail inside a window. {@code listed} is how many Gmail returned,
	 * including ones outside the exact instants. {@code nextPageToken} is null on the last page.
	 */
	public MailPage pageReceipts(String accessToken, Instant from, Instant until, String pageToken) {
		String query = "from:notificacionesbancolombia.com after:"
				+ gmailDay(from, -1)
				+ " before:"
				+ gmailDay(until, 1);
		String uri = GMAIL + "/messages?q=" + url(query) + "&maxResults=20";
		if (pageToken != null && !pageToken.isBlank()) {
			uri += "&pageToken=" + url(pageToken);
		}
		JsonNode list = get(accessToken, uri);
		JsonNode messages = list.path("messages");
		List<BankMail> receipts = new ArrayList<>();
		int listed = 0;
		if (messages.isArray()) {
			for (JsonNode message : messages) {
				listed++;
				String id = text(message, "id");
				if (id == null) {
					continue;
				}
				try {
					BankMail mail = read(accessToken, id);
					if (mail != null && !mail.sentAt().isBefore(from) && !mail.sentAt().isAfter(until)) {
						receipts.add(mail);
					}
				} catch (RuntimeException ex) {
					log.warn("Could not read Gmail message {}: {}", id, ex.toString());
				}
			}
		}
		return new MailPage(receipts, text(list, "nextPageToken"), list.path("resultSizeEstimate").asInt(0), listed);
	}

	private static String gmailDay(Instant instant, int plusDays) {
		return instant.atZone(java.time.ZoneId.of("America/Bogota"))
				.toLocalDate()
				.plusDays(plusDays)
				.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"));
	}

	public List<BankMail> recentReceipts(String accessToken, Instant notBefore) {
		JsonNode list = get(accessToken, GMAIL + "/messages?q=" + url(QUERY) + "&maxResults=20");
		JsonNode messages = list.path("messages");
		List<BankMail> receipts = new ArrayList<>();
		if (!messages.isArray()) {
			return receipts;
		}
		for (JsonNode message : messages) {
			String id = text(message, "id");
			if (id == null) {
				continue;
			}
			try {
				BankMail mail = read(accessToken, id);
				if (mail != null && (notBefore == null || !mail.sentAt().isBefore(notBefore))) {
					receipts.add(mail);
				}
			} catch (RuntimeException ex) {
				log.warn("Could not read Gmail message {}: {}", id, ex.toString());
			}
		}
		return receipts;
	}

	private BankMail read(String accessToken, String id) {
		JsonNode message = get(accessToken, GMAIL + "/messages/" + id + "?format=full");
		JsonNode payload = message.path("payload");
		String from = header(payload, "From");
		Instant sentAt = internalDate(message);
		String plain = firstPart(payload, "text/plain");
		if (plain == null || plain.isBlank()) {
			plain = EmailPlainText.fromHtml(firstPart(payload, "text/html"));
		}
		if (plain == null || plain.isBlank()) {
			plain = message.path("snippet").asText("");
		}
		return new BankMail(from, plain, sentAt);
	}

	private JsonNode postToken(String grant, String code, String refreshToken) {
		var form = new LinkedMultiValueMap<String, String>();
		form.add("client_id", clientId);
		form.add("client_secret", clientSecret);
		form.add("grant_type", grant);
		if (code != null) {
			form.add("code", code);
			form.add("redirect_uri", redirectUri);
		}
		if (refreshToken != null) {
			form.add("refresh_token", refreshToken);
		}
		String raw = http.post()
				.uri(TOKEN)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(String.class);
		return parse(raw);
	}

	private JsonNode get(String accessToken, String uri) {
		String raw = http.get()
				.uri(uri)
				.header("Authorization", "Bearer " + accessToken)
				.retrieve()
				.body(String.class);
		return parse(raw);
	}

	private JsonNode parse(String raw) {
		try {
			return json.readTree(raw == null ? "{}" : raw);
		} catch (Exception ex) {
			throw new IllegalStateException("Gmail answered with something that is not JSON", ex);
		}
	}

	private static String firstPart(JsonNode payload, String mime) {
		String type = payload.path("mimeType").asText("");
		if (mime.equalsIgnoreCase(type)) {
			String data = payload.path("body").path("data").asText("");
			return data.isEmpty() ? null : decode(data);
		}
		JsonNode parts = payload.path("parts");
		if (!parts.isArray()) {
			return null;
		}
		for (JsonNode part : parts) {
			String found = firstPart(part, mime);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static String header(JsonNode payload, String name) {
		JsonNode headers = payload.path("headers");
		if (!headers.isArray()) {
			return null;
		}
		for (JsonNode header : headers) {
			if (name.equalsIgnoreCase(header.path("name").asText())) {
				return header.path("value").asText(null);
			}
		}
		return null;
	}

	private static Instant internalDate(JsonNode message) {
		String raw = message.path("internalDate").asText("");
		if (raw.isEmpty()) {
			return Instant.now();
		}
		try {
			return Instant.ofEpochMilli(Long.parseLong(raw));
		} catch (NumberFormatException ex) {
			return Instant.now();
		}
	}

	private static String decode(String data) {
		return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
	}

	private static String text(JsonNode node, String field) {
		String value = node.path(field).asText("");
		return value.isBlank() ? null : value;
	}

	private static String url(String value) {
		return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	public record Tokens(String accessToken, String refreshToken, String idToken) {
	}

	public record Profile(String emailAddress, String historyId) {
	}

	public record BankMail(String from, String body, Instant sentAt) {
	}

	public record MailPage(List<BankMail> receipts, String nextPageToken, int estimate, int listed) {
	}
}
