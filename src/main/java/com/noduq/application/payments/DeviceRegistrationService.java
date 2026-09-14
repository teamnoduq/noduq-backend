package com.noduq.application.payments;

import com.noduq.application.identity.OwnerAccountService;
import com.noduq.domain.identity.IdentityException;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.payments.Device;
import com.noduq.domain.payments.port.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class DeviceRegistrationService {

	private static final Set<String> PLATFORMS = Set.of("android", "ios", "web");
	private static final String DEFAULT_PLATFORM = "android";
	private static final int MAX_TOKEN_LENGTH = 512;

	private final OwnerAccountService owners;
	private final DeviceRepository devices;

	public DeviceRegistrationService(OwnerAccountService owners, DeviceRepository devices) {
		this.owners = owners;
		this.devices = devices;
	}

	@Transactional
	public Device registerForOwner(UUID profileId, String pushToken, String platform, boolean smsReader) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		return devices.register(new Device(
				UUID.randomUUID(),
				workspace.organization().id(),
				profileId,
				null,
				token(pushToken),
				platform(platform),
				smsReader,
				Instant.now()));
	}

	@Transactional
	public Device registerForEmployee(UUID organizationId, UUID employeeId, String pushToken, String platform) {
		return devices.register(new Device(
				UUID.randomUUID(),
				organizationId,
				null,
				employeeId,
				token(pushToken),
				platform(platform),
				false,
				Instant.now()));
	}

	@Transactional
	public void forgetForOwner(UUID profileId, String pushToken) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		devices.deleteByPushToken(workspace.organization().id(), token(pushToken));
	}

	@Transactional
	public void forgetForEmployee(UUID organizationId, String pushToken) {
		devices.deleteByPushToken(organizationId, token(pushToken));
	}

	private static String token(String pushToken) {
		if (pushToken == null || pushToken.isBlank()) {
			throw IdentityException.validation("PUSH_TOKEN_REQUIRED", "Falta el token del dispositivo.");
		}
		String trimmed = pushToken.trim();
		if (trimmed.length() > MAX_TOKEN_LENGTH) {
			throw IdentityException.validation("PUSH_TOKEN_INVALID", "Ese token no es válido.");
		}
		return trimmed;
	}

	private static String platform(String raw) {
		if (raw == null || raw.isBlank()) {
			return DEFAULT_PLATFORM;
		}
		String value = raw.trim().toLowerCase(Locale.ROOT);
		if (!PLATFORMS.contains(value)) {
			throw IdentityException.validation("PLATFORM_INVALID", "Esa plataforma no existe.");
		}
		return value;
	}
}
