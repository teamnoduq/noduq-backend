package com.noduq.adapter.inbound.http;

import com.noduq.application.payments.DeviceRegistrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/devices")
public class DeviceController {

	private final DeviceRegistrationService devices;

	public DeviceController(DeviceRegistrationService devices) {
		this.devices = devices;
	}

	@PostMapping
	PaymentResponses.DeviceResponse register(Authentication authentication, @Valid @RequestBody RegisterRequest body) {
		return PaymentResponses.DeviceResponse.from(devices.registerForOwner(
				OwnerAuth.userId(authentication),
				body.pushToken(),
				body.platform(),
				Boolean.TRUE.equals(body.smsReader())));
	}

	@PostMapping("/forget")
	void forget(Authentication authentication, @Valid @RequestBody ForgetRequest body) {
		devices.forgetForOwner(OwnerAuth.userId(authentication), body.pushToken());
	}

	/** {@code smsReader} marks the phone holding the SIM the bank writes to. */
	public record RegisterRequest(@NotBlank String pushToken, String platform, Boolean smsReader) {
	}

	public record ForgetRequest(@NotBlank String pushToken) {
	}
}
