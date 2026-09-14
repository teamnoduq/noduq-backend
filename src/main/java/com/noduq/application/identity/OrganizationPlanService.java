package com.noduq.application.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.OrganizationPlan;
import com.noduq.domain.identity.port.OrganizationPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrganizationPlanService {

	private static final Logger log = LoggerFactory.getLogger(OrganizationPlanService.class);

	private static final Duration ACTIVATE_BRIDGE = Duration.ofDays(35);

	private final OrganizationPlanRepository plans;

	public OrganizationPlanService(OrganizationPlanRepository plans) {
		this.plans = plans;
	}

	public Optional<OrganizationPlan> find(UUID organizationId) {
		return plans.find(organizationId);
	}

	public boolean allowsSms(UUID organizationId) {
		return plans.find(organizationId).map(plan -> plan.allowsSms(Instant.now())).orElse(false);
	}

	public boolean allowsEmail(UUID organizationId) {
		return plans.find(organizationId).map(plan -> plan.allowsEmail(Instant.now())).orElse(false);
	}

	public boolean isActive(UUID organizationId) {
		return plans.find(organizationId).map(plan -> plan.active(Instant.now())).orElse(false);
	}

	public void requireActive(UUID organizationId) {
		if (!isActive(organizationId)) {
			throw IdentityException.planRequired();
		}
	}

	public OrganizationPlan activateFromClient(UUID organizationId, String entitlement) {
		String resolved = resolveEntitlement(entitlement, null);
		OrganizationPlan plan = new OrganizationPlan(
				organizationId,
				resolved,
				"active",
				Instant.now().plus(ACTIVATE_BRIDGE),
				null,
				"client");
		plans.upsert(plan);
		log.info("Plan activated from client org={} entitlement={}", organizationId, resolved);
		return plan;
	}

	public void applyRevenueCatEvent(JsonNode root) {
		JsonNode event = root.path("event");
		if (event.isMissingNode() || event.isNull()) {
			event = root;
		}
		String userId = text(event, "app_user_id");
		if (userId == null) {
			log.info("RevenueCat event without app_user_id");
			return;
		}
		UUID organizationId;
		try {
			organizationId = UUID.fromString(userId);
		} catch (IllegalArgumentException ex) {
			log.info("RevenueCat event for unknown app_user_id={}", userId);
			return;
		}
		String type = text(event, "type");
		if (type == null) {
			return;
		}
		String product = text(event, "product_id");
		String entitlement = firstEntitlement(event.path("entitlement_ids"));
		if (entitlement == null) {
			entitlement = resolveEntitlement(null, product);
		}
		Instant ends = millis(event, "expiration_at_ms");
		String eventId = text(event, "id");
		String status = statusFor(type, ends);
		if (entitlement == null) {
			entitlement = OrganizationPlan.SMS;
		}
		OrganizationPlan plan = new OrganizationPlan(organizationId, entitlement, status, ends, product, eventId);
		plans.upsert(plan);
		log.info("RevenueCat {} org={} status={} entitlement={}", type, organizationId, status, entitlement);
	}

	private static String statusFor(String type, Instant ends) {
		String normalized = type.toUpperCase(Locale.ROOT);
		if (normalized.contains("EXPIR")) {
			return "expired";
		}
		if (normalized.contains("CANCEL")) {
			return "cancelled";
		}
		if (ends != null && !ends.isAfter(Instant.now())) {
			return "expired";
		}
		return "active";
	}

	private static String resolveEntitlement(String entitlement, String product) {
		if (entitlement != null && !entitlement.isBlank()) {
			String value = entitlement.trim();
			if (OrganizationPlan.EMAIL.equals(value) || OrganizationPlan.SMS.equals(value)) {
				return value;
			}
		}
		if (product != null && product.toLowerCase(Locale.ROOT).contains("email")) {
			return OrganizationPlan.EMAIL;
		}
		return OrganizationPlan.SMS;
	}

	private static String firstEntitlement(JsonNode ids) {
		if (ids == null || !ids.isArray() || ids.isEmpty()) {
			return null;
		}
		JsonNode first = ids.get(0);
		return first == null || first.isNull() ? null : first.asText();
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		String text = value.asText();
		return text == null || text.isBlank() ? null : text;
	}

	private static Instant millis(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull() || !value.canConvertToLong()) {
			return null;
		}
		long ms = value.asLong();
		return ms <= 0 ? null : Instant.ofEpochMilli(ms);
	}
}
