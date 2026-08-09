(function (root) {
    'use strict';

    var ALLOWED_CATEGORIES = ['all', 'expiry', 'security', 'domain-change', 'investment'];
    var PREFERENCE_FIELDS = ['domainExpiryEnabled', 'sslExpiryEnabled', 'domainStatusEnabled', 'dnsChangeEnabled', 'websiteAvailabilityEnabled'];
    var EMAIL_MODES = ['IMMEDIATE', 'DAILY', 'WEEKLY', 'IN_APP_ONLY'];
    var currentCategory = 'all';
    var currentPage = 1;
    var bellStarted = false;
    var visibilityBound = false;

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

    function inferRisk(item) {
        var signal = ((item && item.title) || '') + ' ' + ((item && item.content) || '');
        signal = signal.toLowerCase();
        if (/down|hold|expired|critical|urgent/.test(signal)) return { key: 'high', label: 'High risk' };
        if (/expir|dns|changed|certificate|status/.test(signal)) return { key: 'elevated', label: 'Elevated' };
        return { key: 'info', label: 'Info' };
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
        var actions = doc.createElement('div');
        var target = doc.createElement('a');
        var read = doc.createElement('button');
        var remove = doc.createElement('button');
        var riskValue = inferRisk(item);
        var targetPath = safeInternalTarget(item && item.targetPath);

        article.classList.add('signal-event');
        article.dataset.notificationId = String((item && item.id) || '');
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
        actions.classList.add('signal-event__actions');
        target.classList.add('signal-event__target');
        target.textContent = 'Open domain evidence';
        target.hidden = !targetPath;
        if (targetPath) target.setAttribute('href', targetPath);
        read.type = 'button';
        read.setAttribute('data-action', 'read');
        read.textContent = item && item.readAt ? 'Read' : 'Mark read';
        read.disabled = Boolean(item && item.readAt);
        remove.type = 'button';
        remove.setAttribute('data-action', 'delete');
        remove.textContent = 'Delete';

        read.addEventListener('click', function () { markRead(item.id, article, read); });
        remove.addEventListener('click', function () { deleteNotification(item.id, article, remove); });
        meta.append(risk, time);
        actions.append(target, read, remove);
        body.append(meta, title, content, actions);
        article.append(rail, body);
        article.parts = { title: title, content: content, target: target, risk: risk, time: time, read: read, remove: remove };
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

    function loadNotifications(category, page) {
        currentCategory = normalizeCategory(category);
        currentPage = Math.max(1, Number(page) || 1);
        setCenterState('loading', 'Loading the latest signals.');
        var list = document.getElementById('notificationList');
        var pagination = document.getElementById('notificationPagination');
        if (list) list.replaceChildren();
        if (pagination) pagination.hidden = true;
        return requestJson('/api/notifications?page=' + currentPage + '&category=' + encodeURIComponent(currentCategory))
            .then(function (data) {
                var items = data && Array.isArray(data.items) ? data.items : [];
                if (list) items.forEach(function (item) { list.appendChild(createNotificationRow(item)); });
                setCenterState(items.length ? 'ready' : 'empty', items.length
                    ? String(data.total || items.length) + ' signals in this channel.'
                    : 'No signals in this channel.');
                if (pagination) pagination.hidden = !data || currentPage * (data.size || 20) >= (data.total || 0);
                return data;
            })
            .catch(function (error) {
                setCenterState('error', error.message);
                throw error;
            });
    }

    function refreshUnreadCount() {
        return requestJson('/api/notifications/unread-count').then(function (data) {
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
    }

    function markRead(id, row, button) {
        if (button) button.disabled = true;
        return requestJson('/api/notifications/' + encodeURIComponent(id) + '/read', { method: 'PUT' })
            .then(function () {
                if (row) row.classList.add('is-read');
                if (button) button.textContent = 'Read';
                return refreshUnreadCount();
            })
            .catch(function (error) {
                if (button) button.disabled = false;
                setCenterState('ready', error.message);
                throw error;
            });
    }

    function deleteNotification(id, row, button) {
        if (button) button.disabled = true;
        return requestJson('/api/notifications/' + encodeURIComponent(id), { method: 'DELETE' })
            .then(function () {
                if (row && row.parentNode) row.parentNode.removeChild(row);
                return Promise.all([loadNotifications(currentCategory, currentPage), refreshUnreadCount()]);
            })
            .catch(function (error) {
                if (button) button.disabled = false;
                setCenterState('ready', error.message);
                throw error;
            });
    }

    function markAllRead() {
        var button = document.getElementById('markAllRead');
        if (button) button.disabled = true;
        return requestJson('/api/notifications/read-all', { method: 'PUT' })
            .then(function () { return Promise.all([loadNotifications(currentCategory, 1), refreshUnreadCount()]); })
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
            .catch(function (error) { setSettingsState('error', error.message, 'error'); throw error; });
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
        }).catch(function (error) {
            setSettingsState('ready', error.message, 'error');
        }).finally(function () {
            button.disabled = false;
            button.textContent = 'Save notification settings';
        });
    }

    function startBell() {
        var nav = document.getElementById('notificationNav');
        if (!nav) return;
        nav.hidden = false;
        refreshUnreadCount().catch(function () {});
        if (!bellStarted) {
            bellStarted = true;
            setInterval(function () {
                if (document.visibilityState === 'visible') refreshUnreadCount().catch(function () {});
            }, 60000);
        }
        if (!visibilityBound) {
            visibilityBound = true;
            document.addEventListener('visibilitychange', function () {
                if (document.visibilityState === 'visible') refreshUnreadCount().catch(function () {});
            });
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
        document.getElementById('loadMoreNotifications').addEventListener('click', function () { loadNotifications(currentCategory, currentPage + 1).catch(function () {}); });
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
        loadNotifications: loadNotifications,
        markRead: markRead,
        preferencePayload: preferencePayload,
        refreshUnreadCount: refreshUnreadCount,
        safeInternalTarget: safeInternalTarget,
        startBell: startBell
    };
    root.WhoseNotifications = api;
    root.addEventListener('whose:authenticated', startBell);
    function initPages() {
        initCenter();
        initSettings();
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initPages);
    else initPages();
})(window);
