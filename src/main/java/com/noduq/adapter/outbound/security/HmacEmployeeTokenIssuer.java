package com.noduq.adapter.outbound.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.port.EmployeeTokenIssuer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class HmacEmployeeTokenIssuer implements EmployeeTokenIssuer {

	public static final String ISSUER = "noduq-employee";

	private final byte[] secret;

	public HmacEmployeeTokenIssuer(@Value("${noduq.employee.jwt-secret}") String secret) {
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			throw new IllegalStateException("EMPLOYEE_JWT_SECRET debe tener al menos 32 caracteres.");
		}
		this.secret = bytes;
	}

	@Override
	public String issue(UUID sessionId, UUID employeeId, UUID organizationId, Instant expiresAt) {
		try {
			SignedJWT jwt = new SignedJWT(
					new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
					new JWTClaimsSet.Builder()
							.issuer(ISSUER)
							.jwtID(sessionId.toString())
							.subject(employeeId.toString())
							.claim("org", organizationId.toString())
							.claim("role", "employee")
							.issueTime(new Date())
							.expirationTime(Date.from(expiresAt))
							.build());
			jwt.sign(new MACSigner(secret));
			return jwt.serialize();
		} catch (Exception ex) {
			throw new IllegalStateException("No se pudo emitir la sesión del empleado", ex);
		}
	}

	@Override
	public EmployeeTokenPayload parse(String token) {
		try {
			SignedJWT jwt = SignedJWT.parse(token);
			if (!jwt.verify(new MACVerifier(secret))) {
				throw IdentityException.unauthorized("Sesión inválida.");
			}
			JWTClaimsSet claims = jwt.getJWTClaimsSet();
			if (!ISSUER.equals(claims.getIssuer())) {
				throw IdentityException.unauthorized("Sesión inválida.");
			}
			Date expiration = claims.getExpirationTime();
			if (expiration == null || expiration.toInstant().isBefore(Instant.now())) {
				throw IdentityException.unauthorized("Sesión inválida.");
			}
			return new EmployeeTokenPayload(
					UUID.fromString(claims.getJWTID()),
					UUID.fromString(claims.getSubject()),
					UUID.fromString(claims.getStringClaim("org")),
					expiration.toInstant());
		} catch (IdentityException ex) {
			throw ex;
		} catch (Exception ex) {
			throw IdentityException.unauthorized("Sesión inválida.");
		}
	}
}
