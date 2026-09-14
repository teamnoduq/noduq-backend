package com.noduq.application.payments;

import com.noduq.application.identity.OwnerAccountService;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.payments.PaymentNotice;
import com.noduq.domain.payments.port.PaymentNoticeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PaymentFeedService {

	private static final int DEFAULT_LIMIT = 30;
	private static final int MAX_LIMIT = 100;

	private final OwnerAccountService owners;
	private final PaymentNoticeRepository notices;

	public PaymentFeedService(OwnerAccountService owners, PaymentNoticeRepository notices) {
		this.owners = owners;
		this.notices = notices;
	}

	@Transactional(readOnly = true)
	public List<PaymentNotice> forOwner(UUID profileId, Integer limit) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		return notices.latest(workspace.organization().id(), clamp(limit));
	}

	@Transactional(readOnly = true)
	public List<PaymentNotice> forOrganization(UUID organizationId, Integer limit) {
		return notices.latest(organizationId, clamp(limit));
	}

	private static int clamp(Integer limit) {
		if (limit == null || limit <= 0) {
			return DEFAULT_LIMIT;
		}
		return Math.min(limit, MAX_LIMIT);
	}
}
