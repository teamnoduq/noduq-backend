package com.noduq.domain.payments.port;

import com.noduq.domain.payments.PaymentHistoryImport;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentHistoryRepository {

	Optional<PaymentHistoryImport> find(UUID organizationId);

	void save(PaymentHistoryImport row);

	void rememberMessages(UUID organizationId, List<String> gmailIds);

	int countMessages(UUID organizationId);

	List<String> nextMessages(UUID organizationId, int limit);

	void forgetMessages(UUID organizationId, List<String> gmailIds);

	void clearMessages(UUID organizationId);
}
