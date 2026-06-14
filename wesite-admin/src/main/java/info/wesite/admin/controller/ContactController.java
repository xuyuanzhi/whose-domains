package info.wesite.admin.controller;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.ContactInfo;
import info.wesite.core.service.ContactInfoService;
import info.wesite.core.utils.Constants;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "联系信息管理")
@Controller
@RequestMapping("/admin/contacts")
public class ContactController {

    @Autowired
    private ContactInfoService contactInfoService;

    @Operation(summary = "联系信息管理页面")
    @GetMapping("")
    public String contacts(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Contact Messages - Admin Panel");
        return "admin/contacts";
    }

    @Operation(summary = "获取联系信息分页列表")
    @GetMapping("/list")
    @ResponseBody
    public ResponseJson<IPage<ContactInfo>> getContactsList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        
        Page<ContactInfo> pageInfo = new Page<>(page, size);
        IPage<ContactInfo> result = contactInfoService.page(pageInfo, 
            Wrappers.<ContactInfo>lambdaQuery()
                .orderByDesc(ContactInfo::getCreateTime));
        
        return ResponseJson.success(result);
    }

    @Operation(summary = "删除联系信息")
    @DeleteMapping("/delete")
    @ResponseBody
    public ResponseJson<String> deleteContacts(@RequestBody Map<String, List<String>> params) {
        List<String> ids = params.get("ids");
        if (ids == null || ids.isEmpty()) {
            return ResponseJson.failure("No IDs provided for deletion.");
        }
        
        boolean success = contactInfoService.removeByIds(ids);
        if (success) {
            return ResponseJson.success("Records deleted successfully.");
        } else {
            return ResponseJson.failure("Failed to delete records.");
        }
    }

    @Operation(summary = "获取单个联系信息详情")
    @GetMapping("/{id}")
    @ResponseBody
    public ResponseJson<ContactInfo> getContactDetail(@PathVariable("id") String id) {
        ContactInfo contact = contactInfoService.getById(id);
        if (contact != null) {
            return ResponseJson.success(contact);
        } else {
            return ResponseJson.failure("Contact not found.");
        }
    }

    @Operation(summary = "更新联系信息状态")
    @PostMapping("/{id}/status")
    @ResponseBody
    public ResponseJson<String> updateContactStatus(
            @PathVariable("id") String id,
            @RequestParam Integer status) {
        
        ContactInfo contact = contactInfoService.getById(id);
        if (contact == null) {
            return ResponseJson.failure("Contact not found.");
        }
        
        contact.setStatus(status);
        contact.setUpdateTime(new Date());
        boolean updated = contactInfoService.updateById(contact);
        
        if (updated) {
            return ResponseJson.success("Status updated successfully.");
        } else {
            return ResponseJson.failure("Failed to update status.");
        }
    }
    
    @Operation(summary = "获取联系信息统计")
    @GetMapping("/stats")
    @ResponseBody
    public ResponseJson<Map<String, Object>> getContactStats() {
        Map<String, Object> stats = new HashMap<>();
        
        // 总联系数
        long totalContacts = contactInfoService.count();
        stats.put("total", totalContacts);
        
        // 今天的联系数
        long todaysContacts = contactInfoService.count(
            Wrappers.<ContactInfo>lambdaQuery()
                .ge(ContactInfo::getCreateTime, new java.sql.Date(System.currentTimeMillis()))
        );
        stats.put("today", todaysContacts);
        
        // 待处理数 (可以根据实际业务逻辑调整)
        stats.put("pending", 0); // 可以根据实际状态计算
        
        ResponseJson<Map<String, Object>> response = new ResponseJson<>();
        response.setCode(0);
        response.setMsg("success");
        response.setData(stats);
        return response;
    }
}