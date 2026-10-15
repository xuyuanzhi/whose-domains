package info.wesite.core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DomainNameValidatorTest {

    @Test
    void acceptsAndNormalizesPlainDomains() {
        assertEquals("example.com", DomainNameValidator.normalize("  EXAMPLE.com.  "));
        assertTrue(DomainNameValidator.isValid("example.com"));
        assertTrue(DomainNameValidator.isValid("sub.example.co"));
        assertTrue(DomainNameValidator.isValid("xn--fiqs8s.com"));
    }

    @Test
    void rejectsUnsafeOrMalformedInput() {
        assertNull(DomainNameValidator.normalize(""));
        assertFalse(DomainNameValidator.isValid(null));
        assertFalse(DomainNameValidator.isValid("localhost"));
        assertFalse(DomainNameValidator.isValid("127.0.0.1"));
        assertFalse(DomainNameValidator.isValid("http://example.com"));
        assertFalse(DomainNameValidator.isValid("example.com/path"));
        assertFalse(DomainNameValidator.isValid("exa mple.com"));
        assertFalse(DomainNameValidator.isValid("-bad.example.com"));
        assertFalse(DomainNameValidator.isValid("bad-.example.com"));
    }

    @Test
    void rejectsOverlongLabelsAndNames() {
        String longLabel = "a".repeat(64) + ".com";
        assertFalse(DomainNameValidator.isValid(longLabel));
        String longName = ("a".repeat(60) + ".").repeat(5) + "com";
        assertFalse(DomainNameValidator.isValid(longName));
    }
}
