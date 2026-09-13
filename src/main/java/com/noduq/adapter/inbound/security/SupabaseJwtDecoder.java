package com.noduq.adapter.inbound.security;

import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;

/**
 * Supabase still signs many access tokens with the legacy HS256 secret while JWKS
 * only publishes the newer ES256 key. Accept both.
 */
final class SupabaseJwtDecoder implements JwtDecoder {

	private final JwtDecoder jwksDecoder;
	private final JwtDecoder hmacDecoder;

	SupabaseJwtDecoder(String supabaseUrl, String jwtSecret) {
		String issuer = supabaseUrl.replaceAll("/$", "") + "/auth/v1";
		OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefault();
		NimbusJwtDecoder jwks = NimbusJwtDecoder.withJwkSetUri(issuer + "/.well-known/jwks.json").build();
		jwks.setJwtValidator(validator);
		this.jwksDecoder = jwks;
		this.hmacDecoder = hmacDecoder(jwtSecret, validator);
	}

	@Override
	public Jwt decode(String token) throws JwtException {
		String alg = algorithm(token);
		if (hmacDecoder != null && (alg == null || alg.startsWith("HS"))) {
			try {
				return hmacDecoder.decode(token);
			} catch (JwtException hmacError) {
				if (alg != null && alg.startsWith("HS")) {
					throw hmacError;
				}
			}
		}
		return jwksDecoder.decode(token);
	}

	private static JwtDecoder hmacDecoder(String jwtSecret, OAuth2TokenValidator<Jwt> validator) {
		if (jwtSecret == null || jwtSecret.isBlank()) {
			return null;
		}
		SecretKeySpec key = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(validator);
		return decoder;
	}

	private static String algorithm(String token) {
		try {
			return SignedJWT.parse(token).getHeader().getAlgorithm().getName();
		} catch (ParseException | RuntimeException ignored) {
			return null;
		}
	}
}
