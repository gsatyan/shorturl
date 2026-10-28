package com.schwab.assessment.schwab_url_shortener.link;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.schwab.assessment.schwab_url_shortener.domain.ApiException;
import com.schwab.assessment.schwab_url_shortener.domain.Hashing;
import com.schwab.assessment.schwab_url_shortener.domain.LinkStatus;
import com.schwab.assessment.schwab_url_shortener.domain.Timestamps;
import com.schwab.assessment.schwab_url_shortener.link.DynamoDbLinkStore.CodeTakenException;
import com.schwab.assessment.schwab_url_shortener.link.DynamoDbLinkStore.IdempotencyKeyTakenException;
import com.schwab.assessment.schwab_url_shortener.link.DynamoDbLinkStore.LinkChangedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Creates, reads, lists and changes links (docs/DESIGN.md §6.1, AP6–AP9). Caches need no update here: every committed write
 * reaches the redirect caches through the stream relay (§6.3). {@code CREATED} also clears any "not found" entry
 * cached for the code before it existed.
 */
@Service
@EnableConfigurationProperties(LinksProperties.class)
class LinkService {

	/** A concurrent change between our read and write is retried once on the fresh version, then reported. */
	private static final int MAX_UPDATE_ATTEMPTS = 2;

	/** A generated code only clashes with an existing 7-character custom alias; then the next ID is used. */
	private static final int MAX_GENERATED_ATTEMPTS = 5;

	private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

	static final int MAX_PAGE_SIZE = 100;

	private final LinkValidator validator;

	private final IdAllocator idAllocator;

	private final DynamoDbLinkStore store;

	private final LinksProperties properties;

	private final CursorCodec cursors;

	private final MeterRegistry meterRegistry;

	private final Clock clock;

	@Autowired
	LinkService(LinkValidator validator, IdAllocator idAllocator, DynamoDbLinkStore store, LinksProperties properties,
			CursorCodec cursors, MeterRegistry meterRegistry) {
		this(validator, idAllocator, store, properties, cursors, meterRegistry, Clock.systemUTC());
	}

	LinkService(LinkValidator validator, IdAllocator idAllocator, DynamoDbLinkStore store, LinksProperties properties,
			CursorCodec cursors, MeterRegistry meterRegistry, Clock clock) {
		this.validator = validator;
		this.idAllocator = idAllocator;
		this.store = store;
		this.properties = properties;
		this.cursors = cursors;
		this.meterRegistry = meterRegistry;
		this.clock = clock;
	}

	/**
	 * @param idempotencyKey optional {@code Idempotency-Key} header; a retry with the same key and body returns the
	 * original response instead of creating a second link
	 */
	CreateResult create(String ownerId, CreateLinkRequest request, String idempotencyKey) {
		String idempotencyPk = null;
		String requestHash = null;
		if (idempotencyKey != null) {
			if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
				throw new ApiException(HttpStatus.BAD_REQUEST,
						"Idempotency-Key must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
			}
			idempotencyPk = IdempotencyRecordMapper.pk(ownerId, Hashing.sha256Hex(idempotencyKey));
			requestHash = requestHash(request);
			// Checked before validation, so a retry gets the original answer even if, say, expiresAt has passed since.
			var existing = store.findIdempotencyRecord(idempotencyPk);
			if (existing.isPresent()) {
				return replay(existing.get(), requestHash);
			}
		}

		var now = clock.instant();
		validator.validate(request, now);
		var createdAt = now.truncatedTo(ChronoUnit.MILLIS);

		try {
			var created = (request.customAlias() != null)
					? write(new NewLink(request.customAlias(), null, request.longUrl(), ownerId, createdAt,
							request.expiresAt()), idempotencyPk, requestHash)
					: writeWithGeneratedCode(request, ownerId, createdAt, idempotencyPk, requestHash);
			meterRegistry.counter("links.created", "kind", (request.customAlias() != null) ? "alias" : "generated")
				.increment();
			return new CreateResult(created, false);
		}
		catch (IdempotencyKeyTakenException concurrent) {
			// A concurrent request with the same key committed first: answer with its result.
			return replay(store.findIdempotencyRecord(idempotencyPk).orElseThrow(), requestHash);
		}
	}

	private LinkResponse writeWithGeneratedCode(CreateLinkRequest request, String ownerId, Instant createdAt,
			String idempotencyPk, String requestHash) {
		for (int attempt = 1; ; attempt++) {
			var id = idAllocator.nextId();
			var link = new NewLink(CodeGenerator.encode(id), id, request.longUrl(), ownerId, createdAt,
					request.expiresAt());
			try {
				return write(link, idempotencyPk, requestHash);
			}
			catch (CodeTakenException taken) {
				if (attempt == MAX_GENERATED_ATTEMPTS) {
					throw new IllegalStateException("No free code after " + attempt + " IDs", taken);
				}
			}
		}
	}

	private LinkResponse write(NewLink link, String idempotencyPk, String requestHash) {
		var response = link.toResponse(properties);
		var item = LinkItemMapper.toItem(link);
		try {
			if (idempotencyPk == null) {
				store.putLink(item);
			}
			else {
				store.putLinkWithIdempotencyRecord(item,
						IdempotencyRecordMapper.toItem(idempotencyPk, requestHash, response, clock.instant()));
			}
		}
		catch (CodeTakenException taken) {
			if (link.seq() == null) {
				throw new ApiException(HttpStatus.CONFLICT, "The alias '" + link.code() + "' is already taken");
			}
			throw taken;
		}
		return response;
	}

	private static CreateResult replay(Map<String, AttributeValue> record, String requestHash) {
		if (!IdempotencyRecordMapper.requestHash(record).equals(requestHash)) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
					"This Idempotency-Key was already used with a different request body");
		}
		return new CreateResult(IdempotencyRecordMapper.response(record), true);
	}

	private static String requestHash(CreateLinkRequest request) {
		return Hashing.sha256Hex(String.join("\n", Objects.toString(request.longUrl(), ""),
				Objects.toString(request.customAlias(), ""), Objects.toString(request.expiresAt(), "")));
	}

	/**
	 * {@code PATCH}: applies the change on top of the latest version. Owners may switch between
	 * {@code ACTIVE} and {@code DISABLED} and set a future expiry; {@code BLOCKED} and {@code DELETED} links can't be
	 * changed. A change that matches the current state writes nothing.
	 * @return the link after the change
	 */
	LinkResponse update(String ownerId, String code, LinkChange change) {
		var newExpiry = (change.expiresAt() == null) ? null : change.expiresAt().truncatedTo(ChronoUnit.MILLIS);
		if (newExpiry != null && !newExpiry.isAfter(clock.instant())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "expiresAt must be in the future");
		}
		for (int attempt = 1; ; attempt++) {
			var current = ownedLink(ownerId, code);
			if (current.status() == LinkStatus.BLOCKED || current.status() == LinkStatus.DELETED) {
				throw new ApiException(HttpStatus.CONFLICT,
						"This link is " + current.status() + " and can't be changed");
			}

			var set = new LinkedHashMap<String, AttributeValue>();
			var audit = new LinkedHashMap<String, String[]>();
			var status = current.status();
			var expiresAt = current.expiresAt();
			if (change.status() != null && change.status() != current.status()) {
				status = change.status();
				set.put("status", AttributeValue.fromS(status.name()));
				audit.put("status", new String[] { current.status().name(), status.name() });
			}
			if (newExpiry != null && !newExpiry.equals(current.expiresAt())) {
				expiresAt = newExpiry;
				set.put("expiresAt", AttributeValue.fromS(Timestamps.format(newExpiry)));
				audit.put("expiresAt", new String[] {
						(current.expiresAt() == null) ? null : Timestamps.format(current.expiresAt()),
						Timestamps.format(newExpiry) });
			}
			if (set.isEmpty()) {
				return current.toResponse(properties);
			}

			var now = clock.instant();
			var action = (status == LinkStatus.DISABLED && current.status() != LinkStatus.DISABLED) ? "DISABLED"
					: "UPDATED";
			try {
				store.updateLink(current, set, AuditEntryMapper.toItem(code, now, action, ownerId, audit),
						Timestamps.format(now));
				meterRegistry.counter("links.changed", "action", action).increment();
				return new StoredLink(code, current.longUrl(), ownerId, status, current.createdAt(), expiresAt,
						current.version() + 1)
					.toResponse(properties);
			}
			catch (LinkChangedException changedMeanwhile) {
				// Re-read: the next pass reports 404 or 409 if that's what changed, or retries on the new version.
				if (attempt == MAX_UPDATE_ATTEMPTS) {
					throw new ApiException(HttpStatus.CONFLICT, "The link changed while updating it; try again");
				}
			}
		}
	}

	/** AP6: one link, strongly consistent, so it reflects a change made a moment ago. */
	LinkResponse get(String ownerId, String code) {
		return ownedLink(ownerId, code).toResponse(properties);
	}

	/**
	 * AP9: the caller's links, newest first, including disabled and blocked ones. Deleted owners' tombstones leave
	 * the index (AP19), so they never appear.
	 */
	LinkPage list(String ownerId, int limit, String cursor) {
		if (limit < 1 || limit > MAX_PAGE_SIZE) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_PAGE_SIZE);
		}
		var after = (cursor == null) ? null : cursors.decode(ownerId, cursor);
		var page = store.listLinks(ownerId, limit, after);
		var items = page.items().stream().map(item -> LinkItemMapper.fromIndexItem(item, properties)).toList();
		return new LinkPage(items, (page.lastGsi1sk() == null) ? null : cursors.encode(ownerId, page.lastGsi1sk()));
	}

	/** {@code DELETE}: disables the link (AP8). Already disabled is fine, so retries are safe. */
	void disable(String ownerId, String code) {
		update(ownerId, code, LinkChange.disable());
	}

	/** Another owner's link is {@code 404}, not {@code 403}, so codes can't be probed for existence (§4). */
	private StoredLink ownedLink(String ownerId, String code) {
		return store.findLink(code)
			.filter(link -> link.ownerId().equals(ownerId))
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No link with code '" + code + "'"));
	}

	record CreateResult(LinkResponse link, boolean replayed) {
	}

}
