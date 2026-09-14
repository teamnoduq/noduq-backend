package com.noduq.adapter.inbound.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.noduq.application.identity.OrganizationPlanService;
import com.noduq.application.identity.OwnerAccountService;
import com.noduq.domain.identity.OwnerWorkspace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/billing")
public class BillingController {

	private final OrganizationPlanService plans;
	private final OwnerAccountService owners;
	private final String webhookAuth;

	public BillingController(
			OrganizationPlanService plans,
			OwnerAccountService owners,
			@Value("${noduq.billing.webhook-auth:}") String webhookAuth) {
		this.plans = plans;
		this.owners = owners;
		this.webhookAuth = webhookAuth == null ? "" : webhookAuth.trim();
	}

	@PostMapping("/activate")
	IdentityResponses.PlanResponse activate(Authentication authentication) {
		OwnerWorkspace workspace = owners.requireWorkspace(OwnerAuth.userId(authentication));
		workspace.requireOwner();
		return IdentityResponses.PlanResponse.from(
				plans.activateFromClient(workspace.organization().id(), "noduq_sms"));
	}

	@PostMapping("/revenuecat")
	void revenueCat(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestBody JsonNode body) {
		if (!webhookAuth.isBlank()) {
			String got = authorization == null ? "" : authorization.trim();
			if (!webhookAuth.equals(got)) {
				throw com.noduq.domain.identity.IdentityException.unauthorized("Webhook inválido.");
			}
		}
		plans.applyRevenueCatEvent(body);
	}
}
