package com.noduq.adapter.outbound.security;

import com.noduq.domain.identity.EmployeeCode;
import com.noduq.domain.identity.port.EmployeeCodeHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Component
public class BcryptEmployeeCodeHasher implements EmployeeCodeHasher {

	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
	private final byte[] pepper;

	public BcryptEmployeeCodeHasher(@Value("${noduq.employee.jwt-secret}") String pepper) {
		this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public String lookup(EmployeeCode code) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(code.normalized().getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException("No se pudo firmar el código", ex);
		}
	}

	@Override
	public String hash(EmployeeCode code) {
		return encoder.encode(code.normalized());
	}

	@Override
	public boolean matches(EmployeeCode code, String codeHash) {
		return encoder.matches(code.normalized(), codeHash);
	}
}
