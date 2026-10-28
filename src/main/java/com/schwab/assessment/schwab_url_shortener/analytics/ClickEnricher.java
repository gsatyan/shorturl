package com.schwab.assessment.schwab_url_shortener.analytics;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZoneOffset;
import java.util.Locale;

import com.schwab.assessment.schwab_url_shortener.click.ClickEvent;
import com.schwab.assessment.schwab_url_shortener.click.DeviceType;

/**
 * Derives the stored fields of a click (docs/DESIGN.md §6.4): device type and bot flag from the User-Agent, the
 * referrer's host, and the anonymous visitor ID. Country is left empty for now.
 */
final class ClickEnricher {

	private final VisitorHasher visitorHasher;

	private final String region;

	ClickEnricher(VisitorHasher visitorHasher, String region) {
		this.visitorHasher = visitorHasher;
		this.region = region;
	}

	EnrichedClick enrich(ClickEvent click) {
		var device = DeviceType.fromUserAgent(click.userAgent());
		var day = click.timestamp().atZone(ZoneOffset.UTC).toLocalDate();
		return new EnrichedClick(click.eventId(), click.code(), click.timestamp(), referrerHost(click.referer()),
				device.name(), "", device == DeviceType.BOT, visitorHasher.hash(day, click.ip(), click.userAgent()),
				region);
	}

	/** Only the host is kept: full referrer URLs can carry personal data in paths and query strings. */
	static String referrerHost(String referer) {
		if (referer == null || referer.isBlank()) {
			return "";
		}
		try {
			var host = new URI(referer.strip()).getHost();
			if (host == null) {
				return "";
			}
			host = host.toLowerCase(Locale.ROOT);
			return host.startsWith("www.") ? host.substring(4) : host;
		}
		catch (URISyntaxException malformed) {
			return "";
		}
	}

}
