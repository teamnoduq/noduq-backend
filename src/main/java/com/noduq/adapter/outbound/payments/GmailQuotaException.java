package com.noduq.adapter.outbound.payments;

/**
 * Gmail refused the read because the per-minute quota is spent. The message body never arrived,
 * so the caller must keep the id and try again after the minute turns.
 */
public class GmailQuotaException extends RuntimeException {

	public GmailQuotaException(Throwable cause) {
		super(cause);
	}
}
