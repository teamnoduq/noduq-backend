package com.noduq.adapter.inbound.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * User access tokens are ES256 after the JWT Signing Keys migration.
 * Spring's Jwt type cannot store Supabase nested claims, so the signature is
 * verified with JWKS (or GoTrue) and only scalar claims are kept.
 */
final class SupabaseJwtDecoder implements JwtDecoder {

	private static final Logger log = LoggerFactory.getLogger(SupabaseJwtDecoder.class);

	private final byte[] hmacSecret;
	private final String supabaseUrl;
	private final String anonKey;
	private final String serviceRoleKey;
	private final RestClient restClient;
	private volatile JWKSet jwkSet;

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
		log.info("SupabaseJwtDecoder ready url={} hmac={} anon={} serviceRole={}",
				this.supabaseUrl,
				hmacSecret != null,
				keyKind(this.anonKey),
				keyKind(this.serviceRoleKey));
	}

	@Override
	public Jwt decode(String token) throws JwtException {
		String alg = algorithm(token);
		log.info("Supabase access token alg={}", alg);
		try {
			Jwt jwt = decodeToken(token, alg);
			log.info("Supabase token verified alg={} sub={}", alg, jwt.getSubject());
			return jwt;
		} catch (JwtException ex) {
			log.info("Supabase decode failed alg={} msg={}", alg, ex.getMessage());
			throw ex;
		} catch (RuntimeException ex) {
			log.info("Supabase decode crashed alg={} type={} msg={}", alg, ex.getClass().getName(), ex.getMessage());
			throw new JwtException("Supabase access token was rejected: " + ex.getMessage(), ex);
		}
	}

	private Jwt decodeToken(String token, String alg) {
		if (hmac(alg)) {
			if (hmacSecret != null) {
				try {
					return verifyHmac(token);
				} catch (JwtException hmacError) {
					log.info("Supabase HS256 local verify failed, asking GoTrue: {}", hmacError.getMessage());
					return decodeViaGoTrue(token);
				}
			}
			return decodeViaGoTrue(token);
		}
		try {
			return verifyJwks(token);
		} catch (JwtException jwksError) {
			log.info("Supabase JWKS verify failed alg={}: {}", alg, jwksError.getMessage());
			this.jwkSet = null;
			try {
				return decodeViaGoTrue(token);
			} catch (JwtException goTrueError) {
				log.info("Supabase GoTrue verify failed: {}", goTrueError.getMessage());
				throw new JwtException(
						"JWKS: " + jwksError.getMessage() + "; GoTrue: " + goTrueError.getMessage(),
						goTrueError);
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
			SignedJWT parsed = SignedJWT.parse(token);
			ECKey key = es256Key(parsed.getHeader().getKeyID());
			if (!parsed.verify(new ECDSAVerifier(key.toECPublicKey()))) {
				throw new JwtException("Supabase ES256 signature is invalid kid=" + parsed.getHeader().getKeyID());
			}
			return springJwt(parsed);
		} catch (JwtException ex) {
			throw ex;
		} catch (JOSEException | ParseException | RuntimeException ex) {
			throw new JwtException("Supabase JWKS access token was rejected: " + ex.getMessage(), ex);
		}
	}

	private ECKey es256Key(String kid) {
		JWKSet set = jwkSet();
		JWK jwk = kid != null ? set.getKeyByKeyId(kid) : null;
		if (jwk == null && set.getKeys().size() == 1) {
			jwk = set.getKeys().getFirst();
		}
		if (jwk instanceof ECKey ecKey) {
			return ecKey;
		}
		throw new JwtException("Supabase JWKS has no ES256 key for kid=" + kid);
	}

	private JWKSet jwkSet() {
		JWKSet cached = this.jwkSet;
		if (cached != null) {
			return cached;
		}
		synchronized (this) {
			if (this.jwkSet != null) {
				return this.jwkSet;
			}
			String body = restClient.get()
					.uri(supabaseUrl + "/auth/v1/.well-known/jwks.json")
					.retrieve()
					.body(String.class);
			if (body == null || body.isBlank()) {
				throw new JwtException("Supabase JWKS was empty");
			}
			try {
				this.jwkSet = JWKSet.parse(stripWebCryptoFields(body));
			} catch (ParseException stripped) {
				try {
					this.jwkSet = JWKSet.parse(body);
				} catch (ParseException retry) {
					throw new JwtException("Supabase JWKS could not be parsed", retry);
				}
			}
			log.info("Supabase JWKS loaded keys={} kids={}",
					this.jwkSet.getKeys().size(),
					this.jwkSet.getKeys().stream().map(JWK::getKeyID).toList());
			return this.jwkSet;
		}
	}

	private Jwt decodeViaGoTrue(String token) {
		List<String> keys = goTrueApiKeys();
		if (keys.isEmpty()) {
			throw new JwtException("Supabase access token cannot be verified without SUPABASE_ANON_KEY or SUPABASE_SERVICE_ROLE_KEY");
		}
		JwtException last = null;
		for (String apikey : keys) {
			try {
				restClient.get()
						.uri(supabaseUrl + "/auth/v1/user")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.header("apikey", apikey)
						.retrieve()
						.toBodilessEntity();
				return springJwt(SignedJWT.parse(token));
			} catch (JwtException ex) {
				last = ex;
			} catch (RestClientResponseException ex) {
				log.info("Supabase GoTrue rejected access token: status={} apikey={}",
						ex.getStatusCode().value(), keyKind(apikey));
				last = new JwtException("Supabase access token was rejected (" + ex.getStatusCode().value() + ")");
			} catch (ParseException | RuntimeException ex) {
				log.info("Supabase GoTrue verify failed apikey={}: {}", keyKind(apikey), ex.getMessage());
				last = new JwtException("Supabase access token was rejected", ex);
			}
		}
		throw last != null ? last : new JwtException("Supabase access token was rejected");
	}

	private List<String> goTrueApiKeys() {
		LinkedHashSet<String> keys = new LinkedHashSet<>();
		if (jwtShaped(serviceRoleKey)) {
			keys.add(serviceRoleKey);
		}
		if (jwtShaped(anonKey)) {
			keys.add(anonKey);
		}
		if (!serviceRoleKey.isBlank()) {
			keys.add(serviceRoleKey);
		}
		if (!anonKey.isBlank()) {
			keys.add(anonKey);
		}
		return List.copyOf(keys);
	}

	private static boolean jwtShaped(String key) {
		return key != null && key.startsWith("eyJ");
	}

	private static String keyKind(String key) {
		if (key == null || key.isBlank()) {
			return "missing";
		}
		if (key.startsWith("eyJ")) {
			return "jwt";
		}
		if (key.startsWith("sb_publishable_")) {
			return "publishable";
		}
		if (key.startsWith("sb_secret_")) {
			return "secret";
		}
		return "other:" + key.length();
	}

	private static String stripWebCryptoFields(String body) {
		return body.replaceAll("\"ext\"\\s*:\\s*true\\s*,", "")
				.replaceAll(",\\s*\"ext\"\\s*:\\s*true", "")
				.replaceAll("\"key_ops\"\\s*:\\s*\\[[^\\]]*\\]\\s*,", "")
				.replaceAll(",\\s*\"key_ops\"\\s*:\\s*\\[[^\\]]*\\]", "");
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
			claims.put("iss", set.getIssuer());
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
		try {
			return new Jwt(parsed.getParsedString(), issuedAt, expiresAt, headers, claims);
		} catch (IllegalArgumentException ex) {
			throw new JwtException("Spring Jwt rejected token claims: " + ex.getMessage(), ex);
		}
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

	private static RestClient restClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(4000);
		factory.setReadTimeout(4000);
		return RestClient.builder()
				.requestFactory(factory)
				.defaultHeader(HttpHeaders.USER_AGENT, "noduq-backend")
				.build();
	}

	private static String algorithm(String token) {
		try {
			return SignedJWT.parse(token).getHeader().getAlgorithm().getName();
		} catch (ParseException | RuntimeException ignored) {
			return null;
		}
	}
}
