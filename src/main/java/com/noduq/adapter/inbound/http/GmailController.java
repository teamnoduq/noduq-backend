package com.noduq.adapter.inbound.http;

import com.noduq.application.payments.GmailLinkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/v1/gmail")
public class GmailController {

	private final GmailLinkService gmail;

	public GmailController(GmailLinkService gmail) {
		this.gmail = gmail;
	}

	@GetMapping
	StatusResponse status(Authentication authentication) {
		GmailLinkService.Status status = gmail.status(OwnerAuth.userId(authentication));
		return new StatusResponse(status.configured(), status.connected(), status.address());
	}

	@GetMapping("/connect")
	ConnectResponse connect(
			Authentication authentication,
			@RequestParam(value = "returnTo", required = false) String returnTo) {
		boolean web = "web".equalsIgnoreCase(returnTo);
		return new ConnectResponse(gmail.authorizationUrl(OwnerAuth.userId(authentication), web));
	}

	/** Google lands here without our session; the signed state is what names the owner. */
	@GetMapping("/callback")
	ResponseEntity<Void> callback(@RequestParam String code, @RequestParam String state) {
		String redirect = gmail.finish(code, state);
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirect)).build();
	}

	@DeleteMapping
	void disconnect(Authentication authentication) {
		gmail.disconnect(OwnerAuth.userId(authentication));
	}

	public record StatusResponse(boolean configured, boolean connected, String address) {
	}

	public record ConnectResponse(String authorizationUrl) {
	}
}
