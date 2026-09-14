package com.noduq.adapter.inbound.http;

import com.noduq.application.payments.GmailLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GmailPaymentPoller {

	private static final Logger log = LoggerFactory.getLogger(GmailPaymentPoller.class);

	private final GmailLinkService gmail;

	public GmailPaymentPoller(GmailLinkService gmail) {
		this.gmail = gmail;
	}

	@Scheduled(fixedDelayString = "${noduq.gmail.poll-ms:45000}")
	public void poll() {
		if (!gmail.configured()) {
			return;
		}
		try {
			gmail.pollAll();
		} catch (RuntimeException ex) {
			log.warn("Gmail poller stopped early: {}", ex.toString());
		}
	}
}
