package info.wesite.web.support;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public final class SupportLinkModelAdvice {

    private final SupportLink supportLink;

    public SupportLinkModelAdvice(SupportLink supportLink) {
        this.supportLink = supportLink;
    }

    @ModelAttribute
    public void addSupportLink(Model model) {
        supportLink.url().ifPresent(url -> model.addAttribute("supportUrl", url));
    }
}
