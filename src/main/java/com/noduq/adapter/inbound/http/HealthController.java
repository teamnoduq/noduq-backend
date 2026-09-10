package com.noduq.adapter.inbound.http;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

	@GetMapping("/health")
	Map<String, String> health() {
		return Map.of("status", "ok");
	}
}
