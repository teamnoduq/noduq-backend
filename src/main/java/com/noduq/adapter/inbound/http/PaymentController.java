package com.noduq.adapter.inbound.http;

import com.noduq.application.payments.PaymentFeedService;
import com.noduq.application.payments.PaymentIngestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

	private final PaymentFeedService feed;
	private final PaymentIngestService ingest;

	public PaymentController(PaymentFeedService feed, PaymentIngestService ingest) {
		this.feed = feed;
		this.ingest = ingest;
	}

	@GetMapping
	PaymentResponses.PaymentFeedResponse list(
			Authentication authentication,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Instant since,
			@RequestParam(required = false) Instant until,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String source) {
		return PaymentResponses.PaymentFeedResponse.from(
				feed.forOwner(OwnerAuth.userId(authentication), limit, since, until, q, source));
	}

	/** The owner's phone forwards what the bank sent it; this decides whether it counts. */
	@PostMapping("/sms")
	PaymentResponses.SmsIngestResponse sms(Authentication authentication, @Valid @RequestBody SmsRequest body) {
		PaymentIngestService.Ingested ingested = ingest.ingestOwnerSms(
				OwnerAuth.userId(authentication),
				body.sender(),
				body.message(),
				body.sentAt());
		return PaymentResponses.SmsIngestResponse.from(
				ingested.outcome().name().toLowerCase(Locale.ROOT),
				ingested.notice());
	}

	public record SmsRequest(@NotBlank String sender, @NotBlank String message, Instant sentAt) {
	}
}
