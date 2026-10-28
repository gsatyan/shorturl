package com.schwab.assessment.schwab_url_shortener;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchwabUrlShortenerApplicationTests {

	@Test
	void contextLoads() {
	}

}
