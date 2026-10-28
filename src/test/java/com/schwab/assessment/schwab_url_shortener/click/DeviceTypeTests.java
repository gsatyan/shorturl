package com.schwab.assessment.schwab_url_shortener.click;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class DeviceTypeTests {

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1 | MOBILE
			Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36       | MOBILE
			Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Mobile/15E148 [LinkedInApp]/9.30            | MOBILE
			Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148                   | TABLET
			Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 Chrome/130.0 Safari/537.36              | TABLET
			Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36             | DESKTOP
			Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 Version/18.0 Safari/605.1.15     | DESKTOP
			Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0                              | DESKTOP
			Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)                            | BOT
			LinkedInBot/1.0 (compatible; Mozilla/5.0; Apache-HttpClient +http://www.linkedin.com)              | BOT
			facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)                           | BOT
			Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)                                          | BOT
			curl/8.7.1                                                                                          | BOT
			SomeUnknownClient/1.0                                                                               | OTHER
			""")
	void classifiesUserAgents(String userAgent, DeviceType expected) {
		assertThat(DeviceType.fromUserAgent(userAgent)).isEqualTo(expected);
	}

	@ParameterizedTest
	@NullAndEmptySource
	void missingUserAgentIsOther(String userAgent) {
		assertThat(DeviceType.fromUserAgent(userAgent)).isEqualTo(DeviceType.OTHER);
	}

}
