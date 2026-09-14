package com.noduq.domain.payments;

public enum PaymentSource {
	SMS,
	EMAIL;

	public String dbValue() {
		return name().toLowerCase();
	}

	public static PaymentSource fromDb(String value) {
		return PaymentSource.valueOf(value.toUpperCase());
	}
}
