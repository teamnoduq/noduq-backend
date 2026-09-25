package com.noduq.domain.payments.port;

import com.noduq.domain.payments.PaymentHistoryImport;

import java.util.Optional;
import java.util.UUID;

public interface PaymentHistoryRepository {

	Optional<PaymentHistoryImport> find(UUID organizationId);

	void save(PaymentHistoryImport row);
}
