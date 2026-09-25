package com.noduq.domain.payments;

import java.time.Instant;
import java.util.UUID;

public record PaymentHistoryImport(
		UUID organizationId,
		String status,
		Instant windowFrom,
		Instant windowUntil,
		int totalMessages,
		int processedMessages,
		int storedMessages,
		String pageToken,
		Instant startedAt,
		Instant finishedAt) {

	public static final String DEFERRED = "deferred";
	public static final String RUNNING = "running";
	public static final String DONE = "done";

	public int percent() {
		if (DONE.equals(status)) {
			return 100;
		}
		if (totalMessages <= 0) {
			return 0;
		}
		return (int) Math.min(99, processedMessages * 100L / totalMessages);
	}
}
