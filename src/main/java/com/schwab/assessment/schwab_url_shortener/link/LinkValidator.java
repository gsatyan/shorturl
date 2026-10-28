package com.schwab.assessment.schwab_url_shortener.link;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

import com.schwab.assessment.schwab_url_shortener.domain.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Request checks for creating a link (docs/DESIGN.md §6.1, §9). Malformed input is {@code 400}; a well-formed URL
 * pointing at a blocked destination is {@code 422}.
 */
@Component
class LinkValidator {

	static final int MAX_URL_LENGTH = 2048;

	private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9_-]{3,32}");

	private final LinksProperties properties;

	private final String ownHost;

	LinkValidator(LinksProperties properties) {
		this.properties = properties;
		this.ownHost = properties.baseUrl().getHost().toLowerCase(Locale.ROOT);
	}

	void validate(CreateLinkRequest request, Instant now) {
		var url = parseUrl(request.longUrl());
		var host = url.getHost().toLowerCase(Locale.ROOT);
		if (host.equals(ownHost)) {
			throw badRequest("longUrl can't point to the shortener itself");
		}
		if (isBlocked(host)) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "The destination is blocked");
		}
		if (request.customAlias() != null) {
			validateAlias(request.customAlias());
		}
		if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
			throw badRequest("expiresAt must be in the future");
		}
	}

	private static URI parseUrl(String longUrl) {
		if (longUrl == null || longUrl.isBlank()) {
			throw badRequest("longUrl is required");
		}
		if (longUrl.length() > MAX_URL_LENGTH) {
			throw badRequest("longUrl is longer than " + MAX_URL_LENGTH + " characters");
		}
		URI url;
		try {
			url = new URI(longUrl);
		}
		catch (URISyntaxException invalid) {
			throw badRequest("longUrl isn't a valid URL");
		}
		var scheme = url.getScheme();
		// Only http(s): javascript:, data: and file: links would run code or read files in the visitor's browser.
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw badRequest("longUrl must start with http:// or https://");
		}
		if (url.getHost() == null) {
			throw badRequest("longUrl must have a host");
		}
		// https://www.schwab.com@evil.example/ shows a trusted name but goes to evil.example.
		if (url.getRawUserInfo() != null) {
			throw badRequest("longUrl can't contain a user name or password");
		}
		return url;
	}

	private boolean isBlocked(String host) {
		return properties.blockedHosts().stream()
			.map(blocked -> blocked.toLowerCase(Locale.ROOT))
			.anyMatch(blocked -> host.equals(blocked) || host.endsWith("." + blocked));
	}

	private void validateAlias(String alias) {
		if (!ALIAS.matcher(alias).matches()) {
			throw badRequest("customAlias must be 3 to 32 characters: letters, digits, '-' or '_'");
		}
		if (properties.reservedAliases().stream().anyMatch(alias::equalsIgnoreCase)) {
			throw badRequest("customAlias '" + alias + "' is reserved");
		}
	}

	private static ApiException badRequest(String detail) {
		return new ApiException(HttpStatus.BAD_REQUEST, detail);
	}

}
