package com.schwab.assessment.schwab_url_shortener.click;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Coarse device class from the {@code User-Agent}, for the {@code redirects} metric only. Five values keep the
 * metric small. The stream processor does the full parsing (OS, browser, in-app browser) for per-link stats.
 */
public enum DeviceType {

	MOBILE, TABLET, DESKTOP, BOT, OTHER;

	// Link-preview fetchers (Slack, LinkedIn, Facebook, X) request every shared link: without this they'd count as
	// clicks.
	private static final Pattern BOT_AGENTS = Pattern.compile(
			"bot|crawl|spider|slurp|preview|facebookexternalhit|curl|wget|python-requests|httpclient|okhttp|headless");

	private static final Pattern TABLET_AGENTS = Pattern.compile("ipad|tablet|kindle|silk|playbook|android(?!.*mobile)");

	private static final Pattern MOBILE_AGENTS = Pattern.compile("mobi|iphone|ipod|android|windows phone|blackberry");

	private static final Pattern DESKTOP_AGENTS = Pattern.compile("windows nt|macintosh|x11|cros|linux");

	public static DeviceType fromUserAgent(String userAgent) {
		if (userAgent == null || userAgent.isBlank()) {
			return OTHER;
		}
		var ua = userAgent.toLowerCase(Locale.ROOT);
		if (BOT_AGENTS.matcher(ua).find()) {
			return BOT;
		}
		if (TABLET_AGENTS.matcher(ua).find()) {
			return TABLET;
		}
		if (MOBILE_AGENTS.matcher(ua).find()) {
			return MOBILE;
		}
		if (DESKTOP_AGENTS.matcher(ua).find()) {
			return DESKTOP;
		}
		return OTHER;
	}

}
