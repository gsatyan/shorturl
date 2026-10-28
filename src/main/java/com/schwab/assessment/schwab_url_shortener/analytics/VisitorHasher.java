package com.schwab.assessment.schwab_url_shortener.analytics;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns a click's IP and User-Agent into an anonymous visitor ID (docs/DESIGN.md §6.4):
 * {@code HMAC(salt(day), IP + User-Agent)}, truncated to 16 bytes, where {@code salt(day) = HMAC(secret, day)}.
 * <p>
 * Every instance computes the same salt with nothing stored, so unique-visitor counts agree across instances. The
 * salt changes at midnight UTC, so the same person gets a different ID each day and can't be followed across days,
 * and without the secret no ID can be linked back to an IP address.
 */
final class VisitorHasher {

	static final int LENGTH = 16;

	private final byte[] secret;

	private LocalDate cachedDay;

	private byte[] cachedSalt;

	VisitorHasher(String secret) {
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
	}

	byte[] hash(LocalDate day, String ip, String userAgent) {
		var input = (nullToEmpty(ip) + "\n" + nullToEmpty(userAgent)).getBytes(StandardCharsets.UTF_8);
		return Arrays.copyOf(hmac(salt(day), input), LENGTH);
	}

	private synchronized byte[] salt(LocalDate day) {
		if (!day.equals(cachedDay)) {
			cachedSalt = hmac(secret, day.toString().getBytes(StandardCharsets.UTF_8));
			cachedDay = day;
		}
		return cachedSalt;
	}

	private static byte[] hmac(byte[] key, byte[] input) {
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(input);
		}
		catch (GeneralSecurityException impossible) {
			throw new IllegalStateException("HmacSHA256 is required on every Java platform", impossible);
		}
	}

	private static String nullToEmpty(String value) {
		return (value == null) ? "" : value;
	}

}
