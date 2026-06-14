package info.wesite.admin.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import info.wesite.core.utils.Constants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "管理后台")
@Controller
@RequestMapping("/admin")
public class AdminController {

    @Operation(summary = "管理后台首页")
    @GetMapping("")
    public String adminHome(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Admin Dashboard - Whose.Domains");
        return "admin/dashboard";
    }
    
    @Operation(summary = "管理后台仪表盘")
    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Admin Dashboard - Whose.Domains");
        return "admin/dashboard";
    }
}