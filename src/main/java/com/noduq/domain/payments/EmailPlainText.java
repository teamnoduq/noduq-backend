package com.noduq.domain.payments;

/**
 * Gmail hands us MIME. Bancolombia's receipt lives in the plain-text part; when Google only
 * kept the HTML we strip tags so the same parser that reads the SMS can read the mail.
 */
public final class EmailPlainText {

	private EmailPlainText() {
	}

	public static String fromHtml(String html) {
		if (html == null || html.isBlank()) {
			return "";
		}
		String text = html
				.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
				.replaceAll("(?is)<style[^>]*>.*?</style>", " ")
				.replaceAll("(?is)<br\\s*/?>", "\n")
				.replaceAll("(?is)</p>", "\n")
				.replaceAll("(?is)</div>", "\n")
				.replaceAll("(?is)<[^>]+>", " ");
		return SmsPaymentParser.flatten(decodeEntities(text));
	}

	private static String decodeEntities(String text) {
		return text
				.replace("&nbsp;", " ")
				.replace("&amp;", "&")
				.replace("&lt;", "<")
				.replace("&gt;", ">")
				.replace("&#39;", "'")
				.replace("&quot;", "\"");
	}
}
