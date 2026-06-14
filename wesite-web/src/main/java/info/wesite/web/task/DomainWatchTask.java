package info.wesite.web.task;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.DomainWatchNotifyLog;
import info.wesite.core.mail.Mail;
import info.wesite.core.mail.MailSendResult;
import info.wesite.core.mail.MailSender;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainWatchNotifyLogService;
import info.wesite.core.service.DomainWatchService;
import info.wesite.core.utils.RandomUtils;

/**
 * 域名过期监控定时任务
 * 每天 03:00 更新过期信息；08:00 发送到期提醒邮件
 */
@Profile({"prod", "mac"})
@Component
@EnableScheduling
public class DomainWatchTask {

    private static final Logger log = LoggerFactory.getLogger(DomainWatchTask.class);

    private static final String BASE_URL = "https://whose.domains";

    @Autowired
    private DomainWatchService domainWatchService;

    @Autowired
    private DomainService domainService;

    @Autowired(required = false)
    private MailSender mailSender;

    @Autowired
    private DomainWatchNotifyLogService notifyLogService;

    // ─────────────────────────────────────────────────────────────────────────
    // 每天 03:00 — 刷新过期日期信息
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 3 * * ?")
    public void checkDomainExpiry() {
        log.info("域名过期监控任务开始（刷新过期日期）");
        int page = 1, size = 100, updated = 0;
        while (true) {
            List<DomainWatch> list = domainWatchService.page(
                    new Page<>(page, size),
                    Wrappers.<DomainWatch>lambdaQuery()
                            .eq(DomainWatch::getStatus, DomainWatch.STATUS_ACTIVE)
                            .orderByAsc(DomainWatch::getId)).getRecords();
            if (list == null || list.isEmpty()) break;
            for (DomainWatch w : list) {
                try { if (refreshWatchInfo(w)) updated++; } catch (Exception e) {
                    log.error("刷新域名 {} 失败", w.getDomainName(), e);
                }
            }
            if (list.size() < size) break;
            page++;
        }
        log.info("域名过期监控任务完成，更新 {} 条", updated);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 每天 08:00 — 发送到期提醒邮件
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 8 * * ?")
    public void sendExpiryNotifications() {
        if (mailSender == null) {
            log.warn("MailSender 未配置，跳过邮件通知任务");
            return;
        }
        log.info("域名到期邮件通知任务开始");
        int page = 1, size = 100, sent = 0, failed = 0;
        while (true) {
            List<DomainWatch> list = domainWatchService.page(
                    new Page<>(page, size),
                    Wrappers.<DomainWatch>lambdaQuery()
                            .eq(DomainWatch::getStatus, DomainWatch.STATUS_ACTIVE)
                            .isNotNull(DomainWatch::getNotifyEmail)
                            .ne(DomainWatch::getNotifyType, DomainWatch.NOTIFY_NONE)
                            .orderByAsc(DomainWatch::getId)).getRecords();
            if (list == null || list.isEmpty()) break;

            // 批量构建待发邮件
            List<Mail> batch = new ArrayList<>();
            List<DomainWatch> batchWatch = new ArrayList<>();
            for (DomainWatch w : list) {
                if (!shouldNotify(w)) continue;
                if (StringUtils.isBlank(w.getNotifyEmail())) continue;
                int daysLeft = daysUntilExpiry(w.getExpiryDate());
                Map<String, Object> vars = buildTemplateVars(w, daysLeft);
                Mail mail = Mail.builder()
                        .to(List.of(w.getNotifyEmail()))
                        .subject("⚠️ Domain Expiry Alert: " + w.getDomainName() + " expires in " + daysLeft + " day(s)")
                        .templateName("email/domain-expire-notify")
                        .templateVariables(vars)
                        .build();
                batch.add(mail);
                batchWatch.add(w);
            }

            // 逐一发送（SmtpMailSender 内部已做限速）
            for (int i = 0; i < batch.size(); i++) {
                DomainWatch w = batchWatch.get(i);
                int daysLeft = daysUntilExpiry(w.getExpiryDate());
                MailSendResult result = mailSender.send(batch.get(i));
                DomainWatchNotifyLog logEntry = buildLog(w, daysLeft, result);
                try { notifyLogService.save(logEntry); } catch (Exception e) {
                    log.error("写入通知日志失败 watchId={}", w.getId(), e);
                }
                if (result.isSuccess()) {
                    sent++;
                    w.setLastNotifyTime(new Date());
                    domainWatchService.updateById(w);
                } else {
                    failed++;
                    log.warn("邮件发送失败 domain={} email={} reason={}", w.getDomainName(), w.getNotifyEmail(), result.getErrorMessage());
                }
            }

            if (list.size() < size) break;
            page++;
        }
        log.info("域名到期邮件通知完成，成功={} 失败={}", sent, failed);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 每小时整点 — 失败重试（retry_count < 3 且 status=FAIL 且 7 天内）
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 * * * ?")
    public void retryFailedNotifications() {
        if (mailSender == null) return;
        List<DomainWatchNotifyLog> failLogs = notifyLogService.list(
                Wrappers.<DomainWatchNotifyLog>lambdaQuery()
                        .eq(DomainWatchNotifyLog::getSendStatus, DomainWatchNotifyLog.SEND_STATUS_FAIL)
                        .lt(DomainWatchNotifyLog::getRetryCount, 3)
                        .gt(DomainWatchNotifyLog::getSentAt, DateUtils.addDays(new Date(), -7)));
        if (failLogs.isEmpty()) return;
        log.info("失败重试：找到 {} 条待重试邮件", failLogs.size());
        for (DomainWatchNotifyLog fl : failLogs) {
            try {
                DomainWatch w = domainWatchService.getById(fl.getWatchId());
                if (w == null || w.getStatus() != DomainWatch.STATUS_ACTIVE) continue;
                if (StringUtils.isBlank(fl.getToEmail())) continue;
                int daysLeft = daysUntilExpiry(w.getExpiryDate());
                Mail mail = Mail.builder()
                        .to(List.of(fl.getToEmail()))
                        .subject("⚠️ Domain Expiry Alert: " + fl.getDomainName() + " expires in " + daysLeft + " day(s)")
                        .templateName("email/domain-expire-notify")
                        .templateVariables(buildTemplateVars(w, daysLeft))
                        .build();
                MailSendResult result = mailSender.send(mail);
                fl.setRetryCount(fl.getRetryCount() == null ? 1 : fl.getRetryCount() + 1);
                fl.setSentAt(new Date());
                if (result.isSuccess()) {
                    fl.setSendStatus(DomainWatchNotifyLog.SEND_STATUS_SUCCESS);
                    fl.setErrorMsg(null);
                    w.setLastNotifyTime(new Date());
                    domainWatchService.updateById(w);
                } else {
                    fl.setSendStatus(DomainWatchNotifyLog.SEND_STATUS_FAIL);
                    fl.setErrorMsg(result.getErrorMessage());
                }
                notifyLogService.updateById(fl);
            } catch (Exception e) {
                log.error("重试邮件失败 logId={}", fl.getId(), e);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 私有工具方法
    // ─────────────────────────────────────────────────────────────────────────

    private boolean refreshWatchInfo(DomainWatch watch) {
        if (watch.getLastCheckTime() != null
                && watch.getLastCheckTime().after(DateUtils.addHours(new Date(), -24))) {
            return false;
        }
        Domain domain = watch.getDomainId() != null
                ? domainService.getById(watch.getDomainId())
                : domainService.getOne(Wrappers.<Domain>lambdaQuery().eq(Domain::getName, watch.getDomainName()));

        boolean changed = false;
        if (domain != null) {
            if (domain.getRegistrar() != null && !domain.getRegistrar().equals(watch.getRegistrar())) {
                watch.setRegistrar(domain.getRegistrar()); changed = true;
            }
            if (domain.getRegistExpiryDateText() != null
                    && !domain.getRegistExpiryDateText().equals(watch.getExpiryDateText())) {
                watch.setExpiryDateText(domain.getRegistExpiryDateText());
                watch.setExpiryDate(domain.getExpiryDate()); changed = true;
            }
            if (watch.getDomainId() == null) { watch.setDomainId(domain.getId()); changed = true; }
        }
        watch.setLastCheckTime(new Date());
        watch.setUpdateTime(new Date());
        domainWatchService.updateById(watch);
        return changed;
    }

    private boolean shouldNotify(DomainWatch watch) {
        if (watch.getNotifyType() == null || watch.getNotifyType() == DomainWatch.NOTIFY_NONE) return false;
        if (watch.getExpiryDate() == null) return false;
        // 最近 7 天内已通知过，跳过
        if (watch.getLastNotifyTime() != null
                && watch.getLastNotifyTime().after(DateUtils.addDays(new Date(), -7))) return false;

        Date now = new Date();
        Date exp = watch.getExpiryDate();
        if (exp.before(now)) return true;

        int type = watch.getNotifyType();
        boolean within7  = exp.before(DateUtils.addDays(now, 7));
        boolean within30 = exp.before(DateUtils.addDays(now, 30));
        if ((type == DomainWatch.NOTIFY_7_DAYS  || type == DomainWatch.NOTIFY_BOTH) && within7)  return true;
        if ((type == DomainWatch.NOTIFY_30_DAYS || type == DomainWatch.NOTIFY_BOTH) && within30) return true;
        return false;
    }

    private int daysUntilExpiry(Date expiryDate) {
        if (expiryDate == null) return -1;
        return (int) Math.ceil((expiryDate.getTime() - System.currentTimeMillis()) / 86_400_000.0);
    }

    private Map<String, Object> buildTemplateVars(DomainWatch w, int daysLeft) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("domainName",    w.getDomainName());
        vars.put("expiryDate",    w.getExpiryDateText() != null ? w.getExpiryDateText() : "Unknown");
        vars.put("daysLeft",      daysLeft);
        vars.put("dashboardUrl",  BASE_URL + "/user/watchlist");
        vars.put("unsubscribeUrl",BASE_URL + "/api/domain-watch/unwatch/" + w.getId()
                + "?token=" + w.getId()); // simple token; production should use HMAC
        vars.put("registrar",     w.getRegistrar() != null ? w.getRegistrar() : "Unknown");
        return vars;
    }

    private DomainWatchNotifyLog buildLog(DomainWatch w, int daysLeft, MailSendResult result) {
        DomainWatchNotifyLog entry = new DomainWatchNotifyLog();
        entry.setId(RandomUtils.generateId());
        entry.setWatchId(w.getId());
        entry.setToEmail(w.getNotifyEmail());
        entry.setDomainName(w.getDomainName());
        entry.setDaysLeft(daysLeft);
        entry.setSentAt(new Date());
        entry.setSendStatus(result.isSuccess()
                ? DomainWatchNotifyLog.SEND_STATUS_SUCCESS
                : DomainWatchNotifyLog.SEND_STATUS_FAIL);
        entry.setErrorMsg(result.isSuccess() ? null : result.getErrorMessage());
        entry.setRetryCount(0);
        entry.setCreateTime(new Date());
        return entry;
    }
}
