package com.noduq.domain.payments;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Payments grouped onto one calendar day, or the first day of a month. */
public record PaymentBucket(LocalDate start, long count, BigDecimal amount) {
}
