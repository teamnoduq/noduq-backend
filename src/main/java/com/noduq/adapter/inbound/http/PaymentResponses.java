package com.noduq.adapter.inbound.http;

import com.noduq.domain.payments.Device;
import com.noduq.domain.payments.PaymentAnnouncement;
import com.noduq.domain.payments.PaymentNotice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentResponses {

	private PaymentResponses() {
	}

	public record PaymentNoticeResponse(
			UUID id,
			String source,
			String payerName,
			BigDecimal amount,
			String amountLabel,
			String currency,
			Instant occurredAt,
			Instant receivedAt,
			boolean readable,
			boolean confirmedByEmail) {

		static PaymentNoticeResponse from(PaymentNotice notice) {
			return new PaymentNoticeResponse(
					notice.id(),
					notice.source().dbValue(),
					notice.payerName(),
					notice.amount(),
					notice.amount() == null ? null : PaymentAnnouncement.pesos(notice.amount()),
					notice.currency(),
					notice.occurredAt(),
					notice.receivedAt(),
					notice.readable(),
					notice.confirmedByEmail());
		}
	}

	public record PaymentFeedResponse(List<PaymentNoticeResponse> notices) {

		static PaymentFeedResponse from(List<PaymentNotice> notices) {
			return new PaymentFeedResponse(notices.stream().map(PaymentNoticeResponse::from).toList());
		}
	}

	/**
	 * {@code outcome} is {@code stored}, {@code duplicate} or {@code ignored_sender}. All three
	 * are answered with 200 so the phone never retries a message we already dealt with.
	 */
	public record SmsIngestResponse(String outcome, PaymentNoticeResponse notice) {

		static SmsIngestResponse from(String outcome, PaymentNotice notice) {
			return new SmsIngestResponse(outcome, notice == null ? null : PaymentNoticeResponse.from(notice));
		}
	}

	public record DeviceResponse(UUID id, String platform, boolean smsReader, Instant lastSeenAt) {

		static DeviceResponse from(Device device) {
			return new DeviceResponse(device.id(), device.platform(), device.smsReader(), device.lastSeenAt());
		}
	}
}
