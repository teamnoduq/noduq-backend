package com.noduq.adapter.outbound.payments;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import com.noduq.domain.payments.PushMessage;
import com.noduq.domain.payments.port.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class FirebasePushSender implements PushSender {

	/** Must match the channel the Android app creates, or the push arrives silent. */
	public static final String PAYMENTS_CHANNEL = "noduq_payments";

	private static final Logger log = LoggerFactory.getLogger(FirebasePushSender.class);

	private static final String APP_NAME = "noduq";
	private static final int MAX_TOKENS_PER_CALL = 500;

	private final FirebaseMessaging messaging;

	public FirebasePushSender(@Value("${noduq.firebase.credentials:}") String credentials) {
		this.messaging = open(credentials);
	}

	@Override
	public boolean enabled() {
		return messaging != null;
	}

	@Override
	public Set<String> send(Collection<String> pushTokens, PushMessage message) {
		if (messaging == null || pushTokens.isEmpty()) {
			return Set.of();
		}
		List<String> tokens = List.copyOf(pushTokens);
		Set<String> rejected = new LinkedHashSet<>();
		for (int from = 0; from < tokens.size(); from += MAX_TOKENS_PER_CALL) {
			int to = Math.min(from + MAX_TOKENS_PER_CALL, tokens.size());
			rejected.addAll(sendBatch(tokens.subList(from, to), message));
		}
		return rejected;
	}

	private Set<String> sendBatch(List<String> tokens, PushMessage message) {
		BatchResponse response;
		try {
			response = messaging.sendEachForMulticast(multicast(tokens, message));
		} catch (FirebaseMessagingException ex) {
			throw new IllegalStateException("Firebase turned down the batch: " + ex.getMessage(), ex);
		}
		Set<String> rejected = new LinkedHashSet<>();
		List<SendResponse> results = response.getResponses();
		for (int i = 0; i < results.size() && i < tokens.size(); i++) {
			SendResponse result = results.get(i);
			if (result.isSuccessful()) {
				continue;
			}
			FirebaseMessagingException failure = result.getException();
			MessagingErrorCode code = failure == null ? null : failure.getMessagingErrorCode();
			if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
				rejected.add(tokens.get(i));
			} else {
				log.warn("Push to one device failed: {}", failure == null ? "unknown reason" : failure.getMessage());
			}
		}
		return rejected;
	}

	private static MulticastMessage multicast(List<String> tokens, PushMessage message) {
		return MulticastMessage.builder()
				.addAllTokens(tokens)
				.putAllData(message.data())
				.setNotification(Notification.builder()
						.setTitle(message.title())
						.setBody(message.body())
						.build())
				.setAndroidConfig(AndroidConfig.builder()
						.setPriority(AndroidConfig.Priority.HIGH)
						.setNotification(AndroidNotification.builder()
								.setChannelId(PAYMENTS_CHANNEL)
								.setSound("default")
								.setDefaultVibrateTimings(true)
								.build())
						.build())
				.build();
	}

	/**
	 * Push is a nice-to-have on top of a stored notice, so a missing or broken credential
	 * logs and steps aside instead of stopping the app from starting.
	 */
	private static FirebaseMessaging open(String credentials) {
		if (credentials == null || credentials.isBlank()) {
			log.warn("No Firebase credentials set: payments will be stored but no phone will ring");
			return null;
		}
		try {
			byte[] raw = serviceAccount(credentials);
			String projectId = projectIdFrom(raw);
			FirebaseOptions.Builder options = FirebaseOptions.builder()
					.setCredentials(GoogleCredentials.fromStream(new ByteArrayInputStream(raw)));
			if (projectId != null && !projectId.isBlank()) {
				options.setProjectId(projectId);
			}
			FirebaseApp app = FirebaseApp.getApps().stream()
					.filter(existing -> APP_NAME.equals(existing.getName()))
					.findFirst()
					.orElseGet(() -> FirebaseApp.initializeApp(options.build(), APP_NAME));
			log.info("Firebase push ready for project {}", app.getOptions().getProjectId());
			return FirebaseMessaging.getInstance(app);
		} catch (IOException | RuntimeException ex) {
			log.error("Firebase credentials unusable, push stays off: {}", ex.toString());
			return null;
		}
	}

	/** Accepts the service account JSON as-is, or base64 for platforms that dislike newlines. */
	private static byte[] serviceAccount(String configured) {
		String value = configured.trim();
		if (value.startsWith("{")) {
			return value.getBytes(StandardCharsets.UTF_8);
		}
		return Base64.getDecoder().decode(value.replaceAll("\\s", ""));
	}

	private static String projectIdFrom(byte[] json) {
		try {
			com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
			com.fasterxml.jackson.databind.JsonNode id = root.get("project_id");
			if (id == null || id.isNull() || id.asText().isBlank()) {
				id = root.get("projectId");
			}
			if (id == null || id.isNull() || id.asText().isBlank()) {
				return "noduq-col";
			}
			return id.asText();
		} catch (IOException ex) {
			log.warn("Firebase JSON had no project_id, using noduq-col: {}", ex.toString());
			return "noduq-col";
		}
	}
}
