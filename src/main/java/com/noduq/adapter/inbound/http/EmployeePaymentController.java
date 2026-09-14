package com.noduq.adapter.inbound.http;

import com.noduq.adapter.inbound.security.EmployeeCaller;
import com.noduq.application.payments.DeviceRegistrationService;
import com.noduq.application.payments.PaymentFeedService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The counter side. Employees read the notices of their local and register their phone for
 * the push; they never touch the bank or the SMS.
 */
@RestController
@RequestMapping("/v1/employee")
public class EmployeePaymentController {

	private final PaymentFeedService feed;
	private final DeviceRegistrationService devices;

	public EmployeePaymentController(PaymentFeedService feed, DeviceRegistrationService devices) {
		this.feed = feed;
		this.devices = devices;
	}

	@GetMapping("/payments")
	PaymentResponses.PaymentFeedResponse list(
			@AuthenticationPrincipal EmployeeCaller caller,
			@RequestParam(required = false) Integer limit) {
		return PaymentResponses.PaymentFeedResponse.from(
				feed.forEmployee(caller.organizationId(), caller.employeeId(), limit));
	}

	@PostMapping("/devices")
	PaymentResponses.DeviceResponse register(
			@AuthenticationPrincipal EmployeeCaller caller,
			@Valid @RequestBody RegisterRequest body) {
		return PaymentResponses.DeviceResponse.from(devices.registerForEmployee(
				caller.organizationId(),
				caller.employeeId(),
				body.pushToken(),
				body.platform()));
	}

	@PostMapping("/devices/forget")
	void forget(@AuthenticationPrincipal EmployeeCaller caller, @Valid @RequestBody ForgetRequest body) {
		devices.forgetForEmployee(caller.organizationId(), body.pushToken());
	}

	public record RegisterRequest(@NotBlank String pushToken, String platform) {
	}

	public record ForgetRequest(@NotBlank String pushToken) {
	}
}
