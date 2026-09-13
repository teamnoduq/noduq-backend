package com.noduq.adapter.inbound.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupabaseJwtDecoderTest {

	private static final String SECRET = "n".repeat(32);
	private static final String ISSUER = "https://example.supabase.co/auth/v1";

	@Test
	void decodesLegacyHs256AccessTokens() throws Exception {
		String token = hs256(SECRET, Instant.now().plusSeconds(60));
		Jwt jwt = new SupabaseJwtDecoder("https://example.supabase.co", SECRET, "").decode(token);
		assertEquals("owner-1", jwt.getSubject());
		assertEquals(ISSUER, jwt.getIssuer().toString());
	}

	@Test
	void asksGoTrueWhenHs256SecretIsMissing() throws Exception {
		String token = hs256(SECRET, Instant.now().plusSeconds(60));
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
	void rejectsHs256WhenSecretAndGoTrueAreMissing() throws Exception {
		String token = hs256(SECRET, Instant.now().plusSeconds(60));
		assertThrows(JwtException.class,
				() -> new SupabaseJwtDecoder("https://example.supabase.co", "", "").decode(token));
	}

	private static RestClient restClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(2000);
		factory.setReadTimeout(2000);
		return RestClient.builder().requestFactory(factory).build();
	}

	private static String hs256(String secret, Instant exp) throws Exception {
		SignedJWT jwt = new SignedJWT(
				new JWSHeader(JWSAlgorithm.HS256),
				new JWTClaimsSet.Builder()
						.issuer(ISSUER)
						.subject("owner-1")
						.jwtID(UUID.randomUUID().toString())
						.expirationTime(Date.from(exp))
						.build());
		jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
		return jwt.serialize();
	}
}
