package com.noduq.domain.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommerceDetailsTest {

	@Test
	void readsLast4FromStarPrefix() {
		assertEquals("8186", CommerceDetails.merchantLast4("*8186"));
		assertEquals("8186", CommerceDetails.merchantLast4(" 8186 "));
	}

	@Test
	void rejectsWrongLast4Length() {
		IdentityException error = assertThrows(IdentityException.class, () -> CommerceDetails.merchantLast4("818"));
		assertEquals("MERCHANT_LAST4_INVALID", error.code());
	}

	@Test
	void normalizesColombianMobile() {
		assertEquals("+573001112233", CommerceDetails.smsPhone("300 111 2233"));
		assertEquals("+573001112233", CommerceDetails.smsPhone("+57 3001112233"));
		assertNull(CommerceDetails.smsPhone(" "));
	}

	@Test
	void rejectsLandline() {
		IdentityException error = assertThrows(IdentityException.class, () -> CommerceDetails.smsPhone("6011234567"));
		assertEquals("SMS_PHONE_INVALID", error.code());
	}
}
