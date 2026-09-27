package com.noduq.adapter.inbound.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noduq.application.identity.OwnerAccountService;
import com.noduq.domain.payments.PaymentHistoryImport;
import com.noduq.domain.payments.port.PaymentHistoryRepository;
import com.noduq.domain.payments.port.PaymentNoticeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes history progress to the open phone. The phone keeps one connection for the
 * whole import and reconnects on its own if the link drops.
 */
@Component
public class PaymentHistoryLive extends TextWebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(PaymentHistoryLive.class);

	private final PaymentHistoryRepository history;
	private final PaymentNoticeRepository notices;
	private final ObjectMapper json;
	private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

	public PaymentHistoryLive(
			PaymentHistoryRepository history,
			PaymentNoticeRepository notices,
			ObjectMapper json) {
		this.history = history;
		this.notices = notices;
		this.json = json;
	}

	@Override
	public void afterConnectionEstablished(@NonNull WebSocketSession session) {
		UUID organizationId = organizationId(session);
		if (organizationId == null) {
			closeQuietly(session);
			return;
		}
		sessions.computeIfAbsent(organizationId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
		history.find(organizationId).ifPresent(row -> send(session, row));
	}

	@Override
	public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
		UUID organizationId = organizationId(session);
		if (organizationId == null) {
			return;
		}
		Set<WebSocketSession> open = sessions.get(organizationId);
		if (open != null) {
			open.remove(session);
		}
	}

	public void publish(PaymentHistoryImport row) {
		if (row == null) {
			return;
		}
		Set<WebSocketSession> open = sessions.get(row.organizationId());
		if (open == null || open.isEmpty()) {
			return;
		}
		for (WebSocketSession session : open) {
			send(session, row);
		}
	}

	private void send(WebSocketSession session, PaymentHistoryImport row) {
		if (!session.isOpen()) {
			return;
		}
		Instant earliest = PaymentHistoryImport.DONE.equals(row.status())
				? notices.earliest(row.organizationId()).orElse(null)
				: null;
		try {
			String body = json.writeValueAsString(PaymentController.HistoryResponse.from(row, earliest));
			synchronized (session) {
				if (session.isOpen()) {
					session.sendMessage(new TextMessage(body));
				}
			}
		} catch (Exception ex) {
			log.info("History socket closed org={}: {}", row.organizationId(), ex.toString());
		}
	}

	private static UUID organizationId(WebSocketSession session) {
		Object value = session.getAttributes().get("organizationId");
		return value instanceof UUID id ? id : null;
	}

	private static void closeQuietly(WebSocketSession session) {
		try {
			session.close(CloseStatus.POLICY_VIOLATION);
		} catch (Exception ignored) {
			// The handshake already failed.
		}
	}

	@Component
	static class Handshake implements HandshakeInterceptor {

		private final JwtDecoder decoder;
		private final OwnerAccountService owners;

		Handshake(JwtDecoder decoder, OwnerAccountService owners) {
			this.decoder = decoder;
			this.owners = owners;
		}

		@Override
		public boolean beforeHandshake(
				@NonNull ServerHttpRequest request,
				@NonNull ServerHttpResponse response,
				@NonNull org.springframework.web.socket.WebSocketHandler wsHandler,
				@NonNull Map<String, Object> attributes) {
			String header = request.getHeaders().getFirst("Authorization");
			if (header == null || header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
				response.setStatusCode(HttpStatus.UNAUTHORIZED);
				return false;
			}
			try {
				UUID userId = UUID.fromString(decoder.decode(header.substring(7).trim()).getSubject());
				attributes.put("organizationId", owners.requireWorkspace(userId).organization().id());
				return true;
			} catch (RuntimeException ex) {
				response.setStatusCode(HttpStatus.UNAUTHORIZED);
				return false;
			}
		}

		@Override
		public void afterHandshake(
				@NonNull ServerHttpRequest request,
				@NonNull ServerHttpResponse response,
				@NonNull org.springframework.web.socket.WebSocketHandler wsHandler,
				Exception exception) {
		}
	}
}
