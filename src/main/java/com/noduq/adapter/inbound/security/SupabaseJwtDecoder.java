package com.noduq.adapter.inbound.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Supabase JWKS only publishes ES256, while many access tokens are still HS256.
 * Spring's JWKS decoder defaults to RS256, so ES256 must be enabled explicitly.
 * Verify HMAC locally when {@code SUPABASE_JWT_SECRET} is set; otherwise ask GoTrue.
 */
final class SupabaseJwtDecoder implements JwtDecoder {

	private static final Logger log = LoggerFactory.getLogger(SupabaseJwtDecoder.class);

	private final JwtDecoder jwksDecoder;
	private final JwtDecoder hmacDecoder;
	private final String supabaseUrl;
	private final String serviceRoleKey;
	private final RestClient restClient;

	SupabaseJwtDecoder(String supabaseUrl, String jwtSecret, String serviceRoleKey) {
		this(supabaseUrl, jwtSecret, serviceRoleKey, restClient());
	}

	SupabaseJwtDecoder(String supabaseUrl, String jwtSecret, String serviceRoleKey, RestClient restClient) {
		this.supabaseUrl = supabaseUrl.replaceAll("/$", "");
		this.serviceRoleKey = serviceRoleKey == null ? "" : serviceRoleKey;
		this.restClient = restClient;
		OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefault();
		NimbusJwtDecoder jwks = NimbusJwtDecoder.withJwkSetUri(this.supabaseUrl + "/auth/v1/.well-known/jwks.json")
				.jwsAlgorithm(SignatureAlgorithm.ES256)
				.build();
		jwks.setJwtValidator(validator);
		this.jwksDecoder = jwks;
		this.hmacDecoder = hmacDecoder(jwtSecret, validator);
	}

	@Override
	public Jwt decode(String token) throws JwtException {
		String alg = algorithm(token);
		if (hmac(alg)) {
			if (hmacDecoder != null) {
				try {
					return hmacDecoder.decode(token);
				} catch (JwtException hmacError) {
					log.warn("Supabase HS256 local verify failed, asking GoTrue: {}", hmacError.getMessage());
					return decodeViaGoTrue(token);
				}
			}
			return decodeViaGoTrue(token);
		}
		try {
			return jwksDecoder.decode(token);
		} catch (JwtException jwksError) {
			log.warn("Supabase JWKS verify failed alg={}: {}", alg, jwksError.getMessage());
			if (!serviceRoleKey.isBlank()) {
				try {
					return decodeViaGoTrue(token);
				} catch (JwtException goTrueError) {
					log.warn("Supabase GoTrue verify failed: {}", goTrueError.getMessage());
					throw jwksError;
				}
			}
			throw jwksError;
		}
	}

	private Jwt decodeViaGoTrue(String token) {
		if (serviceRoleKey.isBlank()) {
			throw new JwtException("Supabase HS256 access token cannot be verified without SUPABASE_JWT_SECRET");
		}
		try {
			restClient.get()
					.uri(supabaseUrl + "/auth/v1/user")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.header("apikey", serviceRoleKey)
					.retrieve()
					.toBodilessEntity();
			return parseVerifiedByGoTrue(token);
		} catch (JwtException ex) {
			throw ex;
		} catch (RestClientResponseException ex) {
			throw new JwtException("Supabase access token was rejected (" + ex.getStatusCode().value() + ")");
		} catch (ParseException | RuntimeException ex) {
			throw new JwtException("Supabase access token was rejected", ex);
		}
	}

	private static Jwt parseVerifiedByGoTrue(String token) throws ParseException {
		SignedJWT parsed = SignedJWT.parse(token);
		JWTClaimsSet set = parsed.getJWTClaimsSet();
		Instant issuedAt = set.getIssueTime() != null ? set.getIssueTime().toInstant() : Instant.now();
		Instant expiresAt = set.getExpirationTime() != null ? set.getExpirationTime().toInstant() : issuedAt.plusSeconds(3600);
		if (expiresAt.isBefore(Instant.now().minusSeconds(30))) {
			throw new JwtException("Supabase access token has expired");
		}
		Map<String, Object> headers = new HashMap<>();
		if (parsed.getHeader().getAlgorithm() != null) {
			headers.put("alg", parsed.getHeader().getAlgorithm().getName());
		}
		if (parsed.getHeader().getKeyID() != null) {
			headers.put("kid", parsed.getHeader().getKeyID());
		}
		headers.put("typ", "JWT");
		Map<String, Object> claims = new HashMap<>();
		claims.put("sub", set.getSubject());
		if (set.getIssuer() != null) {
			claims.put("iss", set.getIssuer().toString());
		}
		if (set.getAudience() != null && !set.getAudience().isEmpty()) {
			claims.put("aud", List.copyOf(set.getAudience()));
		}
		String email = set.getStringClaim("email");
		if (email != null && !email.isBlank()) {
			claims.put("email", email);
		}
		String role = set.getStringClaim("role");
		if (role != null && !role.isBlank()) {
			claims.put("role", role);
		}
		return new Jwt(token, issuedAt, expiresAt, headers, claims);
	}

	private static boolean hmac(String alg) {
		return alg != null && alg.startsWith("HS");
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

	private static RestClient restClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(3000);
		factory.setReadTimeout(3000);
		return RestClient.builder().requestFactory(factory).build();
	}

	private static String algorithm(String token) {
		try {
			return SignedJWT.parse(token).getHeader().getAlgorithm().getName();
		} catch (ParseException | RuntimeException ignored) {
			return null;
		}
	}
}
