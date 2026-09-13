package com.noduq.adapter.inbound.security;

import com.noduq.application.identity.EmployeeSessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	EmployeeAuthenticationFilter employeeAuthenticationFilter(EmployeeSessionService sessions) {
		return new EmployeeAuthenticationFilter(sessions);
	}

	@Bean
	JwtDecoder jwtDecoder(
			@Value("${noduq.supabase.url}") String supabaseUrl,
			@Value("${noduq.supabase.jwt-secret:}") String jwtSecret,
			@Value("${noduq.supabase.anon-key:}") String anonKey,
			@Value("${noduq.supabase.service-role-key:}") String serviceRoleKey) {
		return new SupabaseJwtDecoder(supabaseUrl, jwtSecret, anonKey, serviceRoleKey);
	}

	@Bean
	@Order(1)
	SecurityFilterChain employeeApi(HttpSecurity http, EmployeeAuthenticationFilter employeeFilter) throws Exception {
		http.securityMatcher("/v1/employee/**")
				.csrf(AbstractHttpConfigurer::disable)
				.cors(Customizer.withDefaults())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.POST, "/v1/employee/sessions").permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(employeeFilter, UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain ownerApi(HttpSecurity http) throws Exception {
		http.securityMatcher("/v1/**")
				.csrf(AbstractHttpConfigurer::disable)
				.cors(Customizer.withDefaults())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.oauth2ResourceServer(oauth -> oauth
						.jwt(Customizer.withDefaults())
						.authenticationEntryPoint(invalidSession()));
		return http.build();
	}

	@Bean
	@Order(3)
	SecurityFilterChain publicApi(HttpSecurity http) throws Exception {
		http.securityMatcher("/health", "/error")
				.csrf(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(@Value("${noduq.cors.origins}") String corsOrigins) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(Arrays.stream(corsOrigins.split(","))
				.map(String::trim)
				.filter(origin -> !origin.isEmpty())
				.toList());
		cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of("*"));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);
		return source;
	}

	private static AuthenticationEntryPoint invalidSession() {
		return (request, response, authException) -> {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Sesión inválida.\"}");
		};
	}
}
