(function (root) {
    'use strict';

    var ALLOWED_CATEGORIES = ['all', 'expiry', 'security', 'domain-change', 'investment'];
    var PREFERENCE_FIELDS = ['domainExpiryEnabled', 'sslExpiryEnabled', 'domainStatusEnabled', 'dnsChangeEnabled', 'websiteAvailabilityEnabled'];
    var EMAIL_MODES = ['IMMEDIATE', 'DAILY', 'WEEKLY', 'IN_APP_ONLY'];
    var currentCategory = 'all';
    var currentPage = 1;
    var bellStarted = false;
    var visibilityBound = false;
    var unreadRequest = null;
    var lastUnreadRefreshAt = -Infinity;
    var bellTimer = null;
    var activeListController = null;
    var listGeneration = 0;

    function requestJson(url, options) {
        var config = options || {};
        config.credentials = 'include';
        return fetch(url, config).then(function (response) {
            return response.json().then(function (body) {
                if (!response.ok || !body || body.code !== 0) {
                    throw new Error(body && body.msg ? body.msg : 'The request could not be completed.');
                }
                return body.data;
            });
        });
    }

    function normalizeCategory(value) {
        return ALLOWED_CATEGORIES.indexOf(value) >= 0 ? value : 'all';
    }

    function currentDomainFilter() {
        try {
            var search = (root.location && root.location.search) || '';
            return new URLSearchParams(search).get('domain') || '';
        } catch (error) {
            return '';
        }
    }

    function safeInternalTarget(target) {
        if (typeof target !== 'string' || target.charAt(0) !== '/' || target.indexOf('//') === 0 || target.indexOf('/\\') === 0) return null;
        try {
            var base = (root.location && root.location.origin) || (typeof location !== 'undefined' && location.origin) || 'https://whose.domains';
            var parsed = new URL(target, base);
            return parsed.origin === base ? target : null;
        } catch (error) {
            return null;
        }
    }

    function canonicalRisk(value) {
        var risk = typeof value === 'string' ? value.toUpperCase() : '';
        var labels = { CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low' };
        return labels[risk]
            ? { key: risk.toLowerCase(), label: labels[risk] }
            : { key: 'unknown', label: 'Unrated' };
    }

    function trackRetentionEvent(eventName, parameters) {
        try {
            if (root.WhoseRetentionAnalytics && typeof root.WhoseRetentionAnalytics.track === 'function') {
                root.WhoseRetentionAnalytics.track(eventName, parameters);
            }
        } catch (error) {}
    }

    function notificationActionParameters(type, item) {
        var risk = canonicalRisk(item && item.risk).key;
        return {
            type: type,
            category: currentCategory,
            risk: risk === 'unknown' ? 'UNKNOWN' : risk.toUpperCase(),
            source: 'notification_center'
        };
    }

    function formatTime(value) {
        if (!value) return 'Time unavailable';
        var date = new Date(value);
        if (Number.isNaN(date.getTime())) return 'Time unavailable';
        return new Intl.DateTimeFormat(undefined, {
            month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
        }).format(date);
    }

    function createNotificationRow(item, ownerDocument) {
        var doc = ownerDocument || document;
        var article = doc.createElement('article');
        var rail = doc.createElement('span');
        var body = doc.createElement('div');
        var meta = doc.createElement('div');
        var risk = doc.createElement('span');
        var time = doc.createElement('time');
        var title = doc.createElement('h3');
        var content = doc.createElement('p');
        var context = doc.createElement('div');
        var domain = doc.createElement('span');
        var source = doc.createElement('span');
        var actions = doc.createElement('div');
        var target = doc.createElement('a');
        var read = doc.createElement('button');
        var remove = doc.createElement('button');
        var riskValue = canonicalRisk(item && item.risk);
        var targetPath = safeInternalTarget(item && item.targetPath);

        article.classList.add('signal-event');
        article.dataset.notificationId = String((item && item.id) || '');
        article.dataset.eventType = String((item && item.eventType) || '');
        article.setAttribute('tabindex', '-1');
        if (item && item.readAt) article.classList.add('is-read');
        rail.classList.add('signal-event__rail');
        rail.setAttribute('aria-hidden', 'true');
        body.classList.add('signal-event__body');
        meta.classList.add('signal-event__meta');
        risk.classList.add('signal-risk');
        risk.setAttribute('data-risk', riskValue.key);
        risk.textContent = riskValue.label;
        time.dateTime = (item && item.createTime) || '';
        time.setAttribute('datetime', (item && item.createTime) || '');
        time.textContent = formatTime(item && item.createTime);
        title.textContent = (item && item.title) || 'Domain signal';
        content.textContent = (item && item.content) || 'No additional detail was provided.';
        context.classList.add('signal-event__context');
        domain.textContent = (item && item.domain) || 'Domain unavailable';
        source.textContent = (item && item.source) || 'Source unavailable';
        actions.classList.add('signal-event__actions');
        target.classList.add('signal-event__target');
        target.textContent = 'Open domain evidence';
        target.hidden = !targetPath;
        if (targetPath) target.setAttribute('href', targetPath);
        target.addEventListener('click', function () {
            if (targetPath) trackRetentionEvent('notification_action_clicked',
                notificationActionParameters('open_evidence', item));
        });
        read.type = 'button';
        read.setAttribute('data-action', 'read');
        read.textContent = item && item.readAt ? 'Read' : 'Mark read';
        read.disabled = Boolean(item && item.readAt);
        remove.type = 'button';
        remove.setAttribute('data-action', 'delete');
        remove.textContent = 'Delete';

        read.addEventListener('click', function () { markRead(item.id, article, read, notificationActionParameters('mark_read', item)).catch(function () {}); });
        remove.addEventListener('click', function () { deleteNotification(item.id, article, remove, notificationActionParameters('delete', item)).catch(function () {}); });
        meta.append(risk, time);
        context.append(domain, source);
        actions.append(target, read, remove);
        body.append(meta, title, context, content, actions);
        article.append(rail, body);
        article.parts = { title: title, content: content, domain: domain, source: source, target: target, risk: risk, time: time, read: read, remove: remove };
        return article;
    }

    function setCenterState(state, message) {
        var loading = document.getElementById('notificationLoading');
        var error = document.getElementById('notificationError');
        var empty = document.getElementById('notificationEmpty');
        var status = document.getElementById('notificationStatus');
        if (loading) loading.hidden = state !== 'loading';
        if (error) error.hidden = state !== 'error';
        if (empty) empty.hidden = state !== 'empty';
        if (status && message) status.textContent = message;
    }

    function loadNotifications(category, page, append) {
        var requestedCategory = normalizeCategory(category);
        var requestedPage = Math.max(1, Number(page) || 1);
        var shouldAppend = Boolean(append) && requestedCategory === currentCategory && requestedPage > 1;
        var generation = ++listGeneration;
        if (activeListController) activeListController.abort();
        activeListController = new AbortController();
        var controller = activeListController;
        if (!shouldAppend) {
            currentCategory = requestedCategory;
            currentPage = 1;
        }
        setCenterState(shouldAppend ? 'ready' : 'loading', shouldAppend
            ? 'Loading earlier signals.' : 'Loading the latest signals.');
        var list = document.getElementById('notificationList');
        var pagination = document.getElementById('notificationPagination');
        if (list && !shouldAppend) list.replaceChildren();
        if (pagination) pagination.hidden = true;
        var domain = currentDomainFilter();
        var requestUrl = '/api/notifications?page=' + requestedPage + '&category=' + encodeURIComponent(requestedCategory);
        if (domain) requestUrl += '&domain=' + encodeURIComponent(domain);
        return requestJson(requestUrl, {
            signal: controller.signal
        })
            .then(function (data) {
                if (generation !== listGeneration) return null;
                var items = data && Array.isArray(data.items) ? data.items : [];
                if (list) items.forEach(function (item) { list.appendChild(createNotificationRow(item)); });
                currentCategory = requestedCategory;
                currentPage = requestedPage;
                var visibleCount = list ? list.children.length : items.length;
                setCenterState(visibleCount ? 'ready' : 'empty', visibleCount
                    ? String(data.total || visibleCount) + ' signals in this channel.'
                    : 'No signals in this channel.');
                if (pagination) pagination.hidden = !data || currentPage * (data.size || 20) >= (data.total || 0);
                trackRetentionEvent('notification_opened', {
                    type: 'notification_opened', category: requestedCategory, source: 'notification_center'
                });
                return data;
            })
            .catch(function (error) {
                if (generation !== listGeneration || error.name === 'AbortError') return null;
                setCenterState('error', error.message);
                return null;
            })
            .finally(function () {
                if (generation === listGeneration) activeListController = null;
            });
    }

    function refreshUnreadCount() {
        if (unreadRequest) return unreadRequest;
        lastUnreadRefreshAt = Date.now();
        var request = requestJson('/api/notifications/unread-count').then(function (data) {
            var unread = Math.max(0, Number(data && data.unreadCount) || 0);
            var live = document.getElementById('notificationCount');
            var badge = document.getElementById('notificationCountBadge');
            if (live) {
                live.textContent = unread + (unread === 1 ? ' unread notification' : ' unread notifications');
                live.setAttribute('data-count', String(unread));
            }
            if (badge) {
                badge.textContent = unread > 99 ? '99+' : String(unread);
                badge.hidden = unread === 0;
            }
            return unread;
        });
        var tracked = request.finally(function () {
            if (unreadRequest === tracked) unreadRequest = null;
        });
        unreadRequest = tracked;
        return tracked;
    }

    function markRead(id, row, button, analyticsParameters) {
        if (button) button.disabled = true;
        return requestJson('/api/notifications/' + encodeURIComponent(id) + '/read', { method: 'PUT' })
            .then(function () {
                if (row) row.classList.add('is-read');
                if (button) button.textContent = 'Read';
                if (analyticsParameters) trackRetentionEvent('notification_action_clicked', analyticsParameters);
                return refreshUnreadCount();
            })
            .catch(function (error) {
                if (button) button.disabled = false;
                setCenterState('ready', error.message);
                return null;
            });
    }

    function deleteNotification(id, row, button, analyticsParameters) {
        if (button) button.disabled = true;
        return requestJson('/api/notifications/' + encodeURIComponent(id), { method: 'DELETE' })
            .then(function () {
                var list = row && row.parentNode;
                var focusTarget = row && (row.nextElementSibling || row.previousElementSibling);
                if (list) list.removeChild(row);
                if (!focusTarget && list) {
                    list.setAttribute('tabindex', '-1');
                    focusTarget = list;
                }
                if (focusTarget) focusTarget.focus();
                if (list && !list.children.length) setCenterState('empty', 'No signals in this channel.');
                else setCenterState('ready', 'Notification deleted.');
                if (analyticsParameters) trackRetentionEvent('notification_action_clicked', analyticsParameters);
                return refreshUnreadCount().catch(function () { return null; });
            })
            .catch(function (error) {
                if (button) button.disabled = false;
                setCenterState('ready', error.message);
                return null;
            });
    }

    function markAllRead() {
        var button = document.getElementById('markAllRead');
        if (button) button.disabled = true;
        return requestJson('/api/notifications/read-all', { method: 'PUT' })
            .then(function () {
                trackRetentionEvent('notification_action_clicked', {
                    type: 'mark_all_read', category: currentCategory, source: 'notification_center'
                });
                return Promise.all([loadNotifications(currentCategory, 1), refreshUnreadCount()]);
            })
            .finally(function () { if (button) button.disabled = false; });
    }

    function preferencePayload(form) {
        var mode = form.elements.emailMode.value;
        var payload = { emailMode: EMAIL_MODES.indexOf(mode) >= 0 ? mode : 'DAILY' };
        PREFERENCE_FIELDS.forEach(function (field) { payload[field] = Boolean(form.elements[field].checked); });
        return payload;
    }

    function applyPreferences(form, values) {
        var mode = EMAIL_MODES.indexOf(values.emailMode) >= 0 ? values.emailMode : 'DAILY';
        var radios = form.querySelectorAll('[name="emailMode"]');
        Array.prototype.forEach.call(radios, function (radio) { radio.checked = radio.value === mode; });
        PREFERENCE_FIELDS.forEach(function (field) { form.elements[field].checked = values[field] !== false; });
    }

    function setSettingsState(state, message, type) {
        var form = document.getElementById('notificationSettingsForm');
        var loading = document.getElementById('settingsLoading');
        var error = document.getElementById('settingsError');
        var status = document.getElementById('settingsStatus');
        if (form) form.hidden = state === 'loading' || state === 'error';
        if (loading) loading.hidden = state !== 'loading';
        if (error) error.hidden = state !== 'error';
        if (status) {
            status.textContent = message || '';
            status.className = type ? 'settings-status--' + type : '';
        }
    }

    function loadPreferences() {
        var form = document.getElementById('notificationSettingsForm');
        setSettingsState('loading');
        return requestJson('/api/notification-preferences')
            .then(function (data) { applyPreferences(form, data || {}); setSettingsState('ready'); return data; })
            .catch(function (error) { setSettingsState('error', error.message, 'error'); return null; });
    }

    function savePreferences(event) {
        event.preventDefault();
        var form = event.currentTarget;
        var button = document.getElementById('saveNotificationSettings');
        button.disabled = true;
        button.textContent = 'Saving settings...';
        setSettingsState('ready', 'Saving your notification settings.');
        return requestJson('/api/notification-preferences', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(preferencePayload(form))
        }).then(function (data) {
            applyPreferences(form, data || {});
            setSettingsState('ready', 'Notification settings saved.', 'success');
            trackRetentionEvent('notification_preferences_saved', {
                type: 'notification_preferences_saved', category: 'preferences', source: 'notification_settings'
            });
        }).catch(function (error) {
            setSettingsState('ready', error.message, 'error');
        }).finally(function () {
            button.disabled = false;
            button.textContent = 'Save notification settings';
        });
    }

    function clearBellTimer() {
        if (bellTimer) root.clearTimeout(bellTimer);
        bellTimer = null;
    }

    function scheduleBell() {
        clearBellTimer();
        if (!bellStarted || document.visibilityState !== 'visible') return;
        var elapsed = Date.now() - lastUnreadRefreshAt;
        bellTimer = root.setTimeout(runBellRefresh, Math.max(0, 60000 - elapsed));
    }

    function runBellRefresh() {
        bellTimer = null;
        if (document.visibilityState !== 'visible') return;
        if (unreadRequest) {
            unreadRequest.finally(scheduleBell);
            return;
        }
        if (Date.now() - lastUnreadRefreshAt < 60000) {
            scheduleBell();
            return;
        }
        refreshUnreadCount().catch(function () {}).finally(scheduleBell);
    }

    function handleVisibilityChange() {
        clearBellTimer();
        if (document.visibilityState !== 'visible') return;
        if (unreadRequest) unreadRequest.finally(scheduleBell);
        else if (Date.now() - lastUnreadRefreshAt >= 60000) {
            refreshUnreadCount().catch(function () {}).finally(scheduleBell);
        } else scheduleBell();
    }

    function startBell() {
        var nav = document.getElementById('notificationNav');
        if (!nav) return;
        nav.hidden = false;
        if (!bellStarted) {
            bellStarted = true;
            refreshUnreadCount().catch(function () {}).finally(scheduleBell);
        } else scheduleBell();
        if (!visibilityBound) {
            visibilityBound = true;
            document.addEventListener('visibilitychange', handleVisibilityChange);
        }
    }

    function initCenter() {
        var list = document.getElementById('notificationList');
        if (!list) return;
        Array.prototype.forEach.call(document.querySelectorAll('[data-category]'), function (button) {
            button.addEventListener('click', function () {
                Array.prototype.forEach.call(document.querySelectorAll('[data-category]'), function (candidate) {
                    candidate.setAttribute('aria-pressed', candidate === button ? 'true' : 'false');
                });
                loadNotifications(button.dataset.category, 1).catch(function () {});
            });
        });
        document.getElementById('markAllRead').addEventListener('click', function () { markAllRead().catch(function () {}); });
        document.getElementById('retryNotifications').addEventListener('click', function () { loadNotifications(currentCategory, currentPage).catch(function () {}); });
        document.getElementById('loadMoreNotifications').addEventListener('click', function () { loadNotifications(currentCategory, currentPage + 1, true); });
        loadNotifications('all', 1).catch(function () {});
    }

    function initSettings() {
        var form = document.getElementById('notificationSettingsForm');
        if (!form) return;
        form.addEventListener('submit', savePreferences);
        document.getElementById('retrySettings').addEventListener('click', function () { loadPreferences().catch(function () {}); });
        loadPreferences().catch(function () {});
    }

    var api = {
        createNotificationRow: createNotificationRow,
        deleteNotification: deleteNotification,
        loadNotifications: loadNotifications,
        markRead: markRead,
        preferencePayload: preferencePayload,
        refreshUnreadCount: refreshUnreadCount,
        safeInternalTarget: safeInternalTarget,
        startBell: startBell
    };
    root.WhoseNotifications = api;
    root.addEventListener('whose:authenticated', startBell);
    if (root.WhoseAuthState && root.WhoseAuthState.authenticated === true) startBell();
    function initPages() {
        initCenter();
        initSettings();
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initPages);
    else initPages();
})(window);
