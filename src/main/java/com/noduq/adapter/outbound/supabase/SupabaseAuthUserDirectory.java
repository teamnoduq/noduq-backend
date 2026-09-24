package com.noduq.adapter.outbound.supabase;

import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.port.AuthUserDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;

@Component
public class SupabaseAuthUserDirectory implements AuthUserDirectory {

	private static final Logger log = LoggerFactory.getLogger(SupabaseAuthUserDirectory.class);

	private final RestClient http;
	private final String serviceRoleKey;

	public SupabaseAuthUserDirectory(
			@Value("${noduq.supabase.url}") String supabaseUrl,
			@Value("${noduq.supabase.service-role-key}") String serviceRoleKey) {
		this.serviceRoleKey = serviceRoleKey == null ? "" : serviceRoleKey;
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(20));
		this.http = RestClient.builder().baseUrl(supabaseUrl).requestFactory(factory).build();
	}

	@Override
	public void deleteAuthUser(UUID authUserId) {
		if (serviceRoleKey.isBlank()) {
			throw IdentityException.validation(
					"AUTH_DELETE_UNAVAILABLE",
					"Falta SUPABASE_SERVICE_ROLE_KEY para borrar la cuenta de Auth.");
		}
		try {
			http.delete()
					.uri("/auth/v1/admin/users/{id}", authUserId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceRoleKey)
					.header("apikey", serviceRoleKey)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientResponseException ex) {
			if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
				return;
			}
			log.warn("Auth admin delete failed id={} status={}", authUserId, ex.getStatusCode().value(), ex);
		} catch (Exception ex) {
			log.warn("Auth admin delete failed id={}", authUserId, ex);
		}
	}
}
