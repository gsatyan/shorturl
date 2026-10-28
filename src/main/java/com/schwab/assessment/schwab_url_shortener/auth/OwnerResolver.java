package com.schwab.assessment.schwab_url_shortener.auth;

/**
 * Caller identity (docs/DESIGN.md §12). With the gateway out of scope (§2.3), the service authenticates callers
 * itself.
 */
public interface OwnerResolver {

	/**
	 * @param credential the API key from the {@code Authorization: Bearer} header
	 * @return the owner the key belongs to
	 * @throws com.schwab.assessment.schwab_url_shortener.domain.ApiException {@code 401} if the key is unknown or
	 * revoked
	 */
	String resolveOwnerId(String credential);

}
