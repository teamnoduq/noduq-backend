package com.noduq.adapter.outbound.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noduq.domain.payments.BankSenders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Loads the sender catalogue that Bancolombia keeps changing. Editing the JSON is a
 * one-line change with no Java in it, which is the point.
 */
@Configuration
public class BankSendersConfiguration {

	private static final Logger log = LoggerFactory.getLogger(BankSendersConfiguration.class);

	private static final String LOCATION = "payments/bancolombia-senders.json";

	@Bean
	BankSenders bankSenders(ObjectMapper mapper) throws IOException {
		try (InputStream stream = new ClassPathResource(LOCATION).getInputStream()) {
			JsonNode root = mapper.readTree(stream);
			JsonNode sms = root.path("sms");
			JsonNode email = root.path("email");
			JsonNode filter = root.path("filter");

			Set<String> ignoredEmail = new LinkedHashSet<>();
			ignoredEmail.addAll(texts(email.path("securityOnlyDoNotTreatAsQrPayment")));
			ignoredEmail.addAll(texts(email.path("doNotUseToValidatePaymentUnlessSeenInTheWild")));

			BankSenders senders = new BankSenders(
					texts(sms.path("qrPaymentSenders")),
					texts(sms.path("knownBancolombiaSenders")),
					addresses(email.path("qrPaymentFrom")),
					ignoredEmail,
					filter.path("requireQrHintInBody").asBoolean(true));

			log.info(
					"Bank senders loaded: sms={} email={}",
					senders.qrPaymentSmsSenders(),
					senders.qrPaymentEmailFrom().size());
			return senders;
		}
	}

	private static Set<String> texts(JsonNode array) {
		Set<String> values = new LinkedHashSet<>();
		array.forEach(node -> {
			String value = node.asText("").trim().toLowerCase(Locale.ROOT);
			if (!value.isEmpty()) {
				values.add(value);
			}
		});
		return values;
	}

	private static Set<String> addresses(JsonNode array) {
		Set<String> values = new LinkedHashSet<>();
		array.forEach(node -> {
			String value = node.path("address").asText("").trim().toLowerCase(Locale.ROOT);
			if (!value.isEmpty()) {
				values.add(value);
			}
		});
		return values;
	}
}
