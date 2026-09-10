package com.noduq.adapter.outbound.security;

import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.port.LoginThrottle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryLoginThrottle implements LoginThrottle {

	private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
	private final int maxAttempts;
	private final long lockoutSeconds;

	public InMemoryLoginThrottle(
			@Value("${noduq.employee.max-login-attempts}") int maxAttempts,
			@Value("${noduq.employee.lockout-minutes}") long lockoutMinutes) {
		this.maxAttempts = maxAttempts;
		this.lockoutSeconds = lockoutMinutes * 60;
	}

	@Override
	public void guard(String key) {
		Attempt attempt = attempts.get(key);
		if (attempt != null && attempt.lockedUntil != null && attempt.lockedUntil.isAfter(Instant.now())) {
			throw IdentityException.locked("Demasiados intentos. Espera un momento e inténtalo de nuevo.");
		}
	}

	@Override
	public void recordFailure(String key) {
		attempts.compute(key, (ignored, current) -> {
			Attempt next = current == null ? new Attempt() : current;
			if (next.lockedUntil != null && next.lockedUntil.isAfter(Instant.now())) {
				return next;
			}
			if (next.lockedUntil != null) {
				next.failures = 0;
				next.lockedUntil = null;
			}
			next.failures++;
			if (next.failures >= maxAttempts) {
				next.lockedUntil = Instant.now().plusSeconds(lockoutSeconds);
			}
			return next;
		});
	}

	@Override
	public void clear(String key) {
		attempts.remove(key);
	}

	private static final class Attempt {
		private int failures;
		private Instant lockedUntil;
	}
}
