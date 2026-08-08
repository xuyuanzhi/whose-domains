package info.wesite.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import info.wesite.core.config.UserHolder;
import info.wesite.core.utils.Constants;
import info.wesite.web.auth.ReturnTargetService;
import jakarta.servlet.http.HttpServletRequest;

@Controller
public class LoginController {

    private final ReturnTargetService returnTargets;

    public LoginController(ReturnTargetService returnTargets) {
        this.returnTargets = returnTargets;
    }

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String returnTo, Model model) {
        String target = returnTargets.resolve(returnTo);
        if (UserHolder.get() != null) {
            return "redirect:" + target;
        }
        model.addAttribute(Constants.PAGE_TITLE, "Sign In - Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Sign in securely to continue to your account.");
        model.addAttribute("returnTo", target);
        return "login";
    }

    @GetMapping("/login/google")
    public String google(@RequestParam(required = false) String returnTo, HttpServletRequest request) {
        request.getSession(true).setAttribute(ReturnTargetService.GOOGLE_RETURN_TARGET_SESSION_KEY,
                returnTargets.resolve(returnTo));
        return "redirect:/oauth2/authorization/google";
    }
}
