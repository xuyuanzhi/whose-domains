const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const templatePath = path.resolve(__dirname, '../../main/resources/views/template.html');
const template = fs.readFileSync(templatePath, 'utf8');
const authScript = Array.from(template.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g), (match) => match[1])
    .find((script) => script.includes("var authModal=document.getElementById('authModal')"));

assert.ok(authScript, 'auth modal production script should be present in template.html');

class FakeClassList {
    constructor() {
        this.values = new Set();
    }

    add(value) {
        this.values.add(value);
    }

    remove(value) {
        this.values.delete(value);
    }

    contains(value) {
        return this.values.has(value);
    }

    toggle(value, force) {
        const enabled = force === undefined ? !this.contains(value) : force;
        if (enabled) this.add(value);
        else this.remove(value);
        return enabled;
    }
}

class FakeElement {
    constructor(document, id, options = {}) {
        this.ownerDocument = document;
        this.id = id;
        this.style = {};
        this.attributes = {};
        this.classList = new FakeClassList();
        this.listeners = new Map();
        this.capturedPointers = new Set();
        this.offsetWidth = options.width || 0;
        this.offsetHeight = options.height || 0;
        this.offsetParent = options.visible === false ? null : {};
        this.defaultLeft = options.left || 0;
        this.defaultTop = options.top || 0;
        this.interactive = options.interactive || false;
        this.tagName = (options.tagName || 'div').toLowerCase();
        this.value = options.value || '';
        this.disabled = false;
        this._textContent = '';
        this._innerHTML = '';
        this.onTextContent = null;
        this.queryResult = null;
        this.queryResults = [];
        this.descendants = new Set();
    }

    addEventListener(type, listener) {
        if (!this.listeners.has(type)) this.listeners.set(type, []);
        this.listeners.get(type).push(listener);
    }

    dispatch(type, event) {
        for (const listener of this.listeners.get(type) || []) listener.call(this, event);
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
    }

    getAttribute(name) {
        return this.attributes[name];
    }

    querySelector(selector) {
        return selector === '[role="dialog"]' ? this.queryResult : null;
    }

    querySelectorAll(selector) {
        return selector === 'a[href],button:not([disabled]),input:not([disabled]),[tabindex]:not([tabindex="-1"])'
            ? this.queryResults
            : [];
    }

    contains(element) {
        return element === this || this.descendants.has(element);
    }

    closest(selector) {
        const tags = selector.split(',').map((tag) => tag.trim().toLowerCase());
        return this.interactive && tags.includes(this.tagName) ? this : null;
    }

    focus() {
        this.ownerDocument.activeElement = this;
    }

    getBoundingClientRect() {
        const left = this.style.left ? Number.parseFloat(this.style.left) : this.defaultLeft;
        const top = this.style.top ? Number.parseFloat(this.style.top) : this.defaultTop;
        return {
            left,
            top,
            width: this.offsetWidth,
            height: this.offsetHeight,
            right: left + this.offsetWidth,
            bottom: top + this.offsetHeight
        };
    }

    setPointerCapture(pointerId) {
        this.capturedPointers.add(pointerId);
    }

    hasPointerCapture(pointerId) {
        return this.capturedPointers.has(pointerId);
    }

    releasePointerCapture(pointerId) {
        this.capturedPointers.delete(pointerId);
    }

    set textContent(value) {
        this._textContent = String(value);
        if (this.onTextContent) this.onTextContent(this._textContent);
    }

    get textContent() {
        return this._textContent;
    }

    set innerHTML(value) {
        this._innerHTML = String(value);
    }

    get innerHTML() {
        return this._innerHTML;
    }
}

class FakeDocument {
    constructor() {
        this.elements = new Map();
        this.listeners = new Map();
        this.activeElement = null;
    }

    register(id, options) {
        const element = new FakeElement(this, id, options);
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

    dispatch(type, event) {
        for (const listener of this.listeners.get(type) || []) listener.call(this, event);
    }

    contains(element) {
        return Array.from(this.elements.values()).includes(element);
    }
}

class FakeMediaQueryList {
    constructor(matches) {
        this.matches = matches;
        this.listeners = [];
    }

    addEventListener(type, listener) {
        if (type === 'change') this.listeners.push(listener);
    }

    addListener(listener) {
        this.listeners.push(listener);
    }

    setMatches(matches) {
        this.matches = matches;
        for (const listener of this.listeners) listener({ matches });
    }
}

function createHarness(options = {}) {
    const document = new FakeDocument();
    const authModal = document.register('authModal');
    const dialog = document.register('authModalDialog', {
        width: options.dialogWidth || 400,
        height: options.dialogHeight || 300,
        left: options.dialogLeft === undefined ? 200 : options.dialogLeft,
        top: options.dialogTop === undefined ? 150 : options.dialogTop
    });
    const titleBar = document.register('authModalTitleBar');
    const closeButton = document.register('authModalClose', { interactive: true, tagName: 'button' });
    const emailInput = document.register('loginEmail', { interactive: true, tagName: 'input' });
    const emailButton = document.register('sendEmailLoginButton', { interactive: true, tagName: 'button' });
    const emailMessage = document.register('emailLoginMsg');
    const googleButton = options.googleEnabled === false
        ? null
        : document.register('googleLoginButton', { interactive: true, tagName: 'a' });
    const googleMessage = options.googleEnabled === false ? null : document.register('googleLoginMsg');

    const navUserMenu = document.register('navUserMenu');
    document.register('navUserName');
    document.register('navLoginBtn');
    document.register('navUserToggle', { interactive: true });
    document.register('navLinks');
    document.register('navHamburger', { interactive: true });

    authModal.setAttribute('aria-hidden', 'true');
    authModal.queryResult = dialog;
    authModal.queryResults = [closeButton, googleButton, emailInput, emailButton].filter(Boolean);
    for (const element of [dialog, titleBar, closeButton, googleButton, emailInput, emailButton, emailMessage, googleMessage].filter(Boolean)) {
        authModal.descendants.add(element);
    }
    navUserMenu.descendants = new Set();

    const mediaQuery = new FakeMediaQueryList(options.mobile || false);
    const windowListeners = new Map();
    const window = {
        innerWidth: options.viewportWidth || 800,
        innerHeight: options.viewportHeight || 600,
        matchMedia: (query) => query === '(max-width: 480px)' ? mediaQuery : new FakeMediaQueryList(false),
        addEventListener(type, listener) {
            if (!windowListeners.has(type)) windowListeners.set(type, []);
            windowListeners.get(type).push(listener);
        },
        dispatch(type, event = {}) {
            for (const listener of windowListeners.get(type) || []) listener.call(window, event);
        }
    };
    const location = {
        search: options.search || '',
        reload() {}
    };
    const context = vm.createContext({
        window,
        document,
        location,
        URLSearchParams,
        JSON,
        console,
        fetch: () => Promise.resolve({ json: () => Promise.resolve({ code: 1 }) }),
        requestAnimationFrame: (callback) => callback()
    });

    return {
        context,
        document,
        window,
        mediaQuery,
        authModal,
        dialog,
        titleBar,
        closeButton,
        emailInput,
        emailButton,
        emailMessage,
        googleButton,
        googleMessage,
        run() {
            vm.runInContext(authScript, context, { filename: templatePath });
            return this;
        }
    };
}

function pointerEvent(target, overrides = {}) {
    return {
        button: 0,
        isPrimary: true,
        pointerId: 1,
        clientX: 100,
        clientY: 100,
        target,
        defaultPrevented: false,
        preventDefault() {
            this.defaultPrevented = true;
        },
        ...overrides
    };
}

test('disabled Google login falls back to a visible email status without aborting modal open', () => {
    const harness = createHarness({ googleEnabled: false, search: '?login=google_error' });

    assert.doesNotThrow(() => harness.run());
    assert.equal(harness.emailMessage.textContent, 'Google sign-in could not be completed. Please try again.');
    assert.equal(harness.emailMessage.style.display, 'block');
    assert.equal(harness.authModal.getAttribute('aria-hidden'), 'false');
});

test('dragging follows pointer displacement and clamps against all four viewport edges', () => {
    const harness = createHarness({ dialogLeft: 100, dialogTop: 100 }).run();
    const start = pointerEvent(harness.titleBar);

    harness.titleBar.dispatch('pointerdown', start);
    harness.titleBar.dispatch('pointermove', pointerEvent(harness.titleBar, { clientX: 150, clientY: 130 }));
    assert.equal(harness.dialog.style.left, '150px');
    assert.equal(harness.dialog.style.top, '130px');

    harness.titleBar.dispatch('pointermove', pointerEvent(harness.titleBar, { clientX: -1000, clientY: 100 }));
    assert.equal(harness.dialog.style.left, '8px');
    harness.titleBar.dispatch('pointermove', pointerEvent(harness.titleBar, { clientX: 100, clientY: -1000 }));
    assert.equal(harness.dialog.style.top, '8px');
    harness.titleBar.dispatch('pointermove', pointerEvent(harness.titleBar, { clientX: 1000, clientY: 100 }));
    assert.equal(harness.dialog.style.left, '392px');
    harness.titleBar.dispatch('pointermove', pointerEvent(harness.titleBar, { clientX: 100, clientY: 1000 }));
    assert.equal(harness.dialog.style.top, '292px');
});

test('interactive title-bar targets never start a drag', () => {
    const harness = createHarness().run();

    harness.titleBar.dispatch('pointerdown', pointerEvent(harness.closeButton));
    harness.titleBar.dispatch('pointerdown', pointerEvent(harness.googleButton));
    harness.titleBar.dispatch('pointerdown', pointerEvent(harness.emailInput));
    harness.titleBar.dispatch('pointerdown', pointerEvent(harness.emailButton));

    assert.equal(harness.context.authModalDrag, null);
    assert.equal(harness.titleBar.capturedPointers.size, 0);
    assert.equal(harness.dialog.classList.contains('is-dragging'), false);
});

test('pointer cancellation clears drag state, appearance, and pointer capture', () => {
    const harness = createHarness().run();

    harness.titleBar.dispatch('pointerdown', pointerEvent(harness.titleBar));
    assert.equal(harness.titleBar.hasPointerCapture(1), true);
    harness.titleBar.dispatch('pointercancel', pointerEvent(harness.titleBar));

    assert.equal(harness.context.authModalDrag, null);
    assert.equal(harness.dialog.classList.contains('is-dragging'), false);
    assert.equal(harness.titleBar.hasPointerCapture(1), false);
});

test('entering the mobile breakpoint cancels drag, releases capture, and resets position', () => {
    const harness = createHarness().run();

    harness.titleBar.dispatch('pointerdown', pointerEvent(harness.titleBar));
    harness.titleBar.dispatch('pointermove', pointerEvent(harness.titleBar, { clientX: 180, clientY: 160 }));
    harness.mediaQuery.setMatches(true);

    assert.equal(harness.context.authModalDrag, null);
    assert.equal(harness.titleBar.hasPointerCapture(1), false);
    assert.equal(harness.dialog.classList.contains('is-dragging'), false);
    assert.equal(harness.dialog.style.left, '');
    assert.equal(harness.dialog.style.top, '');
});

test('desktop viewport resize re-clamps an already positioned modal', () => {
    const harness = createHarness().run();
    harness.dialog.style.left = '392px';
    harness.dialog.style.top = '292px';
    harness.window.innerWidth = 600;
    harness.window.innerHeight = 500;

    harness.window.dispatch('resize');

    assert.equal(harness.dialog.style.left, '192px');
    assert.equal(harness.dialog.style.top, '192px');
});

test('message growth remeasures and re-clamps a positioned modal', () => {
    const harness = createHarness().run();
    harness.dialog.style.left = '100px';
    harness.dialog.style.top = '292px';
    harness.emailMessage.onTextContent = () => {
        harness.dialog.offsetHeight = 400;
    };

    harness.context.setAuthMsg('emailLoginMsg', 'A message that makes the modal taller.', '#ff5252');

    assert.equal(harness.dialog.style.left, '100px');
    assert.equal(harness.dialog.style.top, '192px');
});

test('a non-primary pointer cannot start a drag', () => {
    const harness = createHarness().run();

    harness.titleBar.dispatch('pointerdown', pointerEvent(harness.titleBar, { pointerId: 2, isPrimary: false }));

    assert.equal(harness.context.authModalDrag, null);
    assert.equal(harness.titleBar.hasPointerCapture(2), false);
});

test('a second pointer cannot replace the active drag', () => {
    const harness = createHarness().run();

    harness.titleBar.dispatch('pointerdown', pointerEvent(harness.titleBar));
    harness.titleBar.dispatch('pointerdown', pointerEvent(harness.titleBar, { pointerId: 2 }));

    assert.equal(harness.context.authModalDrag.pointerId, 1);
    assert.equal(harness.titleBar.hasPointerCapture(1), true);
    assert.equal(harness.titleBar.hasPointerCapture(2), false);
});

test('Tab and Shift+Tab cycle between the first and last modal controls', () => {
    const harness = createHarness().run();
    const first = harness.authModal.queryResults[0];
    const last = harness.authModal.queryResults[harness.authModal.queryResults.length - 1];
    const forward = { key: 'Tab', shiftKey: false, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
    const backward = { key: 'Tab', shiftKey: true, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };

    harness.context.openAuthModal();
    last.focus();
    harness.document.dispatch('keydown', forward);
    assert.equal(forward.defaultPrevented, true);
    assert.equal(harness.document.activeElement, first);

    first.focus();
    harness.document.dispatch('keydown', backward);
    assert.equal(backward.defaultPrevented, true);
    assert.equal(harness.document.activeElement, last);
});
