package info.wesite.admin.controller;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.ContactInfo;
import info.wesite.core.service.ContactInfoService;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "联系消息管理")
@RestController
@RequestMapping("/admin/contacts")
public class ContactController {

    private final ContactInfoService contactInfoService;

    public ContactController(ContactInfoService contactInfoService) {
        this.contactInfoService = contactInfoService;
    }

    @Operation(summary = "获取联系消息分页列表")
    @GetMapping("/list")
    public ResponseJson<IPage<ContactInfo>> getContactsList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        Page<ContactInfo> pageInfo = new Page<>(page, size);
        IPage<ContactInfo> result = contactInfoService.page(pageInfo,
            Wrappers.<ContactInfo>lambdaQuery().orderByDesc(ContactInfo::getCreateTime));
        return ResponseJson.success(result);
    }

    @Operation(summary = "删除联系消息")
    @DeleteMapping("/delete")
    public ResponseJson<String> deleteContacts(@RequestBody(required = false) Map<String, List<String>> params) {
        List<String> ids = params == null ? null : params.get("ids");
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(StringUtils::isBlank)) {
            return ResponseJson.failure("请选择要删除的联系消息");
        }

        if (contactInfoService.removeByIds(ids)) {
            return ResponseJson.success("联系消息删除成功", null);
        }
        return ResponseJson.failure("联系消息删除失败");
    }

    @Operation(summary = "获取联系消息详情")
    @GetMapping("/{id}")
    public ResponseJson<ContactInfo> getContactDetail(@PathVariable("id") String id) {
        String normalizedId = StringUtils.trimToNull(id);
        if (normalizedId == null) {
            return ResponseJson.failure("联系消息ID不能为空");
        }

        ContactInfo contact = contactInfoService.getById(normalizedId);
        if (contact == null) {
            return ResponseJson.failure("联系消息不存在");
        }
        return ResponseJson.success(contact);
    }

    @Operation(summary = "更新联系消息状态")
    @PostMapping("/status")
    public ResponseJson<String> updateContactStatus(@RequestBody(required = false) ContactInfo request) {
        String id = request == null ? null : StringUtils.trimToNull(request.getId());
        if (id == null) {
            return ResponseJson.failure("联系消息ID不能为空");
        }
        if (!isContactStatus(request.getStatus())) {
            return ResponseJson.failure("联系消息状态只能是待处理或已处理");
        }

        ContactInfo contact = contactInfoService.getById(id);
        if (contact == null) {
            return ResponseJson.failure("联系消息不存在");
        }

        contact.setStatus(request.getStatus());
        contact.setUpdateBy("admin");
        contact.setUpdateTime(new Date());
        if (contactInfoService.updateById(contact)) {
            return ResponseJson.success("联系消息状态更新成功", null);
        }
        return ResponseJson.failure("联系消息状态更新失败");
    }

    @Operation(summary = "获取联系消息统计")
    @GetMapping("/stats")
    public ResponseJson<Map<String, Object>> getContactStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", contactInfoService.count());
        stats.put("pending", contactInfoService.count(
            Wrappers.<ContactInfo>lambdaQuery().eq(ContactInfo::getStatus, BaseEntity.STATUS_ACTIVE)));
        stats.put("processed", contactInfoService.count(
            Wrappers.<ContactInfo>lambdaQuery().eq(ContactInfo::getStatus, BaseEntity.STATUS_INACTIVE)));
        return ResponseJson.success(stats);
    }

    private static boolean isContactStatus(Integer status) {
        return status != null
            && (status == BaseEntity.STATUS_ACTIVE || status == BaseEntity.STATUS_INACTIVE);
    }
}
