package com.noduq.domain.payments;

import java.math.BigDecimal;

/** How many notices match a filter, and the sum of their amounts. */
public record PaymentTally(long count, BigDecimal totalAmount) {
}
