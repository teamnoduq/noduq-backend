package com.noduq.adapter.outbound.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.port.PlaySubscriptionGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class RevenueCatPlaySubscriptions implements PlaySubscriptionGateway {

	private static final Logger log = LoggerFactory.getLogger(RevenueCatPlaySubscriptions.class);

	private final RestClient http;
	private final ObjectMapper json;
	private final String secretKey;

	public RevenueCatPlaySubscriptions(
			ObjectMapper json,
			@Value("${noduq.billing.secret-api-key:}") String secretKey) {
		this.json = json;
		this.secretKey = secretKey == null ? "" : secretKey.trim();
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(20));
		this.http = RestClient.builder().baseUrl("https://api.revenuecat.com/v1").requestFactory(factory).build();
	}

	@Override
	public void cancelRenewal(UUID appUserId) {
		if (secretKey.isBlank()) {
			throw IdentityException.validation(
					"BILLING_CANCEL_UNAVAILABLE",
					"No se pudo cortar el cobro de Play. Falta la clave secreta de RevenueCat.");
		}
		JsonNode subscriber = fetchSubscriber(appUserId);
		List<String> transactions = renewingPlayTransactions(subscriber, Instant.now());
		for (String transactionId : transactions) {
			cancel(appUserId, transactionId);
		}
		if (transactions.isEmpty()) {
			log.info("No Play renewal to cancel app_user_id={}", appUserId);
		}
	}

	/**
	 * Play subscriptions that will charge again. Already cancelled, refunded, or expired ones are skipped.
	 * Transaction ids are the store ids (GPA…), which is what RevenueCat's cancel endpoint expects.
	 */
	static List<String> renewingPlayTransactions(JsonNode root, Instant now) {
		JsonNode subscriptions = root.path("subscriber").path("subscriptions");
		List<String> ids = new ArrayList<>();
		if (!subscriptions.isObject()) {
			return ids;
		}
		subscriptions.fields().forEachRemaining(entry -> {
			JsonNode subscription = entry.getValue();
			if (!"play_store".equals(subscription.path("store").asText())) {
				return;
			}
			if (present(subscription, "unsubscribe_detected_at") || present(subscription, "refunded_at")) {
				return;
			}
			Instant expires = instant(subscription, "expires_date");
			if (expires != null && !expires.isAfter(now)) {
				return;
			}
			String transactionId = subscription.path("store_transaction_id").asText("");
			if (!transactionId.isBlank()) {
				ids.add(transactionId);
			}
		});
		return ids;
	}

	private JsonNode fetchSubscriber(UUID appUserId) {
		try {
			String body = http.get()
					.uri("/subscribers/{appUserId}", appUserId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(String.class);
			if (body == null || body.isBlank()) {
				return json.createObjectNode();
			}
			return json.readTree(body);
		} catch (RestClientResponseException ex) {
			if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
				return json.createObjectNode();
			}
			log.warn("RevenueCat subscriber lookup failed app_user_id={} status={}", appUserId, ex.getStatusCode().value());
			throw IdentityException.validation(
					"BILLING_CANCEL_FAILED",
					"No se pudo cortar el cobro de Play. La cuenta sigue ahí.");
		} catch (IdentityException ex) {
			throw ex;
		} catch (Exception ex) {
			log.warn("RevenueCat subscriber lookup failed app_user_id={}", appUserId, ex);
			throw IdentityException.validation(
					"BILLING_CANCEL_FAILED",
					"No se pudo cortar el cobro de Play. La cuenta sigue ahí.");
		}
	}

	private void cancel(UUID appUserId, String transactionId) {
		try {
			http.post()
					.uri("/subscribers/{appUserId}/subscriptions/{transactionId}/cancel", appUserId, transactionId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.toBodilessEntity();
			log.info("Play renewal cancelled app_user_id={}", appUserId);
		} catch (RestClientResponseException ex) {
			if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
				return;
			}
			log.warn("RevenueCat cancel failed app_user_id={} status={}", appUserId, ex.getStatusCode().value());
			throw IdentityException.validation(
					"BILLING_CANCEL_FAILED",
					"No se pudo cortar el cobro de Play. La cuenta sigue ahí.");
		} catch (Exception ex) {
			log.warn("RevenueCat cancel failed app_user_id={}", appUserId, ex);
			throw IdentityException.validation(
					"BILLING_CANCEL_FAILED",
					"No se pudo cortar el cobro de Play. La cuenta sigue ahí.");
		}
	}

	private static boolean present(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && !value.isNull() && !value.asText("").isBlank();
	}

	private static Instant instant(JsonNode node, String field) {
		if (!present(node, field)) {
			return null;
		}
		try {
			return Instant.parse(node.get(field).asText());
		} catch (RuntimeException ex) {
			return null;
		}
	}
}
