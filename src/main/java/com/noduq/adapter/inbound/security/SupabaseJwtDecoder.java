package com.noduq.adapter.inbound.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User access tokens are ES256 after the JWT Signing Keys migration.
 * Spring's Jwt type cannot store Supabase nested claims, so the signature is
 * verified with Nimbus/JWKS (or GoTrue) and only scalar claims are kept.
 */
final class SupabaseJwtDecoder implements JwtDecoder {

	private static final Logger log = LoggerFactory.getLogger(SupabaseJwtDecoder.class);

	private final DefaultJWTProcessor<SecurityContext> jwksProcessor;
	private final byte[] hmacSecret;
	private final String supabaseUrl;
	private final String anonKey;
	private final String serviceRoleKey;
	private final RestClient restClient;

	SupabaseJwtDecoder(String supabaseUrl, String jwtSecret, String serviceRoleKey) {
		this(supabaseUrl, jwtSecret, "", serviceRoleKey, restClient());
	}

	SupabaseJwtDecoder(String supabaseUrl, String jwtSecret, String anonKey, String serviceRoleKey) {
		this(supabaseUrl, jwtSecret, anonKey, serviceRoleKey, restClient());
	}

	SupabaseJwtDecoder(String supabaseUrl, String jwtSecret, String serviceRoleKey, RestClient restClient) {
		this(supabaseUrl, jwtSecret, "", serviceRoleKey, restClient);
	}

	SupabaseJwtDecoder(
			String supabaseUrl, String jwtSecret, String anonKey, String serviceRoleKey, RestClient restClient) {
		this.supabaseUrl = supabaseUrl.replaceAll("/$", "");
		this.anonKey = anonKey == null ? "" : anonKey;
		this.serviceRoleKey = serviceRoleKey == null ? "" : serviceRoleKey;
		this.restClient = restClient;
		this.hmacSecret = hmacSecret(jwtSecret);
		this.jwksProcessor = jwksProcessor(this.supabaseUrl);
	}

	@Override
	public Jwt decode(String token) throws JwtException {
		String alg = algorithm(token);
		if (hmac(alg)) {
			if (hmacSecret != null) {
				try {
					return verifyHmac(token);
				} catch (JwtException hmacError) {
					log.warn("Supabase HS256 local verify failed, asking GoTrue: {}", hmacError.getMessage());
					return decodeViaGoTrue(token);
				}
			}
			return decodeViaGoTrue(token);
		}
		try {
			return verifyJwks(token);
		} catch (JwtException jwksError) {
			log.warn("Supabase JWKS verify failed alg={}: {}", alg, jwksError.getMessage());
			try {
				return decodeViaGoTrue(token);
			} catch (JwtException goTrueError) {
				log.warn("Supabase GoTrue verify failed: {}", goTrueError.getMessage());
				throw jwksError;
			}
		}
	}

	private Jwt verifyHmac(String token) {
		try {
			SignedJWT parsed = SignedJWT.parse(token);
			if (!parsed.verify(new MACVerifier(hmacSecret))) {
				throw new JwtException("Supabase HS256 signature is invalid");
			}
			return springJwt(parsed);
		} catch (JwtException ex) {
			throw ex;
		} catch (JOSEException | ParseException | RuntimeException ex) {
			throw new JwtException("Supabase HS256 access token was rejected", ex);
		}
	}

	private Jwt verifyJwks(String token) {
		try {
			jwksProcessor.process(token, null);
			return springJwt(SignedJWT.parse(token));
		} catch (JwtException ex) {
			throw ex;
		} catch (BadJOSEException | JOSEException | ParseException | RuntimeException ex) {
			throw new JwtException("Supabase JWKS access token was rejected", ex);
		}
	}

	private Jwt decodeViaGoTrue(String token) {
		String apikey = goTrueApiKey();
		if (apikey.isBlank()) {
			throw new JwtException("Supabase access token cannot be verified without SUPABASE_ANON_KEY or SUPABASE_SERVICE_ROLE_KEY");
		}
		try {
			restClient.get()
					.uri(supabaseUrl + "/auth/v1/user")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.header("apikey", apikey)
					.retrieve()
					.toBodilessEntity();
			return springJwt(SignedJWT.parse(token));
		} catch (JwtException ex) {
			throw ex;
		} catch (RestClientResponseException ex) {
			log.warn("Supabase GoTrue rejected access token: status={}", ex.getStatusCode().value());
			throw new JwtException("Supabase access token was rejected (" + ex.getStatusCode().value() + ")");
		} catch (ParseException | RuntimeException ex) {
			log.warn("Supabase GoTrue verify failed: {}", ex.getMessage());
			throw new JwtException("Supabase access token was rejected", ex);
		}
	}

	private String goTrueApiKey() {
		if (jwtShaped(serviceRoleKey)) {
			return serviceRoleKey;
		}
		if (jwtShaped(anonKey)) {
			return anonKey;
		}
		if (!serviceRoleKey.isBlank()) {
			return serviceRoleKey;
		}
		return anonKey;
	}

	private static boolean jwtShaped(String key) {
		return key != null && key.startsWith("eyJ");
	}

	private static Jwt springJwt(SignedJWT parsed) throws ParseException {
		JWTClaimsSet set = parsed.getJWTClaimsSet();
		Instant issuedAt = set.getIssueTime() != null ? set.getIssueTime().toInstant() : Instant.now();
		Instant expiresAt = set.getExpirationTime() != null ? set.getExpirationTime().toInstant() : issuedAt.plusSeconds(3600);
		if (expiresAt.isBefore(Instant.now().minusSeconds(30))) {
			throw new JwtException("Supabase access token has expired");
		}
		if (set.getSubject() == null || set.getSubject().isBlank()) {
			throw new JwtException("Supabase access token is missing sub");
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
		return new Jwt(parsed.getParsedString(), issuedAt, expiresAt, headers, claims);
	}

	private static boolean hmac(String alg) {
		return alg != null && alg.startsWith("HS");
	}

	private static byte[] hmacSecret(String jwtSecret) {
		if (jwtSecret == null || jwtSecret.isBlank()) {
			return null;
		}
		return jwtSecret.getBytes(StandardCharsets.UTF_8);
	}

	private static DefaultJWTProcessor<SecurityContext> jwksProcessor(String supabaseUrl) {
		try {
			JWKSource<SecurityContext> jwkSource =
					new RemoteJWKSet<>(URI.create(supabaseUrl + "/auth/v1/.well-known/jwks.json").toURL());
			DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
			processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, jwkSource));
			processor.setJWTClaimsSetVerifier((claims, context) -> {
				Date exp = claims.getExpirationTime();
				if (exp != null && exp.toInstant().isBefore(Instant.now().minusSeconds(30))) {
					throw new BadJWTException("Expired JWT");
				}
			});
			return processor;
		} catch (MalformedURLException ex) {
			throw new IllegalArgumentException("Invalid SUPABASE_URL", ex);
		}
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
