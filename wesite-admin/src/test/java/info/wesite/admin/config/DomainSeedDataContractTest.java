package info.wesite.admin.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class DomainSeedDataContractTest {

    private static final Pattern EXT_VALUES = Pattern.compile(
        "values\\(replace\\(uuid\\(\\),'-',''\\), '([^']+)', '([^']+)', '([^']+)'"
    );

    @Test
    void executableSecondaryLevelDomainSeedsUseCanonicalNameAndDotNameColumns() throws Exception {
        List<String> inserts = Files.readAllLines(seedFile(), StandardCharsets.UTF_8).stream()
            .filter(line -> line.startsWith("insert into WEB_DOMAIN_TLD_EXT"))
            .toList();

        assertTrue(inserts.size() > 1000, "expected the complete secondary-level domain seed set");
        for (String insert : inserts) {
            assertTrue(insert.contains("(ID, NAME, DOT_NAME, TLD_NAME,"), insert);
            Matcher values = EXT_VALUES.matcher(insert);
            assertTrue(values.find(), insert);
            String name = values.group(1);
            String dotName = values.group(2);
            String tldName = values.group(3);
            assertFalse(name.startsWith("."), insert);
            assertEquals("." + name, dotName, insert);
            assertTrue(tldName.startsWith("."), insert);
        }
    }

    private static Path seedFile() throws IOException {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path fromReactor = workingDirectory.resolve("doc/insert.sql");
        Path fromModule = workingDirectory.resolve("../doc/insert.sql").normalize();
        Path seed = Files.isRegularFile(fromReactor) ? fromReactor : fromModule;
        if (!Files.isRegularFile(seed)) {
            throw new IOException("Missing executable database seed: " + seed);
        }
        return seed;
    }
}
