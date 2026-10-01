package com.noduq.adapter.inbound.http;

import com.noduq.application.payments.PaymentFeedService;
import com.noduq.application.payments.PaymentHistoryService;
import com.noduq.application.payments.PaymentIngestService;
import com.noduq.application.payments.PaymentStatsService;
import com.noduq.domain.payments.PaymentHistoryImport;
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
import java.util.UUID;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

	private final PaymentFeedService feed;
	private final PaymentIngestService ingest;
	private final PaymentHistoryService history;
	private final PaymentStatsService stats;

	public PaymentController(
			PaymentFeedService feed,
			PaymentIngestService ingest,
			PaymentHistoryService history,
			PaymentStatsService stats) {
		this.feed = feed;
		this.ingest = ingest;
		this.history = history;
		this.stats = stats;
	}

	@GetMapping
	PaymentResponses.PaymentFeedResponse list(
			Authentication authentication,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Instant since,
			@RequestParam(required = false) Instant until,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) Instant before,
			@RequestParam(required = false) UUID beforeId,
			@RequestParam(required = false) Integer offset) {
		return PaymentResponses.PaymentFeedResponse.from(
				feed.forOwner(
						OwnerAuth.userId(authentication),
						limit,
						since,
						until,
						q,
						source,
						before,
						beforeId,
						offset));
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

	@GetMapping("/stats")
	PaymentStatsService.Report stats(
			Authentication authentication,
			@RequestParam int year,
			@RequestParam(required = false) Integer month) {
		return stats.forOwner(OwnerAuth.userId(authentication), year, month);
	}

	@GetMapping("/history")
	HistoryResponse history(Authentication authentication) {
		return present(authentication, history.status(OwnerAuth.userId(authentication)));
	}

	@PostMapping("/history/defer")
	HistoryResponse deferHistory(Authentication authentication) {
		return present(authentication, history.defer(OwnerAuth.userId(authentication)));
	}

	@PostMapping("/history/start")
	HistoryResponse startHistory(Authentication authentication) {
		return present(authentication, history.start(OwnerAuth.userId(authentication)));
	}

	@PostMapping("/history/batches")
	HistoryResponse historyBatch(Authentication authentication) {
		return present(authentication, history.pullNext(OwnerAuth.userId(authentication)));
	}

	private HistoryResponse present(Authentication authentication, PaymentHistoryImport row) {
		return HistoryResponse.from(row, history.earliest(OwnerAuth.userId(authentication)));
	}

	public record HistoryResponse(
			String status,
			Instant windowFrom,
			Instant windowUntil,
			int total,
			int processed,
			int stored,
			int percent,
			Instant finishedAt,
			Instant earliestAt) {

		static HistoryResponse from(PaymentHistoryImport row, Instant earliestAt) {
			if (row == null) {
				return new HistoryResponse("available", null, null, 0, 0, 0, 0, null, earliestAt);
			}
			return new HistoryResponse(
					row.status(),
					row.windowFrom(),
					row.windowUntil(),
					row.totalMessages(),
					row.processedMessages(),
					row.storedMessages(),
					row.percent(),
					row.finishedAt(),
					earliestAt);
		}
	}
}
