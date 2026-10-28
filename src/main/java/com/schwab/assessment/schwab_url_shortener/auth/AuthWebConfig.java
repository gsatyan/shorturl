package com.schwab.assessment.schwab_url_shortener.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every management and stats endpoint needs an API key; redirects ({@code GET /{code}}) and actuator don't.
 */
@Configuration(proxyBeanMethods = false)
class AuthWebConfig implements WebMvcConfigurer {

	private final ApiKeyAuthenticationInterceptor apiKeyAuthentication;

	AuthWebConfig(ApiKeyAuthenticationInterceptor apiKeyAuthentication) {
		this.apiKeyAuthentication = apiKeyAuthentication;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(apiKeyAuthentication).addPathPatterns("/api/**");
	}

}
