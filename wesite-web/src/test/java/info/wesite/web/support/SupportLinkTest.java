package info.wesite.web.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SupportLinkTest {

    @Test
    void blankConfigurationDisablesSupportLinks() {
        assertTrue(new SupportLink("  ").url().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.paypal.com/ncp/payment/ABC123",
            "https://paypal.com/donate/?hosted_button_id=ABC123",
            "https://paypal.me/whose-domains"
    })
    void officialPaypalHttpsLinksAreAvailable(String configuredUrl) {
        assertEquals(configuredUrl, new SupportLink("  " + configuredUrl + "  ").url().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://www.paypal.com/ncp/payment/ABC123",
            "https://paypal.com.evil.example/ncp/payment/ABC123",
            "https://paypal.com@evil.example/ncp/payment/ABC123",
            "https://user@paypal.com/ncp/payment/ABC123",
            "https://www.paypal.com:8443/ncp/payment/ABC123",
            "not-a-url"
    })
    void unsafeOrNonPaypalLinksFailConfiguration(String configuredUrl) {
        assertThrows(IllegalArgumentException.class, () -> new SupportLink(configuredUrl));
    }
}
