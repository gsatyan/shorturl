package com.schwab.assessment.schwab_url_shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.schwab.assessment.schwab_url_shortener.domain.ApiException;
import com.schwab.assessment.schwab_url_shortener.domain.LinkStatus;
import org.junit.jupiter.api.Test;

class LinkChangeTests {

	@Test
	void readsStatusAndExpiry() {
		var change = LinkChange.fromBody(Map.of("status", "DISABLED", "expiresAt", "2030-01-01T00:00:00Z"));

		assertThat(change).isEqualTo(new LinkChange(LinkStatus.DISABLED, Instant.parse("2030-01-01T00:00:00Z")));
	}

	@Test
	void fieldsLeftOutAreUnchanged() {
		assertThat(LinkChange.fromBody(Map.of("status", "ACTIVE"))).isEqualTo(new LinkChange(LinkStatus.ACTIVE, null));
		assertThat(LinkChange.fromBody(Map.of("expiresAt", "2030-01-01T00:00:00Z")).status()).isNull();
	}

	@Test
	void expiryCantBeRemoved() {
		var body = new HashMap<String, Object>();
		body.put("expiresAt", null);

		assertBadRequest(body, "expiresAt can't be removed");
	}

	@Test
	void ownersCantSetBlockedOrDeleted() {
		assertBadRequest(Map.of("status", "BLOCKED"), "status must be ACTIVE or DISABLED");
		assertBadRequest(Map.of("status", "DELETED"), "status must be ACTIVE or DISABLED");
		assertBadRequest(Map.of("status", "disabled"), "status must be ACTIVE or DISABLED");
	}

	@Test
	void destinationCantChange() {
		assertBadRequest(Map.of("longUrl", "https://evil.example"), "longUrl can't be changed");
	}

	@Test
	void emptyBodyUnknownFieldsAndBadTimesAreRejected() {
		assertBadRequest(Map.of(), "at least one of");
		assertBadRequest(Map.of("customAlias", "x"), "Unknown field 'customAlias'");
		assertBadRequest(Map.of("expiresAt", "next tuesday"), "ISO-8601");
	}

	private static void assertBadRequest(Map<String, Object> body, String detail) {
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> LinkChange.fromBody(body))
			.satisfies(failure -> {
				assertThat(failure.getStatusCode().value()).isEqualTo(400);
				assertThat(failure.getBody().getDetail()).contains(detail);
			});
	}

}
