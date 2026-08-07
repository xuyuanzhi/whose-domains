const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const templatePath = path.resolve(__dirname, '../../main/resources/views/user/domain-watch.html');
const template = fs.readFileSync(templatePath, 'utf8');
const domainWatchScript = Array.from(template.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g), (match) => match[1])
    .find((script) => script.includes("function checkLoginAndLoad()"));

assert.ok(domainWatchScript, 'domain-watch production script should be present in domain-watch.html');

class FakeElement {
    constructor() {
        this.style = {};
        this.listeners = new Map();
        this.innerHTML = '';
        this.textContent = '';
        this.value = '';
    }

    addEventListener(type, listener) {
        if (!this.listeners.has(type)) this.listeners.set(type, []);
        this.listeners.get(type).push(listener);
    }

    appendChild() {}
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

    createElement() {
        return new FakeElement();
    }
}

function createHarness(fetch) {
    const document = new FakeDocument();
    const loginRequired = document.register('loginRequired');
    const listLoader = document.register('listLoader');
    const watchlistUI = document.register('watchlistUI');
    document.register('editModal');
    document.register('statTotal');
    document.register('statExpiring');
    document.register('statMonth');
    document.register('statSafe');
    document.register('watchList');
    document.register('emptyState');

    const context = vm.createContext({
        document,
        fetch,
        console,
        Date,
        Math,
        setTimeout,
        confirm: () => true,
        alert: () => {}
    });
    vm.runInContext(domainWatchScript, context, { filename: templatePath });
    return { context, loginRequired, listLoader, watchlistUI };
}

async function flushPromises() {
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
        status: 200,
        json: () => Promise.resolve({ code: 401 })
    }));

    harness.context.isLoggedIn = true;
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
