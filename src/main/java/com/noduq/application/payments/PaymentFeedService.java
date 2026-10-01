package com.noduq.application.payments;

import com.noduq.application.identity.OwnerAccountService;
import com.noduq.domain.identity.Employee;
import com.noduq.domain.identity.LookbackDays;
import com.noduq.domain.identity.OwnerWorkspace;
import com.noduq.domain.identity.port.EmployeeRepository;
import com.noduq.domain.payments.PaymentNotice;
import com.noduq.domain.payments.PaymentTally;
import com.noduq.domain.payments.port.PaymentNoticeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
	public Page forOwner(
			UUID profileId,
			Integer limit,
			Instant since,
			Instant until,
			String query,
			String source,
			Instant before,
			UUID beforeId,
			Integer offset) {
		OwnerWorkspace workspace = owners.requireWorkspace(profileId);
		return page(workspace.organization().id(), clamp(limit), since, until, query, source, before, beforeId, clampOffset(offset));
	}

	@Transactional(readOnly = true)
	public Page forEmployee(
			UUID organizationId,
			UUID employeeId,
			Integer limit,
			Instant since,
			Instant until,
			String query,
			Instant before,
			UUID beforeId,
			Integer offset) {
		Employee employee = employees.findById(employeeId).orElseThrow();
		Instant floor = LookbackDays.of(employee.lookbackDays()).floor(Instant.now());
		Instant boundedSince = since == null || since.isBefore(floor) ? floor : since;
		Instant boundedUntil = until != null && !until.isAfter(floor) ? floor : until;
		return page(organizationId, clamp(limit), boundedSince, boundedUntil, query, null, before, beforeId, clampOffset(offset));
	}

	private Page page(
			UUID organizationId,
			int limit,
			Instant since,
			Instant until,
			String query,
			String source,
			Instant before,
			UUID beforeId,
			int offset) {
		PaymentTally tally = notices.tally(organizationId, since, until, query, source);
		List<PaymentNotice> rows = notices.search(
				organizationId, limit, since, until, query, source, before, beforeId, offset);
		BigDecimal total = tally.totalAmount() == null ? BigDecimal.ZERO : tally.totalAmount();
		return new Page(rows, tally.count(), total);
	}

	private static int clamp(Integer limit) {
		if (limit == null || limit <= 0) {
			return DEFAULT_LIMIT;
		}
		return Math.min(limit, MAX_LIMIT);
	}

	private static int clampOffset(Integer offset) {
		if (offset == null || offset < 0) {
			return 0;
		}
		return Math.min(offset, 20_000);
	}

	public record Page(List<PaymentNotice> notices, long count, BigDecimal totalAmount) {
	}
}
