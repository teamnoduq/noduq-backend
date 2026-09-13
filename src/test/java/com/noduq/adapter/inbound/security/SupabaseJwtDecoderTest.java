package com.noduq.adapter.inbound.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SupabaseJwtDecoderTest {

	private static final String SECRET = "n".repeat(32);
	private static final String ISSUER = "https://example.supabase.co/auth/v1";

	@Test
	void decodesLegacyHs256AccessTokens() throws Exception {
		String token = hs256(SECRET, Instant.now().plusSeconds(60));
		Jwt jwt = new SupabaseJwtDecoder("https://example.supabase.co", SECRET).decode(token);
		assertEquals("owner-1", jwt.getSubject());
		assertEquals(ISSUER, jwt.getIssuer().toString());
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
