package com.schwab.assessment.schwab_url_shortener.link;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Link settings. Defaults are in application.yaml.
 *
 * @param baseUrl         public origin of short links, e.g. {@code https://sch.wb}; links to it are rejected
 * @param blockedHosts    destinations rejected with {@code 422}; subdomains are blocked too
 * @param reservedAliases path segments that can't be used as custom aliases
 * @param idBlockSize     IDs leased per call to the counter (§6.1)
 */
@ConfigurationProperties("app.links")
public record LinksProperties(URI baseUrl, List<String> blockedHosts, List<String> reservedAliases,
		int idBlockSize) {

	public LinksProperties {
		blockedHosts = (blockedHosts == null) ? List.of() : List.copyOf(blockedHosts);
		reservedAliases = (reservedAliases == null) ? List.of() : List.copyOf(reservedAliases);
	}

}
