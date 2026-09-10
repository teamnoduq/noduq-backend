package com.noduq.adapter.inbound.security;

import com.noduq.application.identity.EmployeeSessionService;
import com.noduq.domain.identity.IdentityException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class EmployeeAuthenticationFilter extends OncePerRequestFilter {

	private final EmployeeSessionService sessions;

	public EmployeeAuthenticationFilter(EmployeeSessionService sessions) {
		this.sessions = sessions;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return HttpMethod.POST.matches(request.getMethod())
				&& request.getRequestURI().endsWith("/v1/employee/sessions");
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith("Bearer ")) {
			filterChain.doFilter(request, response);
			return;
		}
		try {
			var authenticated = sessions.authenticate(header.substring("Bearer ".length()));
			var caller = new EmployeeCaller(
					authenticated.session().id(),
					authenticated.employee().id(),
					authenticated.employee().organizationId());
			var authentication = UsernamePasswordAuthenticationToken.authenticated(
					caller,
					null,
					List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
			SecurityContextHolder.getContext().setAuthentication(authentication);
			filterChain.doFilter(request, response);
		} catch (IdentityException ex) {
			SecurityContextHolder.clearContext();
			response.setStatus(ex.httpStatus());
			response.setContentType("application/json");
			response.getWriter().write("{\"code\":\"" + ex.code() + "\",\"message\":\"" + ex.getMessage() + "\"}");
		}
	}
}
