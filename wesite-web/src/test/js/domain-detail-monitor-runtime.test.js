const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const templatePath = path.resolve(__dirname, '../../main/resources/views/domain_detail.html');
const template = fs.readFileSync(templatePath, 'utf8');
const monitorScript = Array.from(template.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g), (match) => match[1])
    .find((script) => script.includes("var button = document.getElementById('monitorDomainBtn')"));

assert.ok(monitorScript, 'domain monitor production script should be present in domain_detail.html');

class FakeElement {
    constructor(attributes = {}) {
        this.attributes = new Map(Object.entries(attributes));
        this.style = {};
        this.listeners = new Map();
        this._innerHTML = '';
        this._textContent = '';
        this.value = '';
        this.disabled = false;
    }

    get innerHTML() {
        return this._innerHTML;
    }

    set innerHTML(value) {
        this._innerHTML = value;
        this._textContent = value.replace(/<[^>]*>/g, '');
    }

    get textContent() {
        return this._textContent;
    }

    set textContent(value) {
        this._textContent = value;
        this._innerHTML = value;
    }

    addEventListener(type, listener) {
        if (!this.listeners.has(type)) this.listeners.set(type, []);
        this.listeners.get(type).push(listener);
    }

    dispatch(type, event = {}) {
        for (const listener of this.listeners.get(type) || []) listener(event);
    }

    getAttribute(name) {
        return this.attributes.get(name) || null;
    }

    setAttribute(name, value) {
        this.attributes.set(name, value);
    }
}

class FakeDocument {
    constructor() {
        this.elements = new Map();
        this.body = { appendChild() {} };
    }

    register(id, attributes) {
        const element = new FakeElement(attributes);
        this.elements.set(id, element);
        return element;
    }

    getElementById(id) {
        return this.elements.get(id) || null;
    }
}

function response(data) {
    return Promise.resolve({ json: () => Promise.resolve(data) });
}

function createHarness(emailLoginResponse) {
    const document = new FakeDocument();
    const monitorButton = document.register('monitorDomainBtn', { 'data-domain': 'example.com' });
    const modal = document.register('monitorModal');
    const email = document.register('monitorEmail');
    const sendLinkButton = document.register('sendMonitorLink');
    const emailMessage = document.register('monitorEmailMessage');
    document.register('monitorDomainName');
    document.register('closeMonitorModal');
    const localStorage = new Map();
    const context = vm.createContext({
        document,
        fetch(url) {
            if (url.startsWith('/api/domain-watch/check/')) return response({ code: 0, data: false });
            if (url === '/user/email-login') return emailLoginResponse();
            if (url === '/user/session') return response({ code: 1 });
            throw new Error(`Unexpected request: ${url}`);
        },
        localStorage: {
            setItem(key, value) { localStorage.set(key, value); }
        },
        window: {
            setInterval() { return 1; },
            clearInterval() {}
        },
        JSON,
        encodeURIComponent
    });

    vm.runInContext(monitorScript, context, { filename: templatePath });
    return { monitorButton, modal, email, sendLinkButton, emailMessage };
}

async function flushPromises() {
    for (let index = 0; index < 6; index++) await Promise.resolve();
}

test('monitor email validation writes to the dedicated email status without an old shared message node', () => {
    const harness = createHarness(() => response({ code: 0 }));

    assert.doesNotThrow(() => harness.sendLinkButton.dispatch('click'));
    assert.equal(harness.emailMessage.textContent, 'Enter your email address.');
    assert.equal(harness.emailMessage.style.display, 'block');
    assert.equal(harness.sendLinkButton.disabled, false);
});

test('monitor email success enters cooldown and announces its result without throwing', async () => {
    const harness = createHarness(() => response({ code: 0, msg: 'Check your inbox.' }));
    harness.email.value = 'owner@example.com';

    assert.doesNotThrow(() => harness.sendLinkButton.dispatch('click'));
    assert.equal(harness.sendLinkButton.disabled, true);
    assert.match(harness.sendLinkButton.innerHTML, /Sending link/);
    await flushPromises();

    assert.equal(harness.emailMessage.textContent, 'Check your inbox.');
    assert.equal(harness.emailMessage.style.color, '#4caf50');
    assert.equal(harness.emailMessage.style.display, 'block');
    assert.equal(harness.sendLinkButton.textContent, 'Resend in 2:00');
});

test('monitor email failures announce an error and restore the send button without throwing', async () => {
    const harness = createHarness(() => response({ code: 1, msg: 'Delivery failed.' }));
    harness.email.value = 'owner@example.com';

    assert.doesNotThrow(() => harness.sendLinkButton.dispatch('click'));
    await flushPromises();

    assert.equal(harness.emailMessage.textContent, 'Delivery failed.');
    assert.equal(harness.emailMessage.style.color, '#ff5252');
    assert.equal(harness.emailMessage.style.display, 'block');
    assert.equal(harness.sendLinkButton.disabled, false);
    assert.equal(harness.sendLinkButton.textContent, 'Email me a sign-in link');
});

test('monitor email network failures announce an error and restore the send button without throwing', async () => {
    const harness = createHarness(() => Promise.reject(new Error('network unavailable')));
    harness.email.value = 'owner@example.com';

    assert.doesNotThrow(() => harness.sendLinkButton.dispatch('click'));
    await flushPromises();

    assert.equal(harness.emailMessage.textContent, 'Network error.');
    assert.equal(harness.emailMessage.style.color, '#ff5252');
    assert.equal(harness.emailMessage.style.display, 'block');
    assert.equal(harness.sendLinkButton.disabled, false);
    assert.equal(harness.sendLinkButton.textContent, 'Email me a sign-in link');
});
