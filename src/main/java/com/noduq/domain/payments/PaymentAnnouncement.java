package com.noduq.domain.payments;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Turns a notice into the push the counter sees. */
public final class PaymentAnnouncement {

	public static final String TITLE = "El pago ya llegó";

	private PaymentAnnouncement() {
	}

	public static PushMessage of(PaymentNotice notice) {
		Map<String, String> data = new LinkedHashMap<>();
		data.put("type", "payment_notice");
		data.put("noticeId", notice.id().toString());
		data.put("organizationId", notice.organizationId().toString());
		data.put("source", notice.source().dbValue());
		data.put("happenedAt", notice.happenedAt().toString());
		if (notice.payerName() != null) {
			data.put("payerName", notice.payerName());
		}
		if (notice.amount() != null) {
			data.put("amount", notice.amount().toPlainString());
			data.put("amountLabel", pesos(notice.amount()));
		}
		return new PushMessage(TITLE, body(notice), data);
	}

	private static String body(PaymentNotice notice) {
		if (notice.amount() != null && notice.payerName() != null) {
			return notice.payerName() + " · " + pesos(notice.amount());
		}
		if (notice.amount() != null) {
			return pesos(notice.amount());
		}
		if (notice.payerName() != null) {
			return notice.payerName();
		}
		return "Llegó un aviso del banco. Ábrelo para verlo.";
	}

	/** Pesos the way Colombia writes them: {@code $48.000}, and cents only when there are cents. */
	public static String pesos(BigDecimal amount) {
		BigDecimal value = amount.stripTrailingZeros();
		DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
		symbols.setGroupingSeparator('.');
		symbols.setDecimalSeparator(',');
		DecimalFormat format = new DecimalFormat(value.scale() > 0 ? "#,##0.00" : "#,##0", symbols);
		return "$" + format.format(value);
	}
}
