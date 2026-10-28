package com.schwab.assessment.schwab_url_shortener.link;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.schwab.assessment.schwab_url_shortener.domain.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Pagination cursors for listing links (AP9, docs/DESIGN.md §7.2). A cursor is the last returned item's
 * {@code gsi1sk}, which holds both its position and its code, plus an HMAC binding it to the caller.
 * <p>
 * The owner part of the query always comes from the caller's API key, never from the cursor, so a cursor can't reach
 * another owner's links. The signature additionally rejects cursors that were edited, forged, or issued to someone
 * else, with a clear {@code 400} instead of a confusing page.
 */
@Component
class CursorCodec {

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	private final SecretKeySpec key;

	CursorCodec(@Value("${app.links.cursor-secret}") String secret) {
		this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	String encode(String ownerId, String gsi1sk) {
		return ENCODER.encodeToString(gsi1sk.getBytes(StandardCharsets.UTF_8)) + "." + sign(ownerId, gsi1sk);
	}

	/** @throws ApiException {@code 400} if the cursor is malformed, altered, or belongs to another owner */
	String decode(String ownerId, String cursor) {
		var separator = cursor.indexOf('.');
		if (separator < 0) {
			throw invalid();
		}
		String gsi1sk;
		try {
			gsi1sk = new String(DECODER.decode(cursor.substring(0, separator)), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException notBase64) {
			throw invalid();
		}
		var expected = sign(ownerId, gsi1sk).getBytes(StandardCharsets.US_ASCII);
		var actual = cursor.substring(separator + 1).getBytes(StandardCharsets.US_ASCII);
		if (!MessageDigest.isEqual(expected, actual) || !gsi1sk.startsWith("LINK#")) {   // constant-time compare
			throw invalid();
		}
		return gsi1sk;
	}

	private String sign(String ownerId, String gsi1sk) {
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			return ENCODER.encodeToString(mac.doFinal((ownerId + "\n" + gsi1sk).getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException impossible) {
			throw new IllegalStateException("HmacSHA256 is required on every Java platform", impossible);
		}
	}

	private static ApiException invalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, "cursor is invalid; use the nextCursor from a previous page");
	}

}
