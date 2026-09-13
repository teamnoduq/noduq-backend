package com.noduq.adapter.inbound.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupabaseJwtDecoderTest {

	private static final String SECRET = "n".repeat(32);
	private static final String ISSUER = "https://example.supabase.co/auth/v1";

	@Test
	void decodesLegacyHs256AccessTokens() throws Exception {
		String token = hs256(SECRET, Instant.now().plusSeconds(60), false);
		Jwt jwt = new SupabaseJwtDecoder("https://example.supabase.co", SECRET, "").decode(token);
		assertEquals("owner-1", jwt.getSubject());
		assertEquals(ISSUER, jwt.getIssuer().toString());
	}

	@Test
	void asksGoTrueWhenHs256SecretIsMissing() throws Exception {
		String token = hs256(SECRET, Instant.now().plusSeconds(60), false);
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/auth/v1/user", exchange -> {
			String auth = exchange.getRequestHeaders().getFirst("Authorization");
			byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
			int status = auth != null && auth.equals("Bearer " + token) ? 200 : 401;
			exchange.sendResponseHeaders(status, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			Jwt jwt = new SupabaseJwtDecoder(base, "", "service-role", restClient()).decode(token);
			assertEquals("owner-1", jwt.getSubject());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void goTrueIgnoresNestedSupabaseClaimsSpringCannotStore() throws Exception {
		String token = hs256(SECRET, Instant.now().plusSeconds(60), true);
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/auth/v1/user", exchange -> {
			byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			Jwt jwt = new SupabaseJwtDecoder(base, "", "service-role", restClient()).decode(token);
			assertEquals("owner-1", jwt.getSubject());
			assertEquals("owner@example.com", jwt.getClaimAsString("email"));
			assertEquals("authenticated", jwt.getClaimAsString("role"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void decodesEs256AccessTokensFromJwks() throws Exception {
		assertEquals("owner-1", decodeEs256(false, false).getSubject());
	}

	@Test
	void decodesEs256AccessTokensWithNestedSupabaseClaims() throws Exception {
		Jwt jwt = decodeEs256(true, false);
		assertEquals("owner-1", jwt.getSubject());
		assertEquals("owner@example.com", jwt.getClaimAsString("email"));
		assertEquals("authenticated", jwt.getClaimAsString("role"));
	}

	@Test
	void decodesEs256WhenJwksLooksLikeSupabaseWebCrypto() throws Exception {
		Jwt jwt = decodeEs256(true, true);
		assertEquals("owner-1", jwt.getSubject());
		assertEquals("owner@example.com", jwt.getClaimAsString("email"));
	}

	@Test
	void goTruePrefersLegacyJwtApiKeyOverPublishable() throws Exception {
		String token = hs256(SECRET, Instant.now().plusSeconds(60), true);
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/auth/v1/user", exchange -> {
			String apiKey = exchange.getRequestHeaders().getFirst("apikey");
			byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
			int status = apiKey != null && apiKey.startsWith("eyJ") ? 200 : 401;
			exchange.sendResponseHeaders(status, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			Jwt jwt = new SupabaseJwtDecoder(
					base, "", "sb_publishable_test", "eyJhbGciOiJIUzI1NiJ9.test", restClient())
					.decode(token);
			assertEquals("owner-1", jwt.getSubject());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsHs256WhenSecretAndGoTrueAreMissing() throws Exception {
		String token = hs256(SECRET, Instant.now().plusSeconds(60), false);
		assertThrows(JwtException.class,
				() -> new SupabaseJwtDecoder("https://example.supabase.co", "", "").decode(token));
	}

	private static RestClient restClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(2000);
		factory.setReadTimeout(2000);
		return RestClient.builder().requestFactory(factory).build();
	}

	private static Jwt decodeEs256(boolean nestedClaims, boolean webCryptoJwks) throws Exception {
		ECKey key = new ECKeyGenerator(Curve.P_256).keyID("kid-1").generate();
		String token = es256(key, Instant.now().plusSeconds(60), nestedClaims);
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		String jwkJson = key.toPublicJWK().toJSONString();
		if (webCryptoJwks) {
			jwkJson = jwkJson.substring(0, jwkJson.length() - 1) + ",\"ext\":true,\"key_ops\":[\"verify\"]}";
		}
		String jwks = "{\"keys\":[" + jwkJson + "]}";
		server.createContext("/auth/v1/.well-known/jwks.json", exchange -> {
			byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			return new SupabaseJwtDecoder(base, "", "", restClient()).decode(token);
		} finally {
			server.stop(0);
		}
	}

	private static String hs256(String secret, Instant exp, boolean nestedClaims) throws Exception {
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.subject("owner-1")
				.audience("authenticated")
				.jwtID(UUID.randomUUID().toString())
				.expirationTime(Date.from(exp))
				.claim("email", "owner@example.com")
				.claim("role", "authenticated");
		if (nestedClaims) {
			claims.claim("app_metadata", Map.of("provider", "email", "providers", List.of("email")))
					.claim("user_metadata", Map.of("email_verified", true))
					.claim("amr", List.of(Map.of("method", "password", "timestamp", 1_700_000_000L)));
		}
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
		jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
		return jwt.serialize();
	}

	private static String es256(ECKey key, Instant exp, boolean nestedClaims) throws Exception {
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.subject("owner-1")
				.audience("authenticated")
				.expirationTime(Date.from(exp))
				.issueTime(Date.from(Instant.now()))
				.claim("email", "owner@example.com")
				.claim("role", "authenticated");
		if (nestedClaims) {
			claims.claim("app_metadata", Map.of("provider", "email", "providers", List.of("email")))
					.claim("user_metadata", Map.of("email_verified", true))
					.claim("amr", List.of(Map.of("method", "password", "timestamp", 1_700_000_000L)));
		}
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(),
				claims.build());
		jwt.sign(new ECDSASigner(key));
		return jwt.serialize();
	}

	@Test
	void liveSupabaseJwksShapeParsesAndRejectsForeignSignature() throws Exception {
		String liveJwks = "{\"keys\":[{\"alg\":\"ES256\",\"crv\":\"P-256\",\"ext\":true,\"key_ops\":[\"verify\"],\"kid\":\"e6e4726d-1af6-4526-8a7a-ebf29797114b\",\"kty\":\"EC\",\"use\":\"sig\",\"x\":\"EEfQtvTh-p5YQ0dQuo4ooLRQJqA4Lp0BYRabTN_-HZg\",\"y\":\"q7w7ikA302ZrLBex4X3dUMTLNp6ZS_A9LR9OlbKV8pU\"}]}";
		ECKey other = new ECKeyGenerator(Curve.P_256).keyID("e6e4726d-1af6-4526-8a7a-ebf29797114b").generate();
		String token = es256(other, Instant.now().plusSeconds(60), true);
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/auth/v1/.well-known/jwks.json", exchange -> {
			byte[] body = liveJwks.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			JwtException ex = assertThrows(JwtException.class,
					() -> new SupabaseJwtDecoder(base, "", "", restClient()).decode(token));
			org.junit.jupiter.api.Assertions.assertFalse(
					ex.getMessage().contains("could not be parsed"),
					ex.getMessage());
		} finally {
			server.stop(0);
		}
	}
}
