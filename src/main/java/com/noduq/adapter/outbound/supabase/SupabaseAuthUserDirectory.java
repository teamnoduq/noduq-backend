package com.noduq.adapter.outbound.supabase;

import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.port.AuthUserDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

@Component
public class SupabaseAuthUserDirectory implements AuthUserDirectory {

	private final RestClient http;
	private final String serviceRoleKey;

	public SupabaseAuthUserDirectory(
			@Value("${noduq.supabase.url}") String supabaseUrl,
			@Value("${noduq.supabase.service-role-key}") String serviceRoleKey) {
		this.serviceRoleKey = serviceRoleKey == null ? "" : serviceRoleKey;
		this.http = RestClient.builder().baseUrl(supabaseUrl).build();
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
			throw IdentityException.validation(
					"AUTH_DELETE_FAILED",
					"El local se iba a borrar, pero Auth no eliminó la sesión. Inténtalo de nuevo.");
		}
	}
}
