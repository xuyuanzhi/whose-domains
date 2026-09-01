package info.wesite.web.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

class SupportLinkModelAdviceTest {

    @Test
    void configuredUrlIsAddedToTheSharedViewModel() {
        ExtendedModelMap model = new ExtendedModelMap();

        new SupportLinkModelAdvice(new SupportLink("https://www.paypal.com/ncp/payment/ABC123"))
                .addSupportLink(model);

        assertEquals("https://www.paypal.com/ncp/payment/ABC123", model.get("supportUrl"));
    }

    @Test
    void disabledSupportLeavesTheSharedViewModelUnchanged() {
        ExtendedModelMap model = new ExtendedModelMap();

        new SupportLinkModelAdvice(new SupportLink(" ")).addSupportLink(model);

        assertFalse(model.containsAttribute("supportUrl"));
    }
}
