package com.noduq.adapter.outbound.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevenueCatPlaySubscriptionsTest {

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void keepsOnlyPlaySubscriptionsThatWillRenew() throws Exception {
		String body = """
				{
				  "subscriber": {
				    "subscriptions": {
				      "monthly": {
				        "store": "play_store",
				        "store_transaction_id": "GPA.1111",
				        "expires_date": "2099-01-01T00:00:00Z",
				        "unsubscribe_detected_at": null,
				        "refunded_at": null
				      },
				      "already": {
				        "store": "play_store",
				        "store_transaction_id": "GPA.2222",
				        "expires_date": "2099-01-01T00:00:00Z",
				        "unsubscribe_detected_at": "2026-01-01T00:00:00Z"
				      },
				      "test": {
				        "store": "test_store",
				        "store_transaction_id": "test_tx",
				        "expires_date": "2099-01-01T00:00:00Z"
				      }
				    }
				  }
				}
				""";

		List<String> ids = RevenueCatPlaySubscriptions.renewingPlayTransactions(
				json.readTree(body),
				Instant.parse("2026-09-25T00:00:00Z"));

		assertEquals(List.of("GPA.1111"), ids);
	}

	@Test
	void emptyWhenThereIsNoSubscriber() throws Exception {
		List<String> ids = RevenueCatPlaySubscriptions.renewingPlayTransactions(
				json.readTree("{}"),
				Instant.parse("2026-09-25T00:00:00Z"));
		assertTrue(ids.isEmpty());
	}
}
