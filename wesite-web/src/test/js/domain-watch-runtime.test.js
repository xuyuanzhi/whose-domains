const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const templatePath = path.resolve(__dirname, '../../main/resources/views/user/domain-watch.html');
const template = fs.readFileSync(templatePath, 'utf8');
const retentionAnalyticsPath = path.resolve(__dirname, '../../main/resources/static/js/retention-analytics.js');
const retentionAnalyticsScript = fs.readFileSync(retentionAnalyticsPath, 'utf8');
const domainWatchScript = Array.from(template.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g), (match) => match[1])
    .find((script) => script.includes("function checkLoginAndLoad()"));

assert.ok(domainWatchScript, 'domain-watch production script should be present in domain-watch.html');

class FakeElement {
    constructor(tagName = 'div') {
        this.tagName = tagName.toUpperCase();
        this.style = {};
        this.listeners = new Map();
        this.innerHTML = '';
        this.textContent = '';
        this.value = '';
        this.disabled = false;
        this.children = [];
        this.className = '';
        this.attributes = new Map();
    }

    addEventListener(type, listener) {
        if (!this.listeners.has(type)) this.listeners.set(type, []);
        this.listeners.get(type).push(listener);
    }

    append(...children) { children.forEach(child => this.appendChild(child)); }
    appendChild(child) { this.children.push(child); child.parentNode = this; return child; }
    replaceChildren(...children) { this.children = []; this.append(...children); }
    setAttribute(name, value) { this.attributes.set(name, String(value)); }
    getAttribute(name) { return this.attributes.get(name) || null; }
}

class FakeDocument {
    constructor() {
        this.elements = new Map();
        this.listeners = new Map();
    }

    register(id) {
        const element = new FakeElement();
        this.elements.set(id, element);
        return element;
    }

    getElementById(id) {
        return this.elements.get(id) || null;
    }

    addEventListener(type, listener) {
        if (!this.listeners.has(type)) this.listeners.set(type, []);
        this.listeners.get(type).push(listener);
    }

    createElement(tagName) {
        return new FakeElement(tagName);
    }
}

test('watchlist uses bound handlers and keeps a malicious watch email out of HTML', () => {
    assert.doesNotMatch(template, /\sonclick\s*=/i);
    assert.doesNotMatch(domainWatchScript, /row\.innerHTML\s*=/);
    const harness = createHarness(() => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ code: 0 }) }));
    const maliciousEmail = "x@example.com');alert(1);//";

    harness.context.renderList([{
        watch: {
            id: 'watch-1',
            domainName: 'example.com',
            notifyType: 3,
            notifyEmail: maliciousEmail,
            expiryDateText: '2027-08-09'
        },
        latestEventRisk: 'LOW',
        latestEventSummary: 'No monitoring events yet'
    }]);

    const row = harness.context.document.getElementById('watchList').children[0];
    assert.equal(row.parts.settings.getAttribute('onclick'), null);
    row.parts.settings.listeners.get('click')[0]();
    assert.equal(harness.editNotifyEmail.value, maliciousEmail);
});

function createHarness(fetch, retentionAnalytics, gtag) {
    const document = new FakeDocument();
    const loginRequired = document.register('loginRequired');
    const listLoader = document.register('listLoader');
    const watchlistUI = document.register('watchlistUI');
    const editModal = document.register('editModal');
    document.register('statTotal');
    document.register('statExpiring');
    document.register('statMonth');
    document.register('statAfter30');
    document.register('watchList');
    document.register('emptyState');
    const addDomainInput = document.register('addDomainInput');
    const addNotifyType = document.register('addNotifyType');
    const addNotifyEmail = document.register('addNotifyEmail');
    const addWatchBtn = document.register('addWatchBtn');
    document.register('addMsg');
    const editWatchId = document.register('editWatchId');
    document.register('editDomainName');
    const editNotifyType = document.register('editNotifyType');
    const editNotifyEmail = document.register('editNotifyEmail');
    const alerts = [];

    const window = { WhoseRetentionAnalytics: retentionAnalytics };
    if (typeof gtag === 'function') window.gtag = gtag;
    const context = vm.createContext({
        document,
        fetch,
        console,
        Date,
        Math,
        setTimeout,
        window,
        confirm: () => true,
        alert: (message) => alerts.push(message)
    });
    if (typeof gtag === 'function') vm.runInContext(retentionAnalyticsScript, context, { filename: retentionAnalyticsPath });
    vm.runInContext(domainWatchScript, context, { filename: templatePath });
    return {
        context,
        loginRequired,
        listLoader,
        watchlistUI,
        editModal,
        addDomainInput,
        addNotifyType,
        addNotifyEmail,
        addWatchBtn,
        editWatchId,
        editNotifyType,
        editNotifyEmail,
        alerts
    };
}

async function flushPromises() {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
}

function assertSignedOut(harness) {
    assert.equal(harness.loginRequired.style.display, 'block');
    assert.equal(harness.listLoader.style.display, 'none');
    assert.equal(harness.watchlistUI.style.display, 'none');
    assert.equal(harness.context.isLoggedIn, false);
}

test('401 response hides loader and authenticated UI before showing sign-in card', async () => {
    const harness = createHarness(() => Promise.resolve({ status: 401 }));

    harness.context.isLoggedIn = true;
    harness.context.checkLoginAndLoad();
    await flushPromises();

    assertSignedOut(harness);
});

test('business 401 code shows the same signed-out state', async () => {
    const harness = createHarness(() => Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ code: 401 })
    }));

    harness.context.isLoggedIn = true;
    harness.context.checkLoginAndLoad();
    await flushPromises();

    assertSignedOut(harness);
});

test('HTTP 500 list response never enters the authenticated Watchlist UI', async () => {
    const harness = createHarness(() => Promise.resolve({
        ok: false,
        status: 500,
        json: () => Promise.resolve({ code: 0, data: [] })
    }));

    harness.context.isLoggedIn = true;
    harness.watchlistUI.style.display = 'block';
    harness.context.checkLoginAndLoad();
    await flushPromises();

    assertSignedOut(harness);
});

test('business 500 list response never enters the authenticated Watchlist UI', async () => {
    const harness = createHarness(() => Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ code: 500, data: [] })
    }));

    harness.context.isLoggedIn = true;
    harness.watchlistUI.style.display = 'block';
    harness.context.checkLoginAndLoad();
    await flushPromises();

    assertSignedOut(harness);
});

test('network failure shows the same signed-out state', async () => {
    const harness = createHarness(() => Promise.reject(new Error('network unavailable')));

    harness.context.isLoggedIn = true;
    harness.context.checkLoginAndLoad();
    await flushPromises();

    assertSignedOut(harness);
});

test('successful list response hides signed-out state and shows Watchlist UI', async () => {
    const harness = createHarness(() => Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ code: 0, data: [] })
    }));

    harness.loginRequired.style.display = 'block';
    harness.context.checkLoginAndLoad();
    await flushPromises();

    assert.equal(harness.loginRequired.style.display, 'none');
    assert.equal(harness.listLoader.style.display, 'none');
    assert.equal(harness.watchlistUI.style.display, 'block');
    assert.equal(harness.context.isLoggedIn, true);
});

test('a throwing gtag cannot switch a successful watchlist load to signed-out UI', async () => {
    const harness = createHarness(() => Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ code: 0, data: [] })
    }), undefined, () => { throw new Error('analytics outage'); });

    harness.loginRequired.style.display = 'block';
    harness.context.checkLoginAndLoad();
    await flushPromises();

    assert.equal(harness.loginRequired.style.display, 'none');
    assert.equal(harness.watchlistUI.style.display, 'block');
    assert.equal(harness.context.isLoggedIn, true);
});

const expiredOperationCases = [
    {
        name: 'add',
        prepare(harness) {
            harness.addDomainInput.value = 'example.com';
            harness.addNotifyType.value = '3';
            harness.addNotifyEmail.value = '';
        },
        invoke(harness) { harness.context.addWatch(); }
    },
    {
        name: 'remove',
        invoke(harness) { harness.context.removeWatch('watch-1', new FakeElement()); }
    },
    {
        name: 'save',
        prepare(harness) {
            harness.editWatchId.value = 'watch-1';
            harness.editNotifyType.value = '3';
            harness.editNotifyEmail.value = '';
            harness.editModal.style.display = 'flex';
        },
        invoke(harness) { harness.context.saveEdit(); }
    },
    {
        name: 'reload',
        invoke(harness) { harness.context.reloadList(); }
    }
];

for (const operation of expiredOperationCases) {
    test(`${operation.name} switches to signed-out state when its HTTP session has expired`, async () => {
        const harness = createHarness(() => Promise.resolve({
            ok: false,
            status: 401,
            json: () => Promise.resolve({ code: 500 })
        }));
        harness.context.isLoggedIn = true;
        harness.loginRequired.style.display = 'none';
        harness.watchlistUI.style.display = 'block';
        if (operation.prepare) operation.prepare(harness);

        operation.invoke(harness);
        await flushPromises();

        assertSignedOut(harness);
        assert.equal(harness.editModal.style.display, 'none');
    });
}

test('reload switches to signed-out state when the API returns a business auth code', async () => {
    const harness = createHarness(() => Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ code: -401 })
    }));
    harness.context.isLoggedIn = true;
    harness.loginRequired.style.display = 'none';
    harness.watchlistUI.style.display = 'block';

    harness.context.reloadList();
    await flushPromises();

    assertSignedOut(harness);
});
