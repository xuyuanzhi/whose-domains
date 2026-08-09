const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const scriptPath = path.resolve(__dirname, '../../main/resources/static/js/notifications.js');
const script = fs.readFileSync(scriptPath, 'utf8');

class FakeClassList {
    constructor() { this.values = new Set(); }
    add(...values) { values.forEach(value => this.values.add(value)); }
    remove(...values) { values.forEach(value => this.values.delete(value)); }
    toggle(value, force) {
        const next = force === undefined ? !this.values.has(value) : force;
        if (next) this.values.add(value); else this.values.delete(value);
        return next;
    }
    contains(value) { return this.values.has(value); }
}

class FakeElement {
    constructor(tagName = 'div') {
        this.tagName = tagName.toUpperCase();
        this.children = [];
        this.listeners = new Map();
        this.attributes = new Map();
        this.classList = new FakeClassList();
        this.dataset = {};
        this.style = {};
        this.hidden = false;
        this.disabled = false;
        this.textContent = '';
        this.value = '';
        this.checked = false;
        this.focused = false;
    }
    append(...children) { this.children.push(...children); children.forEach(child => { child.parentNode = this; }); }
    appendChild(child) { this.append(child); return child; }
    replaceChildren(...children) { this.children = []; this.append(...children); }
    addEventListener(type, listener) { this.listeners.set(type, listener); }
    setAttribute(name, value) { this.attributes.set(name, String(value)); }
    getAttribute(name) { return this.attributes.get(name) || null; }
    removeAttribute(name) { this.attributes.delete(name); }
    removeChild(child) { this.children = this.children.filter(candidate => candidate !== child); child.parentNode = null; }
    get nextElementSibling() { if (!this.parentNode) return null; const index = this.parentNode.children.indexOf(this); return this.parentNode.children[index + 1] || null; }
    get previousElementSibling() { if (!this.parentNode) return null; const index = this.parentNode.children.indexOf(this); return index > 0 ? this.parentNode.children[index - 1] : null; }
    focus() { this.focused = true; }
}

class FakeDocument {
    constructor() {
        this.elements = new Map();
        this.listeners = new Map();
        this.visibilityState = 'visible';
        this.readyState = 'complete';
    }
    register(id, tagName = 'div') { const element = new FakeElement(tagName); this.elements.set(id, element); return element; }
    getElementById(id) { return this.elements.get(id) || null; }
    createElement(tagName) { return new FakeElement(tagName); }
    addEventListener(type, listener) { this.listeners.set(type, listener); }
    querySelectorAll() { return []; }
}

class FakeAbortController {
    constructor() { this.signal = { aborted: false }; }
    abort() { this.signal.aborted = true; }
}

function ok(data) {
    return Promise.resolve({ ok: true, json: () => Promise.resolve({ code: 0, data }) });
}

function createHarness(fetchImpl = () => ok(null), readyState = 'complete') {
    const document = new FakeDocument();
    document.readyState = readyState;
    const requests = [];
    const analyticsCalls = [];
    const timers = [];
    let now = 0;
    const windowListeners = new Map();
    const setFakeTimeout = (callback, delay) => { const timer = { callback, due: now + delay, cleared: false }; timers.push(timer); return timer; };
    const clearFakeTimeout = timer => { if (timer) timer.cleared = true; };
    const context = vm.createContext({
        document,
        console,
        URL,
        URLSearchParams,
        location: { origin: 'https://whose.domains', pathname: '/user/notifications' },
        fetch: (url, options = {}) => { requests.push({ url, options }); return fetchImpl(url, options); },
        Date: class extends Date { static now() { return now; } },
        AbortController: FakeAbortController,
        setTimeout: setFakeTimeout,
        clearTimeout: clearFakeTimeout,
        setTimeout,
        window: {
            addEventListener(type, listener) { windowListeners.set(type, listener); },
            dispatchEvent() {},
            setTimeout: setFakeTimeout,
            clearTimeout: clearFakeTimeout,
            location: { origin: 'https://whose.domains', pathname: '/user/notifications' },
            WhoseRetentionAnalytics: {
                track(eventName, parameters) {
                    analyticsCalls.push({ eventName, parameters: JSON.parse(JSON.stringify(parameters)) });
                }
            }
        }
    });
    context.globalThis = context;
    vm.runInContext(script, context, { filename: scriptPath });
    return {
        api: context.window.WhoseNotifications, context, document, requests, timers, windowListeners, analyticsCalls,
        setNow(value) { now = value; },
        runDueTimers() {
            timers.filter(timer => !timer.cleared && timer.due <= now).forEach(timer => { timer.cleared = true; timer.callback(); });
        }
    };
}

async function flush() {
    for (let index = 0; index < 20; index += 1) await Promise.resolve();
    await new Promise(resolve => setImmediate(resolve));
}

test('notification rows keep API text inert, use canonical metadata, and reject external targets', () => {
    const harness = createHarness();
    const row = harness.api.createNotificationRow({
        id: 'notice-1',
        title: '<img src=x onerror=alert(1)>',
        content: '<script>steal()</script>',
        targetPath: 'https://evil.example/collect',
        risk: 'LOW',
        eventType: 'WEBSITE_DOWN',
        domain: '<img src=x onerror=alert(2)>',
        source: '<script>source()</script>',
        readAt: null,
        createTime: '2026-08-09T12:30:00Z'
    }, harness.document);

    assert.equal(row.parts.title.textContent, '<img src=x onerror=alert(1)>');
    assert.equal(row.parts.content.textContent, '<script>steal()</script>');
    assert.equal(row.parts.risk.textContent, 'Low');
    assert.equal(row.parts.risk.getAttribute('data-risk'), 'low');
    assert.equal(row.parts.domain.textContent, '<img src=x onerror=alert(2)>');
    assert.equal(row.parts.source.textContent, '<script>source()</script>');
    assert.equal(row.parts.target.hidden, true);
    assert.equal(row.parts.target.getAttribute('href'), null);
});

test('notification rows allow only same-origin internal paths', () => {
    const harness = createHarness();
    const safe = harness.api.createNotificationRow({ id: '1', title: 'Safe', content: 'Safe', targetPath: '/domain/example.com' }, harness.document);
    const protocolRelative = harness.api.createNotificationRow({ id: '2', title: 'No', content: 'No', targetPath: '//evil.example' }, harness.document);
    const backslash = harness.api.createNotificationRow({ id: '3', title: 'No', content: 'No', targetPath: '/\\evil.example' }, harness.document);

    assert.equal(safe.parts.target.getAttribute('href'), '/domain/example.com');
    assert.equal(protocolRelative.parts.target.hidden, true);
    assert.equal(backslash.parts.target.hidden, true);
});

test('legacy unknown risk is rendered as a quiet unrated signal', () => {
    const harness = createHarness();
    const row = harness.api.createNotificationRow({
        id: 'legacy',
        title: 'Legacy event',
        content: 'Risk was not recorded',
        risk: 'UNKNOWN',
        source: 'UNKNOWN'
    }, harness.document);

    assert.equal(row.parts.risk.textContent, 'Unrated');
    assert.equal(row.parts.risk.getAttribute('data-risk'), 'unknown');
    assert.equal(row.parts.source.textContent, 'UNKNOWN');
});

test('category loading normalizes unrecognized filters to all', async () => {
    const harness = createHarness(() => ok({ items: [], total: 0, page: 1, size: 20 }));
    harness.document.register('notificationList');
    harness.document.register('notificationLoading');
    harness.document.register('notificationError');
    harness.document.register('notificationEmpty');
    harness.document.register('notificationPagination');
    harness.document.register('notificationStatus');

    await harness.api.loadNotifications('admin-only', 1);

    assert.equal(harness.requests[0].url, '/api/notifications?page=1&category=all');
});

test('notification loading retains the watched-domain filter from the link', async () => {
    const harness = createHarness(() => ok({ items: [], total: 0, page: 1, size: 20 }));
    harness.context.window.location.search = '?domain=example.com';
    harness.document.register('notificationList');
    harness.document.register('notificationLoading');
    harness.document.register('notificationError');
    harness.document.register('notificationEmpty');
    harness.document.register('notificationPagination');
    harness.document.register('notificationStatus');

    await harness.api.loadNotifications('all', 1);

    assert.equal(harness.requests[0].url, '/api/notifications?page=1&category=all&domain=example.com');
});

test('marking a row read refreshes and announces the unread count', async () => {
    const responses = [ok(null), ok({ unreadCount: 2 })];
    const harness = createHarness(() => responses.shift());
    const count = harness.document.register('notificationCount');
    const row = new FakeElement('article');

    await harness.api.markRead('notice-4', row);

    assert.equal(harness.requests[0].url, '/api/notifications/notice-4/read');
    assert.equal(harness.requests[0].options.method, 'PUT');
    assert.equal(count.textContent, '2 unread notifications');
    assert.equal(count.getAttribute('data-count'), '2');
    assert.equal(row.classList.contains('is-read'), true);
});

test('notification actions emit only after their API succeeds and never include notification identity or text', async () => {
    const successResponses = [ok(null), ok({ unreadCount: 0 })];
    const successful = createHarness(() => successResponses.shift());
    successful.document.register('notificationCount');
    const row = successful.api.createNotificationRow({
        id: 'notification-123',
        title: 'Private notification text',
        content: 'Domain private-example.com changed',
        domain: 'private-example.com',
        risk: 'CRITICAL'
    }, successful.document);

    row.parts.read.listeners.get('click')();
    await flush();

    assert.deepEqual(successful.analyticsCalls, [{
        eventName: 'notification_action_clicked',
        parameters: { type: 'mark_read', category: 'all', risk: 'CRITICAL', source: 'notification_center' }
    }]);

    const failed = createHarness(() => Promise.reject(new Error('offline')));
    const failedRow = failed.api.createNotificationRow({
        id: 'notification-456', title: 'Do not track', content: 'Do not track', risk: 'HIGH'
    }, failed.document);
    failedRow.parts.read.listeners.get('click')();
    await flush();

    assert.deepEqual(failed.analyticsCalls, []);
});

test('settings payload drops fields outside the preference API allowlist', () => {
    const form = {
        elements: {
            emailMode: { value: 'WEEKLY' },
            domainExpiryEnabled: { checked: true },
            sslExpiryEnabled: { checked: false },
            domainStatusEnabled: { checked: true },
            dnsChangeEnabled: { checked: false },
            websiteAvailabilityEnabled: { checked: true },
            adminOverride: { checked: true },
            emailAddress: { value: 'attacker@example.com' }
        }
    };
    const payload = createHarness().api.preferencePayload(form);

    assert.deepEqual(JSON.parse(JSON.stringify(payload)), {
        emailMode: 'WEEKLY',
        domainExpiryEnabled: true,
        sslExpiryEnabled: false,
        domainStatusEnabled: true,
        dnsChangeEnabled: false,
        websiteAvailabilityEnabled: true
    });
});

test('visibility refresh resets polling so 119.9 seconds is skipped and 120 seconds refreshes', async () => {
    const harness = createHarness(() => ok({ unreadCount: 3 }));
    harness.document.register('notificationNav');
    harness.document.register('notificationCount');

    harness.api.startBell();
    await flush();
    assert.equal(harness.requests.length, 1);
    assert.deepEqual(harness.timers.filter(timer => !timer.cleared).map(timer => timer.due), [60000]);

    harness.setNow(60000);
    harness.document.visibilityState = 'visible';
    harness.document.listeners.get('visibilitychange')();
    await flush();
    assert.equal(harness.requests.length, 2);
    assert.deepEqual(harness.timers.filter(timer => !timer.cleared).map(timer => timer.due), [120000]);

    harness.setNow(119900);
    harness.runDueTimers();
    await flush();
    assert.equal(harness.requests.length, 2);

    harness.setNow(120000);
    harness.runDueTimers();
    await flush();
    assert.equal(harness.requests.length, 3);
});

test('concurrent unread refresh requests share one in-flight fetch', async () => {
    let resolveResponse;
    const response = new Promise(resolve => { resolveResponse = resolve; });
    const harness = createHarness(() => response);
    harness.document.register('notificationCount');

    const first = harness.api.refreshUnreadCount();
    const second = harness.api.refreshUnreadCount();
    assert.equal(harness.requests.length, 1);
    resolveResponse({ ok: true, json: () => Promise.resolve({ code: 0, data: { unreadCount: 4 } }) });
    await Promise.all([first, second]);
    assert.equal(harness.requests.length, 1);
});

test('loading an earlier page appends rows instead of replacing the current log', async () => {
    const responses = [
        ok({ items: [{ id: 'new', title: 'Newest', content: 'A', risk: 'LOW' }], total: 2, page: 1, size: 1 }),
        ok({ items: [{ id: 'old', title: 'Earlier', content: 'B', risk: 'MEDIUM' }], total: 2, page: 2, size: 1 })
    ];
    const harness = createHarness(() => responses.shift());
    const list = harness.document.register('notificationList');
    harness.document.register('notificationLoading');
    harness.document.register('notificationError');
    harness.document.register('notificationEmpty');
    harness.document.register('notificationPagination');
    harness.document.register('notificationStatus');

    await harness.api.loadNotifications('all', 1);
    await harness.api.loadNotifications('all', 2, true);

    assert.equal(list.children.length, 2);
    assert.equal(list.children[0].parts.title.textContent, 'Newest');
    assert.equal(list.children[1].parts.title.textContent, 'Earlier');
});

test('a stale filter response cannot overwrite the newest category', async () => {
    const pending = [];
    const harness = createHarness(() => new Promise(resolve => pending.push(resolve)));
    const list = harness.document.register('notificationList');
    harness.document.register('notificationLoading');
    harness.document.register('notificationError');
    harness.document.register('notificationEmpty');
    harness.document.register('notificationPagination');
    harness.document.register('notificationStatus');

    const all = harness.api.loadNotifications('all', 1);
    const expiry = harness.api.loadNotifications('expiry', 1);
    pending[1]({ ok: true, json: () => Promise.resolve({ code: 0, data: { items: [{ id: 'expiry', title: 'Expiry only', content: '', risk: 'HIGH' }], total: 1, page: 1, size: 20 } }) });
    await expiry;
    pending[0]({ ok: true, json: () => Promise.resolve({ code: 0, data: { items: [{ id: 'stale', title: 'Stale all', content: '', risk: 'LOW' }], total: 1, page: 1, size: 20 } }) });
    await all;

    assert.equal(list.children.length, 1);
    assert.equal(list.children[0].parts.title.textContent, 'Expiry only');
});

test('deleting a notification restores focus to the next event without reloading the list', async () => {
    const harness = createHarness(() => ok(null));
    const list = harness.document.register('notificationList');
    harness.document.register('notificationLoading');
    harness.document.register('notificationError');
    harness.document.register('notificationEmpty');
    harness.document.register('notificationPagination');
    harness.document.register('notificationStatus');
    harness.document.register('notificationCount');
    const first = harness.api.createNotificationRow({ id: 'first', title: 'First', content: '', risk: 'LOW' }, harness.document);
    const second = harness.api.createNotificationRow({ id: 'second', title: 'Second', content: '', risk: 'LOW' }, harness.document);
    list.append(first, second);

    await harness.api.deleteNotification('first', first, first.parts.remove);

    assert.equal(list.children.length, 1);
    assert.equal(list.children[0], second);
    assert.equal(second.focused, true);
    assert.equal(harness.requests.some(request => request.url.startsWith('/api/notifications?page=')), false);
});

test('notification page initializes after header script runs before the main document is parsed', async () => {
    const harness = createHarness(() => ok({ items: [], total: 0, page: 1, size: 20 }), 'loading');
    harness.document.register('notificationList');
    harness.document.register('notificationLoading');
    harness.document.register('notificationError');
    harness.document.register('notificationEmpty');
    harness.document.register('notificationPagination');
    harness.document.register('notificationStatus');
    harness.document.register('markAllRead', 'button');
    harness.document.register('retryNotifications', 'button');
    harness.document.register('loadMoreNotifications', 'button');

    assert.equal(harness.requests.length, 0);
    harness.document.listeners.get('DOMContentLoaded')();
    await flush();

    assert.equal(harness.requests[0].url, '/api/notifications?page=1&category=all');
});
