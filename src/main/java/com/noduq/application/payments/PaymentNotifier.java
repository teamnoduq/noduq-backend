package com.noduq.application.payments;

import com.noduq.domain.payments.Device;
import com.noduq.domain.payments.PaymentAnnouncement;
import com.noduq.domain.payments.PaymentNotice;
import com.noduq.domain.payments.port.DeviceRepository;
import com.noduq.domain.payments.port.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** Wakes up every phone in the local when a payment lands. */
@Service
public class PaymentNotifier {

	private static final Logger log = LoggerFactory.getLogger(PaymentNotifier.class);

	private final DeviceRepository devices;
	private final PushSender push;

	public PaymentNotifier(DeviceRepository devices, PushSender push) {
		this.devices = devices;
		this.push = push;
	}

	public void announce(PaymentNotice notice) {
		if (!push.enabled()) {
			log.info("Push disabled, notice {} only stored", notice.id());
			return;
		}
		List<String> tokens = devices.listByOrganization(notice.organizationId()).stream()
				.map(Device::pushToken)
				.toList();
		if (tokens.isEmpty()) {
			log.info("No devices registered for org {}, notice {} only stored", notice.organizationId(), notice.id());
			return;
		}
		// The payment already happened. If Firebase is having a bad day the notice is still
		// stored and the app will pick it up on its next read, so nothing here may throw.
		try {
			Set<String> rejected = push.send(tokens, PaymentAnnouncement.of(notice));
			if (!rejected.isEmpty()) {
				log.info("Dropping {} dead push tokens for org {}", rejected.size(), notice.organizationId());
				devices.deleteByPushTokens(rejected);
			}
			log.info("Announced notice {} to {} devices", notice.id(), tokens.size() - rejected.size());
		} catch (RuntimeException ex) {
			log.warn("Push failed for notice {}: {}", notice.id(), ex.toString());
		}
	}
}
