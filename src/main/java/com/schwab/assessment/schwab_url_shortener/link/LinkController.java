package com.schwab.assessment.schwab_url_shortener.link;

import java.util.Map;

import com.schwab.assessment.schwab_url_shortener.auth.ApiKeyAuthenticationInterceptor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Link management API (docs/DESIGN.md §4). Callers are authenticated by {@link ApiKeyAuthenticationInterceptor}
 * before the request body is read.
 */
@RestController
@RequestMapping("/api/v1/urls")
class LinkController {

	private final LinkService linkService;

	private final LinksProperties properties;

	LinkController(LinkService linkService, LinksProperties properties) {
		this.linkService = linkService;
		this.properties = properties;
	}

	@PostMapping
	ResponseEntity<LinkResponse> create(@RequestAttribute(ApiKeyAuthenticationInterceptor.OWNER_ID) String ownerId,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CreateLinkRequest request) {
		var result = linkService.create(ownerId, request, idempotencyKey);
		var response = ResponseEntity.status(HttpStatus.CREATED)
			.location(properties.baseUrl().resolve("/api/v1/urls/" + result.link().code()))
			.cacheControl(CacheControl.noStore());
		if (result.replayed()) {
			response.header("Idempotent-Replayed", "true");
		}
		return response.body(result.link());
	}

	@GetMapping("/{code}")
	ResponseEntity<LinkResponse> get(@RequestAttribute(ApiKeyAuthenticationInterceptor.OWNER_ID) String ownerId,
			@PathVariable String code) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(linkService.get(ownerId, code));
	}

	@GetMapping
	ResponseEntity<LinkPage> list(@RequestAttribute(ApiKeyAuthenticationInterceptor.OWNER_ID) String ownerId,
			@RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String cursor) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(linkService.list(ownerId, limit, cursor));
	}

	@PatchMapping("/{code}")
	ResponseEntity<LinkResponse> update(@RequestAttribute(ApiKeyAuthenticationInterceptor.OWNER_ID) String ownerId,
			@PathVariable String code, @RequestBody Map<String, Object> body) {
		var link = linkService.update(ownerId, code, LinkChange.fromBody(body));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(link);
	}

	@DeleteMapping("/{code}")
	ResponseEntity<Void> delete(@RequestAttribute(ApiKeyAuthenticationInterceptor.OWNER_ID) String ownerId,
			@PathVariable String code) {
		linkService.disable(ownerId, code);
		return ResponseEntity.noContent().build();
	}

}
