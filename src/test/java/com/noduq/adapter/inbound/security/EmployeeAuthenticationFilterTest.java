package com.noduq.adapter.inbound.security;

import com.noduq.application.identity.EmployeeSessionService;
import com.noduq.domain.identity.IdentityException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeAuthenticationFilterTest {

	private static final String OWNER_JWT = "eyJhbGciOiJFUzI1NiJ9.owner.signature";

	@Mock
	private EmployeeSessionService sessions;

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void letsOwnerRequestsThroughEvenWhenTheyCarryABearerToken() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/me");
		request.addHeader("Authorization", "Bearer " + OWNER_JWT);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new EmployeeAuthenticationFilter(sessions).doFilter(request, response, chain);

		assertNotNull(chain.getRequest(), "owner request must reach the controller");
		assertEquals(200, response.getStatus());
		verifyNoInteractions(sessions);
	}

	@Test
	void skipsTheEmployeeLoginEndpoint() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/employee/sessions");
		request.addHeader("Authorization", "Bearer " + OWNER_JWT);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new EmployeeAuthenticationFilter(sessions).doFilter(request, response, chain);

		assertNotNull(chain.getRequest());
		verifyNoInteractions(sessions);
	}

	@Test
	void rejectsEmployeeRequestsWithAnUnusableToken() throws Exception {
		when(sessions.authenticate(anyString())).thenThrow(IdentityException.unauthorized("Sesión inválida."));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/employee/me");
		request.addHeader("Authorization", "Bearer stale-employee-token");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new EmployeeAuthenticationFilter(sessions).doFilter(request, response, chain);

		assertNull(chain.getRequest(), "rejected employee request must not reach the controller");
		assertEquals(401, response.getStatus());
		assertEquals("{\"code\":\"UNAUTHORIZED\",\"message\":\"Sesión inválida.\"}", response.getContentAsString());
	}
}
