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
    }
    append(...children) { this.children.push(...children); children.forEach(child => { child.parentNode = this; }); }
    appendChild(child) { this.append(child); return child; }
    replaceChildren(...children) { this.children = []; this.append(...children); }
    addEventListener(type, listener) { this.listeners.set(type, listener); }
    setAttribute(name, value) { this.attributes.set(name, String(value)); }
    getAttribute(name) { return this.attributes.get(name) || null; }
    removeAttribute(name) { this.attributes.delete(name); }
    focus() {}
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

function ok(data) {
    return Promise.resolve({ ok: true, json: () => Promise.resolve({ code: 0, data }) });
}

function createHarness(fetchImpl = () => ok(null), readyState = 'complete') {
    const document = new FakeDocument();
    document.readyState = readyState;
    const requests = [];
    const intervals = [];
    const windowListeners = new Map();
    const context = vm.createContext({
        document,
        console,
        URL,
        URLSearchParams,
        location: { origin: 'https://whose.domains', pathname: '/user/notifications' },
        fetch: (url, options = {}) => { requests.push({ url, options }); return fetchImpl(url, options); },
        setInterval: (callback, delay) => { intervals.push({ callback, delay }); return intervals.length; },
        clearInterval() {},
        setTimeout,
        window: {
            addEventListener(type, listener) { windowListeners.set(type, listener); },
            dispatchEvent() {},
            location: { origin: 'https://whose.domains', pathname: '/user/notifications' }
        }
    });
    context.globalThis = context;
    vm.runInContext(script, context, { filename: scriptPath });
    return { api: context.window.WhoseNotifications, context, document, requests, intervals, windowListeners };
}

async function flush() {
    for (let index = 0; index < 8; index += 1) await Promise.resolve();
}

test('notification rows keep API text inert and reject external targets', () => {
    const harness = createHarness();
    const row = harness.api.createNotificationRow({
        id: 'notice-1',
        title: '<img src=x onerror=alert(1)>',
        content: '<script>steal()</script>',
        targetPath: 'https://evil.example/collect',
        readAt: null,
        createTime: '2026-08-09T12:30:00Z'
    }, harness.document);

    assert.equal(row.parts.title.textContent, '<img src=x onerror=alert(1)>');
    assert.equal(row.parts.content.textContent, '<script>steal()</script>');
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

test('bell refreshes immediately, polls at sixty seconds, and refreshes when visibility returns', async () => {
    const harness = createHarness(() => ok({ unreadCount: 3 }));
    harness.document.register('notificationNav');
    harness.document.register('notificationCount');

    harness.api.startBell();
    await flush();
    assert.equal(harness.requests.length, 1);
    assert.equal(harness.intervals.length, 1);
    assert.equal(harness.intervals[0].delay, 60000);

    harness.document.visibilityState = 'visible';
    harness.document.listeners.get('visibilitychange')();
    await flush();
    assert.equal(harness.requests.length, 2);
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
