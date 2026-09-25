package com.noduq.application.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noduq.domain.identity.OrganizationPlan;
import com.noduq.domain.identity.port.OrganizationPlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationPlanServiceTest {

	@Mock
	private OrganizationPlanRepository plans;

	@InjectMocks
	private OrganizationPlanService service;

	@Test
	void initialPurchaseMarksTheOrgActive() throws Exception {
		UUID org = UUID.randomUUID();
		var json = new ObjectMapper().readTree("""
				{
				  "event": {
				    "id": "evt-1",
				    "type": "INITIAL_PURCHASE",
				    "app_user_id": "%s",
				    "product_id": "noduq_sms_monthly",
				    "entitlement_ids": ["noduq_sms"],
				    "expiration_at_ms": 4102444800000
				  }
				}
				""".formatted(org));

		service.applyRevenueCatEvent(json);

		ArgumentCaptor<OrganizationPlan> captor = ArgumentCaptor.forClass(OrganizationPlan.class);
		verify(plans).upsert(captor.capture());
		OrganizationPlan plan = captor.getValue();
		assertEquals(org, plan.organizationId());
		assertEquals("noduq_sms", plan.entitlement());
		assertEquals("active", plan.status());
		assertTrue(plan.active(java.time.Instant.now()));
	}

	@Test
	void clientCancelKeepsAccessUntilPeriodEnds() {
		UUID org = UUID.randomUUID();
		Instant ends = Instant.now().plusSeconds(86_400);
		when(plans.find(org)).thenReturn(Optional.of(new OrganizationPlan(
				org, OrganizationPlan.SMS, "active", ends, "noduq_sms_monthly_24900", "evt-1")));

		OrganizationPlan cancelled = service.cancelFromClient(org);

		assertEquals("cancelled", cancelled.status());
		assertTrue(cancelled.active(Instant.now()));
		assertEquals(ends, cancelled.periodEndsAt());
	}

	@Test
	void clientReactivateRestoresRenewingStatus() {
		UUID org = UUID.randomUUID();
		Instant ends = Instant.now().plusSeconds(86_400);
		when(plans.find(org)).thenReturn(Optional.of(new OrganizationPlan(
				org, OrganizationPlan.SMS, "cancelled", ends, "noduq_sms_monthly_24900", "evt-1")));

		OrganizationPlan restored = service.reactivateFromClient(org);

		assertEquals("active", restored.status());
		assertTrue(restored.active(Instant.now()));
	}
}
