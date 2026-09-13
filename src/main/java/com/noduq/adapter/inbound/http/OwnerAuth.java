package com.noduq.adapter.inbound.http;

import com.noduq.domain.identity.IdentityException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

final class OwnerAuth {

	private OwnerAuth() {
	}

	static Jwt jwt(Authentication authentication) {
		if (authentication instanceof JwtAuthenticationToken token) {
			return token.getToken();
		}
		if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
			return jwt;
		}
		throw IdentityException.unauthorized("Sesión inválida.");
	}

	static UUID userId(Authentication authentication) {
		return userId(jwt(authentication));
	}

	static UUID userId(Jwt jwt) {
		if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
			throw IdentityException.unauthorized("Sesión inválida.");
		}
		try {
			return UUID.fromString(jwt.getSubject());
		} catch (IllegalArgumentException ex) {
			throw IdentityException.unauthorized("Sesión inválida.");
		}
	}
}
