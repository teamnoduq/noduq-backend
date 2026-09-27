package com.noduq.adapter.inbound.http;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class PaymentHistorySocketConfig implements WebSocketConfigurer {

	private final PaymentHistoryLive live;
	private final PaymentHistoryLive.Handshake handshake;

	public PaymentHistorySocketConfig(PaymentHistoryLive live, PaymentHistoryLive.Handshake handshake) {
		this.live = live;
		this.handshake = handshake;
	}

	@Override
	public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
		registry.addHandler(live, "/v1/payments/history/live")
				.addInterceptors(handshake)
				.setAllowedOriginPatterns("*");
	}
}
