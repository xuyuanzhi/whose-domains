package info.wesite.core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MagicLinkTokenUtilsTest {

    @Test
    void generatesOpaqueTokenWhoseStoredHashCanBeVerified() {
        String token = MagicLinkTokenUtils.generateToken();
        String tokenHash = MagicLinkTokenUtils.hash(token);

        assertTrue(token.length() >= 40);
        assertNotEquals(token, tokenHash);
        assertTrue(MagicLinkTokenUtils.matches(token, tokenHash));
        assertFalse(MagicLinkTokenUtils.matches("different-token", tokenHash));
    }

    @Test
    void generatesDistinctTokens() {
        assertNotEquals(MagicLinkTokenUtils.generateToken(), MagicLinkTokenUtils.generateToken());
    }

    @Test
    void hashingIsDeterministic() {
        assertEquals(MagicLinkTokenUtils.hash("known-token"), MagicLinkTokenUtils.hash("known-token"));
    }
}
