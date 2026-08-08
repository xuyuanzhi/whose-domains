package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class RobotsFileTest {

    @Test
    void robotsDeclaresTheProductionSitemap() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/static/robots.txt")) {
            String robots = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            long directives = robots.lines()
                    .filter(line -> line.equals("Sitemap: https://whose.domains/sitemap_all.xml"))
                    .count();
            assertEquals(1, directives);
        }
    }
}
