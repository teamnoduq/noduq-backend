package com.noduq.adapter.inbound.http;

import com.noduq.application.payments.PaymentHistoryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class PaymentHistoryWorker {

	private final PaymentHistoryService history;

	public PaymentHistoryWorker(PaymentHistoryService history) {
		this.history = history;
	}

	@Scheduled(fixedDelayString = "${noduq.history.tick-ms:2000}")
	public void tick() {
		history.advanceRunning(Duration.ofSeconds(12));
	}
}
