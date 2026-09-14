package com.noduq.adapter.inbound.security;

import com.noduq.application.identity.EmployeeSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
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

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

	@Bean
	JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setPrincipalClaimName("sub");
		converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
		return converter;
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
	@Order(0)
	SecurityFilterChain gmailCallback(HttpSecurity http) throws Exception {
		http.securityMatcher("/v1/gmail/callback")
				.csrf(AbstractHttpConfigurer::disable)
				.cors(Customizer.withDefaults())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}

	/**
	 * The filter is built here instead of exposed as a bean: Spring Boot auto-registers
	 * every Filter bean for all URLs, and this one would then reject owner requests.
	 */
	@Bean
	@Order(1)
	SecurityFilterChain employeeApi(HttpSecurity http, EmployeeSessionService sessions) throws Exception {
		EmployeeAuthenticationFilter employeeFilter = new EmployeeAuthenticationFilter(sessions);
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
	SecurityFilterChain ownerApi(
			HttpSecurity http,
			JwtDecoder jwtDecoder,
			JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
		http.securityMatcher("/v1/**")
				.csrf(AbstractHttpConfigurer::disable)
				.cors(Customizer.withDefaults())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(invalidSession())
						.accessDeniedHandler(accessDenied()))
				.oauth2ResourceServer(oauth -> oauth
						.jwt(jwt -> jwt
								.decoder(jwtDecoder)
								.jwtAuthenticationConverter(jwtAuthenticationConverter))
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
			log.info("Auth failed {} {}: {}", request.getMethod(), request.getRequestURI(), authException.toString());
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Sesión inválida.\"}");
		};
	}

	private static AccessDeniedHandler accessDenied() {
		return (request, response, denied) -> {
			log.warn("Forbidden {} {}: {}", request.getMethod(), request.getRequestURI(), denied.getMessage());
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"No tienes permiso.\"}");
		};
	}
}
