package info.wesite.web.controller.api;

import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.config.AccessControl;
import info.wesite.core.config.AccessControl.Level;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainWatchService;
import info.wesite.core.utils.RandomUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Domain Watch API - 域名监控")
@RestController
@RequestMapping("/api/domain-watch")
@AccessControl(level = Level.SESSION)
public class DomainWatchController {

    private static final Logger log = LoggerFactory.getLogger(DomainWatchController.class);

    private static final int MAX_WATCH_PER_USER = 50;
    private static final String DOMAIN_REGEX = "^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.)+[a-z]{2,}$";

    @Autowired
    private DomainWatchService domainWatchService;

    @Autowired
    private DomainService domainService;

    @Operation(summary = "获取用户的域名监控列表")
    @GetMapping("/list")
    public ResponseJson<DomainWatch> listWatches() {
        String userId = UserHolder.get().getId();

        List<DomainWatch> list = domainWatchService.list(
                Wrappers.<DomainWatch>lambdaQuery()
                        .eq(DomainWatch::getUserId, userId)
                        .eq(DomainWatch::getStatus, DomainWatch.STATUS_ACTIVE)
                        .orderByAsc(DomainWatch::getExpiryDate));

        return ResponseJson.success(list);
    }

    @Operation(summary = "添加域名监控")
    @PostMapping("/watch")
    public ResponseJson<DomainWatch> watchDomain(@RequestBody DomainWatch param) {
        String userId = UserHolder.get().getId();

        // 验证域名
        if (StringUtils.isBlank(param.getDomainName())) {
            return ResponseJson.failure("Domain name is required.");
        }

        String domainName = param.getDomainName().toLowerCase().trim();
        if (!domainName.matches(DOMAIN_REGEX)) {
            return ResponseJson.failure("Invalid domain name format.");
        }

        // 检查是否已存在
        DomainWatch existing = domainWatchService.getOne(
                Wrappers.<DomainWatch>lambdaQuery()
                        .eq(DomainWatch::getUserId, userId)
                        .eq(DomainWatch::getDomainName, domainName));
        if (existing != null) {
            if (existing.getStatus() == DomainWatch.STATUS_ACTIVE) {
                return ResponseJson.failure("You are already watching this domain.");
            } else {
                // 重新激活
                existing.setStatus(DomainWatch.STATUS_ACTIVE);
                existing.setNotifyType(param.getNotifyType() != null ? param.getNotifyType() : DomainWatch.NOTIFY_BOTH);
                existing.setRemark(param.getRemark());
                existing.setUpdateBy(userId);
                existing.setUpdateTime(new Date());
                domainWatchService.updateById(existing);
                return ResponseJson.success("Domain watch reactivated.", existing);
            }
        }

        // 检查上限
        long count = domainWatchService.count(
                Wrappers.<DomainWatch>lambdaQuery()
                        .eq(DomainWatch::getUserId, userId)
                        .eq(DomainWatch::getStatus, DomainWatch.STATUS_ACTIVE));
        if (count >= MAX_WATCH_PER_USER) {
            return ResponseJson.failure("You can watch up to " + MAX_WATCH_PER_USER + " domains.");
        }

        // 尝试从数据库获取域名信息
        Domain domain = domainService.getOne(
                Wrappers.<Domain>lambdaQuery().eq(Domain::getName, domainName));

        DomainWatch watch = new DomainWatch();
        watch.setId(RandomUtils.generateId());
        watch.setUserId(userId);
        watch.setDomainName(domainName);
        watch.setNotifyType(param.getNotifyType() != null ? param.getNotifyType() : DomainWatch.NOTIFY_BOTH);
        watch.setNotifyEmail(StringUtils.trimToNull(param.getNotifyEmail()));
        watch.setRemark(param.getRemark());
        watch.setCreateBy(userId);
        watch.setCreateTime(new Date());

        if (domain != null) {
            watch.setDomainId(domain.getId());
            watch.setRegistrar(domain.getRegistrar());
            watch.setExpiryDateText(domain.getRegistExpiryDateText());
            watch.setExpiryDate(domain.getExpiryDate());
        }

        watch.setLastCheckTime(new Date());

        if (domainWatchService.save(watch)) {
            return ResponseJson.success("Domain watch added successfully.", watch);
        } else {
            return ResponseJson.failure("Failed to add domain watch.");
        }
    }

    @Operation(summary = "取消域名监控")
    @DeleteMapping("/unwatch/{id}")
    public ResponseJson<String> unwatchDomain(@PathVariable("id") String id) {
        String userId = UserHolder.get().getId();

        DomainWatch watch = domainWatchService.getOne(
                Wrappers.<DomainWatch>lambdaQuery()
                        .eq(DomainWatch::getId, id)
                        .eq(DomainWatch::getUserId, userId));

        if (watch == null) {
            return ResponseJson.failure("Watch record not found.");
        }

        watch.setStatus(DomainWatch.STATUS_INACTIVE);
        watch.setUpdateBy(userId);
        watch.setUpdateTime(new Date());

        if (domainWatchService.updateById(watch)) {
            return ResponseJson.success("Domain watch removed.", null);
        } else {
            return ResponseJson.failure("Failed to remove domain watch.");
        }
    }

    @Operation(summary = "更新域名监控设置")
    @PutMapping("/update/{id}")
    public ResponseJson<DomainWatch> updateWatch(@PathVariable("id") String id, @RequestBody DomainWatch param) {
        String userId = UserHolder.get().getId();

        DomainWatch watch = domainWatchService.getOne(
                Wrappers.<DomainWatch>lambdaQuery()
                        .eq(DomainWatch::getId, id)
                        .eq(DomainWatch::getUserId, userId)
                        .eq(DomainWatch::getStatus, DomainWatch.STATUS_ACTIVE));

        if (watch == null) {
            return ResponseJson.failure("Watch record not found.");
        }

        if (param.getNotifyType() != null) {
            watch.setNotifyType(param.getNotifyType());
        }
        if (param.getRemark() != null) {
            watch.setRemark(param.getRemark());
        }
        watch.setUpdateBy(userId);
        watch.setUpdateTime(new Date());

        if (domainWatchService.updateById(watch)) {
            return ResponseJson.success("Domain watch updated.", watch);
        } else {
            return ResponseJson.failure("Failed to update domain watch.");
        }
    }

    @Operation(summary = "检查域名是否已被当前用户关注")
    @GetMapping("/check/{domainName}")
    public ResponseJson<Boolean> checkWatch(@PathVariable("domainName") String domainName) {
        String userId = UserHolder.get().getId();
        domainName = domainName.toLowerCase().trim();

        DomainWatch watch = domainWatchService.getOne(
                Wrappers.<DomainWatch>lambdaQuery()
                        .eq(DomainWatch::getUserId, userId)
                        .eq(DomainWatch::getDomainName, domainName)
                        .eq(DomainWatch::getStatus, DomainWatch.STATUS_ACTIVE));

        return ResponseJson.success(watch != null);
    }
}
