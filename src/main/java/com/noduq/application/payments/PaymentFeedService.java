package com.noduq.application.payments;

import com.noduq.application.identity.OwnerAccountService;
import com.noduq.domain.identity.Employee;
import com.noduq.domain.identity.LookbackDays;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.identity.port.EmployeeRepository;
import com.noduq.domain.payments.PaymentNotice;
import com.noduq.domain.payments.port.PaymentNoticeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentFeedService {

	private static final int DEFAULT_LIMIT = 30;
	private static final int MAX_LIMIT = 100;

	private final OwnerAccountService owners;
	private final EmployeeRepository employees;
	private final PaymentNoticeRepository notices;

	public PaymentFeedService(
			OwnerAccountService owners, EmployeeRepository employees, PaymentNoticeRepository notices) {
		this.owners = owners;
		this.employees = employees;
		this.notices = notices;
	}

	@Transactional(readOnly = true)
	public List<PaymentNotice> forOwner(
			UUID profileId, Integer limit, Instant since, Instant until, String query, String source) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		return notices.search(workspace.organization().id(), clamp(limit), since, until, query, source);
	}

	@Transactional(readOnly = true)
	public List<PaymentNotice> forEmployee(UUID organizationId, UUID employeeId, Integer limit) {
		Employee employee = employees.findById(employeeId).orElseThrow();
		Instant since = LookbackDays.of(employee.lookbackDays()).floor(Instant.now());
		return notices.search(organizationId, clamp(limit), since, null, null, null);
	}

	private static int clamp(Integer limit) {
		if (limit == null || limit <= 0) {
			return DEFAULT_LIMIT;
		}
		return Math.min(limit, MAX_LIMIT);
	}
}
