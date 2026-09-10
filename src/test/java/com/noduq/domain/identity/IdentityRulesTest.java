package com.noduq.domain.identity;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsernameTest {

	@Test
	void normalizesAndAcceptsManualUsername() {
		assertEquals("juan_1", Username.parse("Juan_1").value());
	}

	@Test
	void rejectsShortUsername() {
		assertThrows(IdentityException.class, () -> Username.parse("ab"));
	}

	@Test
	void generatesFromDisplayNameAndAvoidsTaken() {
		Username generated = Username.fromDisplayName("Juan Perez", Set.of("juan_perez"));
		assertEquals("juan_perez2", generated.value());
	}
}

class EmployeeCodeTest {

	@Test
	void generatesParseableDisplayCode() {
		EmployeeCode code = EmployeeCode.generate(new SecureRandom());
		assertEquals(11, code.display().length());
		assertEquals(code.normalized(), EmployeeCode.parse(code.display()).normalized());
	}

	@Test
	void rejectsGarbage() {
		assertNull(EmployeeCode.parse("12"));
		assertNotNull(EmployeeCode.parse("23456-789AB"));
	}
}
