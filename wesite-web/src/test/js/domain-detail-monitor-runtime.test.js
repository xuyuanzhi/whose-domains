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

    focus() {
        this.focused = true;
    }
}

class FakeDocument {
    constructor() {
        this.elements = new Map();
        this.listeners = new Map();
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

    addEventListener(type, listener) {
        if (!this.listeners.has(type)) this.listeners.set(type, []);
        this.listeners.get(type).push(listener);
    }

    dispatch(type, event = {}) {
        for (const listener of this.listeners.get(type) || []) listener(event);
    }
}

function response(statusOrData, responseData) {
    const status = typeof statusOrData === 'number' ? statusOrData : 200;
    const data = typeof statusOrData === 'number' ? responseData : statusOrData;
    return Promise.resolve({
        ok: status >= 200 && status < 300,
        status,
        json: () => Promise.resolve(data)
    });
}

function responseWithJsonFailure(status, message = 'invalid JSON') {
    return Promise.resolve({
        ok: status >= 200 && status < 300,
        status,
        json: () => Promise.reject(new Error(message))
    });
}

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((resolvePromise, rejectPromise) => {
        resolve = resolvePromise;
        reject = rejectPromise;
    });
    return { promise, resolve, reject };
}

function createHarness({
    pathname = '/domain/example.com',
    search = '',
    hash = '',
    responses = {},
    googleEnabled = true,
    emailLoginResponse = () => response({ code: 0 })
} = {}) {
    const document = new FakeDocument();
    const monitorButton = document.register('monitorDomainBtn', { 'data-domain': 'example.com' });
    const modal = document.register('monitorModal');
    const googleLink = googleEnabled ? document.register('monitorGoogleLogin') : null;
    const googleMessage = googleEnabled ? document.register('monitorGoogleMessage') : null;
    const email = document.register('monitorEmail');
    const sendLinkButton = document.register('sendMonitorLink');
    const emailMessage = document.register('monitorEmailMessage');
    document.register('monitorDomainName');
    const closeButton = document.register('closeMonitorModal');
    const fetchCalls = [];
    const historyCalls = [];
    const context = vm.createContext({
        document,
        fetch(url, options) {
            fetchCalls.push({ url, options });
            if (Object.prototype.hasOwnProperty.call(responses, url)) {
                const configuredResponse = responses[url];
                return typeof configuredResponse === 'function'
                    ? configuredResponse(url, options)
                    : configuredResponse;
            }
            if (url.startsWith('/api/domain-watch/check/')) return response({ code: 0, data: false });
            if (url === '/user/email-login') return emailLoginResponse();
            if (url === '/user/session') return response({ code: 1 });
            throw new Error(`Unexpected request: ${url}`);
        },
        location: { pathname, search, hash },
        history: {
            replaceState(state, title, url) {
                historyCalls.push({ state, title, url });
            }
        },
        window: {
            setInterval() { return 1; },
            clearInterval() {}
        },
        JSON,
        URLSearchParams,
        encodeURIComponent
    });

    return {
        monitorButton,
        modal,
        googleLink,
        googleMessage,
        email,
        sendLinkButton,
        emailMessage,
        closeButton,
        document,
        button: monitorButton,
        fetchCalls,
        historyCalls,
        get historyUrls() {
            return historyCalls.map((call) => call.url);
        },
        run() {
            vm.runInContext(monitorScript, context, { filename: templatePath });
            return this;
        },
        flushPromises
    };
}

async function flushPromises() {
    await new Promise((resolve) => setImmediate(resolve));
}

test('monitor email validation writes to the dedicated email status without an old shared message node', () => {
    const harness = createHarness().run();

    assert.doesNotThrow(() => harness.sendLinkButton.dispatch('click'));
    assert.equal(harness.emailMessage.textContent, 'Enter your email address.');
    assert.equal(harness.emailMessage.style.display, 'block');
    assert.equal(harness.sendLinkButton.disabled, false);
});

test('monitor email success enters cooldown and announces its result without throwing', async () => {
    const harness = createHarness({ emailLoginResponse: () => response({ code: 0, msg: 'Check your inbox.' }) }).run();
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
    const harness = createHarness({ emailLoginResponse: () => response({ code: 1, msg: 'Delivery failed.' }) }).run();
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
    const harness = createHarness({ emailLoginResponse: () => Promise.reject(new Error('network unavailable')) }).run();
    harness.email.value = 'owner@example.com';

    assert.doesNotThrow(() => harness.sendLinkButton.dispatch('click'));
    await flushPromises();

    assert.equal(harness.emailMessage.textContent, 'Network error.');
    assert.equal(harness.emailMessage.style.color, '#ff5252');
    assert.equal(harness.emailMessage.style.display, 'block');
    assert.equal(harness.sendLinkButton.disabled, false);
    assert.equal(harness.sendLinkButton.textContent, 'Email me a sign-in link');
});

test('Google monitor login carries the current domain continuation target', () => {
    const harness = createHarness({ pathname: '/domain/example.com', search: '?source=lookup' }).run();

    assert.equal(
        harness.googleLink.href,
        '/login/google?returnTo=' + encodeURIComponent('/domain/example.com?source=lookup&monitor=pending')
    );
});

test('Google and email monitor logins share a sanitized continuation target', async () => {
    const returnTo = '/domain/example.com?source=lookup&monitor=pending';
    const harness = createHarness({
        pathname: '/domain/example.com',
        search: '?source=lookup&login=google_error'
    }).run();
    harness.email.value = 'person@example.com';
    harness.sendLinkButton.dispatch('click');
    await harness.flushPromises();

    const request = harness.fetchCalls.find((call) => call.url === '/user/email-login');
    assert.equal(
        harness.googleLink.href,
        '/login/google?returnTo=' + encodeURIComponent(returnTo)
    );
    assert.deepEqual(JSON.parse(request.options.body), {
        email: 'person@example.com',
        returnTo
    });
    assert.doesNotMatch(harness.googleLink.href, /login=google_error/);
    assert.doesNotMatch(JSON.parse(request.options.body).returnTo, /login=google_error/);
    assert.equal(harness.emailMessage.textContent, 'Check your inbox.');
    assert.equal(harness.googleMessage.textContent, 'Google sign-in could not be completed. Please try again.');
});

test('Google callback errors reopen the monitor dialog in the Google status region', async () => {
    const harness = createHarness({
        search: '?source=lookup&monitor=pending&login=google_error',
        hash: '#whois'
    }).run();
    await harness.flushPromises();

    assert.equal(harness.modal.style.display, 'flex');
    assert.equal(harness.googleMessage.textContent, 'Google sign-in could not be completed. Please try again.');
    assert.equal(harness.emailMessage.textContent, '');
    assert.equal(harness.fetchCalls.some((call) => [
        '/api/domain-watch/check/example.com',
        '/user/session',
        '/api/domain-watch/watch'
    ].includes(call.url)), false);
    assert.deepEqual(harness.historyCalls, [{
        state: null,
        title: '',
        url: '/domain/example.com?source=lookup#whois'
    }]);
});

test('email link errors reopen the monitor dialog in the email status region', async () => {
    const harness = createHarness({ search: '?monitor=pending&login=invalid' }).run();
    await harness.flushPromises();

    assert.equal(harness.emailMessage.textContent, 'This sign-in link is invalid or has expired.');
    assert.equal(harness.googleMessage.textContent, '');
    assert.equal(harness.fetchCalls.some((call) => [
        '/api/domain-watch/check/example.com',
        '/user/session',
        '/api/domain-watch/watch'
    ].includes(call.url)), false);
    assert.deepEqual(harness.historyCalls, [{
        state: null,
        title: '',
        url: '/domain/example.com'
    }]);
});

test('unknown login results continue normal initialization without changing the URL', async () => {
    const harness = createHarness({
        search: '?source=lookup&monitor=pending&login=unexpected_code',
        hash: '#whois'
    }).run();
    await harness.flushPromises();

    assert.equal(harness.modal.style.display, undefined);
    assert.equal(harness.googleMessage.textContent, '');
    assert.equal(harness.emailMessage.textContent, '');
    assert.deepEqual(harness.historyCalls, []);
    assert.deepEqual(harness.fetchCalls.map((call) => call.url), [
        '/api/domain-watch/check/example.com'
    ]);
    assert.equal(harness.fetchCalls[0].options.credentials, 'include');
});

test('pending continuation adds the current page domain once and clears only the monitor parameter', async () => {
    const harness = createHarness({
        pathname: '/domain/example.com',
        search: '?source=email&domain=attacker.example&monitor=pending',
        hash: '#whois',
        responses: {
            '/user/session': response(200, {code: 0}),
            '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
            '/api/domain-watch/watch': response(200, {code: 0})
        }
    }).run();
    await harness.flushPromises();

    assert.deepEqual(harness.fetchCalls.map((call) => call.url), [
        '/user/session',
        '/api/domain-watch/check/example.com',
        '/api/domain-watch/watch'
    ]);
    const watchCalls = harness.fetchCalls.filter((call) => call.url === '/api/domain-watch/watch');
    assert.equal(watchCalls.length, 1);
    assert.deepEqual(JSON.parse(watchCalls[0].options.body), {domainName: 'example.com', notifyType: 3});
    assert.equal(harness.button.textContent.includes('Monitoring'), true);
    assert.equal(harness.historyUrls.at(-1), '/domain/example.com?source=email&domain=attacker.example#whois');
});

test('pending continuation reopens the monitor dialog when the session is still signed out', async () => {
    const harness = createHarness({
        search: '?monitor=pending',
        responses: {'/user/session': response(401, {code: 401})}
    }).run();
    await harness.flushPromises();

    assert.equal(harness.modal.style.display, 'flex');
    assert.equal(harness.googleLink.focused, true);
    assert.equal(harness.fetchCalls.some((call) => call.url === '/api/domain-watch/watch'), false);
});

test('pending continuation skips add when the domain is already watched', async () => {
    const harness = createHarness({
        search: '?monitor=pending',
        responses: {
            '/user/session': response(200, {code: 0}),
            '/api/domain-watch/check/example.com': response(200, {code: 0, data: true})
        }
    }).run();
    await harness.flushPromises();

    assert.equal(harness.button.textContent.includes('Monitoring'), true);
    assert.deepEqual(harness.fetchCalls.map((call) => call.url), [
        '/user/session',
        '/api/domain-watch/check/example.com'
    ]);
    assert.equal(harness.fetchCalls.some((call) => call.url === '/api/domain-watch/watch'), false);
});

test('ordinary add failure opens the dialog and reports in the email status region', async () => {
    const harness = createHarness({
        search: '?monitor=pending',
        responses: {
            '/user/session': response(200, {code: 0}),
            '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
            '/api/domain-watch/watch': response(400, {code: 500, msg: 'Watch limit reached.'})
        }
    }).run();
    await harness.flushPromises();

    assert.equal(harness.modal.style.display, 'flex');
    assert.equal(harness.emailMessage.textContent, 'Watch limit reached.');
    assert.equal(harness.button.textContent.includes('Monitoring'), false);
});

test('session continuation requires both HTTP and business success', async () => {
    const cases = [
        {name: 'HTTP failure', session: response(503, {code: 0})},
        {name: 'business failure', session: response(200, {code: 1})}
    ];

    for (const scenario of cases) {
        const harness = createHarness({
            search: '?monitor=pending',
            responses: {'/user/session': scenario.session}
        }).run();
        await harness.flushPromises();

        assert.equal(harness.modal.style.display, 'flex', scenario.name);
        assert.deepEqual(harness.fetchCalls.map((call) => call.url), ['/user/session'], scenario.name);
    }
});

test('monitoring check requires both HTTP and business success', async () => {
    const cases = [
        {name: 'HTTP failure', check: response(503, {code: 0, data: true})},
        {name: 'business failure', check: response(200, {code: 1, data: true})}
    ];

    for (const scenario of cases) {
        const harness = createHarness({
            responses: {'/api/domain-watch/check/example.com': scenario.check}
        }).run();
        await harness.flushPromises();

        assert.equal(harness.button.textContent.includes('Monitoring'), false, scenario.name);
    }
});

test('add authentication failures reopen the dialog without an ordinary failure message', async () => {
    const cases = [
        {name: 'HTTP 401', watch: response(401, {code: 500})},
        {name: 'HTTP 403', watch: response(403, {code: 0})},
        {name: 'business 401', watch: response(200, {code: 401})},
        {name: 'business -401', watch: response(200, {code: -401})}
    ];

    for (const scenario of cases) {
        const harness = createHarness({
            search: '?monitor=pending',
            responses: {
                '/user/session': response(200, {code: 0}),
                '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
                '/api/domain-watch/watch': scenario.watch
            }
        }).run();
        await harness.flushPromises();

        assert.equal(harness.modal.style.display, 'flex', scenario.name);
        assert.equal(harness.emailMessage.textContent, '', scenario.name);
        assert.equal(harness.button.textContent.includes('Monitoring'), false, scenario.name);
    }
});

test('an HTTP add failure cannot report success from a business success payload', async () => {
    const harness = createHarness({
        search: '?monitor=pending',
        responses: {
            '/user/session': response(200, {code: 0}),
            '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
            '/api/domain-watch/watch': response(409, {code: 0, msg: 'Domain could not be watched.'})
        }
    }).run();
    await harness.flushPromises();

    assert.equal(harness.button.textContent.includes('Monitoring'), false);
    assert.equal(harness.modal.style.display, 'flex');
    assert.equal(harness.emailMessage.textContent, 'Domain could not be watched.');
});

test('an authenticated monitor button click adds the domain and enters Monitoring', async () => {
    const harness = createHarness({
        responses: {
            '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
            '/user/session': response(200, {code: 0}),
            '/api/domain-watch/watch': response(200, {code: 0})
        }
    }).run();
    await harness.flushPromises();

    harness.button.dispatch('click');
    await harness.flushPromises();

    assert.deepEqual(harness.fetchCalls.map((call) => call.url), [
        '/api/domain-watch/check/example.com',
        '/user/session',
        '/api/domain-watch/watch'
    ]);
    const watchRequest = harness.fetchCalls.at(-1);
    assert.deepEqual(JSON.parse(watchRequest.options.body), {domainName: 'example.com', notifyType: 3});
    assert.equal(harness.button.textContent.includes('Monitoring'), true);
});

test('a signed-out monitor button click opens the dialog without posting a watch', async () => {
    const harness = createHarness({
        responses: {
            '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
            '/user/session': response(401, {code: 401})
        }
    }).run();
    await harness.flushPromises();

    harness.button.dispatch('click');
    await harness.flushPromises();

    assert.equal(harness.modal.style.display, 'flex');
    assert.equal(harness.googleLink.focused, true);
    assert.equal(harness.emailMessage.textContent, '');
    assert.equal(harness.fetchCalls.some((call) => call.url === '/api/domain-watch/watch'), false);
});

test('addWatch network and JSON failures open the dialog with a visible retryable error', async () => {
    const cases = [
        {name: 'network rejection', watch: () => Promise.reject(new Error('network unavailable'))},
        {name: 'JSON rejection', watch: responseWithJsonFailure(200)}
    ];

    for (const scenario of cases) {
        const harness = createHarness({
            responses: {
                '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
                '/user/session': response(200, {code: 0}),
                '/api/domain-watch/watch': scenario.watch
            }
        }).run();
        await harness.flushPromises();

        harness.button.dispatch('click');
        await harness.flushPromises();

        assert.equal(harness.modal.style.display, 'flex', scenario.name);
        assert.equal(
            harness.emailMessage.textContent,
            'Could not add this domain to your watchlist.',
            scenario.name
        );
        assert.equal(harness.emailMessage.style.display, 'block', scenario.name);
        assert.equal(harness.button.disabled, false, scenario.name);
    }
});

test('pending continuation fetch and JSON failures show the continuation error', async () => {
    const cases = [
        {name: 'fetch rejection', session: () => Promise.reject(new Error('network unavailable'))},
        {name: 'JSON rejection', session: responseWithJsonFailure(200)}
    ];

    for (const scenario of cases) {
        const harness = createHarness({
            search: '?monitor=pending',
            responses: {'/user/session': scenario.session}
        }).run();
        await harness.flushPromises();

        assert.equal(harness.modal.style.display, 'flex', scenario.name);
        assert.equal(
            harness.emailMessage.textContent,
            'Could not start monitoring. Please try again.',
            scenario.name
        );
        assert.equal(harness.emailMessage.style.display, 'block', scenario.name);
        assert.deepEqual(harness.fetchCalls.map((call) => call.url), ['/user/session'], scenario.name);
    }
});

test('monitor button session fetch and JSON failures open sign-in without posting a watch', async () => {
    const cases = [
        {name: 'fetch rejection', session: () => Promise.reject(new Error('network unavailable'))},
        {name: 'JSON rejection', session: responseWithJsonFailure(200)}
    ];

    for (const scenario of cases) {
        const harness = createHarness({
            responses: {
                '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
                '/user/session': scenario.session
            }
        }).run();
        await harness.flushPromises();

        harness.button.dispatch('click');
        await harness.flushPromises();

        assert.equal(harness.modal.style.display, 'flex', scenario.name);
        assert.equal(harness.googleLink.focused, true, scenario.name);
        assert.equal(harness.emailMessage.textContent, '', scenario.name);
        assert.equal(harness.fetchCalls.some((call) => call.url === '/api/domain-watch/watch'), false, scenario.name);
    }
});

test('a failed add can be retried and the second click enters Monitoring', async () => {
    let watchAttempts = 0;
    const harness = createHarness({
        responses: {
            '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
            '/user/session': response(200, {code: 0}),
            '/api/domain-watch/watch': () => {
                watchAttempts++;
                return watchAttempts === 1
                    ? Promise.reject(new Error('network unavailable'))
                    : response(200, {code: 0});
            }
        }
    }).run();
    await harness.flushPromises();

    harness.button.dispatch('click');
    await harness.flushPromises();
    assert.equal(harness.emailMessage.textContent, 'Could not add this domain to your watchlist.');
    assert.equal(harness.button.disabled, false);

    harness.modal.style.display = 'none';
    harness.button.dispatch('click');
    await harness.flushPromises();

    assert.equal(watchAttempts, 2);
    assert.equal(harness.button.textContent.includes('Monitoring'), true);
});

test('pending continuation clears its URL marker before requesting the session', async () => {
    let historyCountWhenSessionRequested = -1;
    const harness = createHarness({
        pathname: '/domain/example.com',
        search: '?source=email&monitor=pending',
        hash: '#whois',
        responses: {
            '/user/session': () => {
                historyCountWhenSessionRequested = harness.historyCalls.length;
                return response(401, {code: 401});
            }
        }
    });

    harness.run();
    await harness.flushPromises();

    assert.equal(historyCountWhenSessionRequested, 1);
    assert.deepEqual(harness.historyUrls, ['/domain/example.com?source=email#whois']);
});

test('an idempotent already-watched response enters Monitoring without opening a failure dialog', async () => {
    const harness = createHarness({
        search: '?monitor=pending',
        responses: {
            '/user/session': response(200, {code: 0}),
            '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
            '/api/domain-watch/watch': response(200, {
                code: 0,
                msg: 'Domain is already being monitored.',
                data: {id: 'watch-1', domainName: 'example.com', status: 1}
            })
        }
    }).run();
    await harness.flushPromises();

    assert.equal(harness.button.textContent.includes('Monitoring'), true);
    assert.equal(harness.modal.style.display, undefined);
    assert.equal(harness.emailMessage.textContent, '');
});

test('pending continuation and repeated clicks share one in-flight session check and watch chain', async () => {
    const sessionGate = deferred();
    const harness = createHarness({
        search: '?monitor=pending',
        responses: {
            '/user/session': () => sessionGate.promise,
            '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
            '/api/domain-watch/watch': response(200, {code: 0})
        }
    }).run();

    assert.equal(harness.button.disabled, true);
    assert.equal(harness.button.getAttribute('aria-busy'), 'true');
    harness.button.dispatch('click');
    harness.button.dispatch('click');
    assert.deepEqual(harness.fetchCalls.map((call) => call.url), ['/user/session']);

    sessionGate.resolve(await response(200, {code: 0}));
    await harness.flushPromises();

    assert.deepEqual(harness.fetchCalls.map((call) => call.url), [
        '/user/session',
        '/api/domain-watch/check/example.com',
        '/api/domain-watch/watch'
    ]);
    assert.equal(harness.button.textContent.includes('Monitoring'), true);
    assert.equal(harness.button.disabled, true);
    assert.equal(harness.button.getAttribute('aria-busy'), 'false');
});

test('a failed shared monitor flow releases busy state and a later click retries', async () => {
    let checkAttempts = 0;
    const harness = createHarness({
        search: '?monitor=pending',
        responses: {
            '/user/session': response(200, {code: 0}),
            '/api/domain-watch/check/example.com': () => {
                checkAttempts++;
                return checkAttempts === 1
                    ? response(503, {code: 500, msg: 'Monitoring is temporarily unavailable.'})
                    : response(200, {code: 0, data: false});
            },
            '/api/domain-watch/watch': response(200, {code: 0})
        }
    }).run();
    await harness.flushPromises();

    assert.equal(harness.emailMessage.textContent, 'Monitoring is temporarily unavailable.');
    assert.equal(harness.fetchCalls.some((call) => call.url === '/api/domain-watch/watch'), false);
    assert.equal(harness.button.disabled, false);
    assert.equal(harness.button.getAttribute('aria-busy'), 'false');

    harness.button.dispatch('click');
    await harness.flushPromises();

    assert.equal(checkAttempts, 2);
    assert.equal(harness.fetchCalls.filter((call) => call.url === '/api/domain-watch/watch').length, 1);
    assert.equal(harness.button.textContent.includes('Monitoring'), true);
});

test('ordinary monitoring check failures are visible and never fall through to POST watch', async () => {
    const cases = [
        {name: 'HTTP failure', check: response(503, {code: 500, msg: 'Check service unavailable.'}), message: 'Check service unavailable.'},
        {name: 'business failure', check: response(200, {code: 500, msg: 'Check rejected.'}), message: 'Check rejected.'},
        {name: 'JSON failure', check: responseWithJsonFailure(200), message: 'Could not check whether this domain is already monitored.'},
        {name: 'network failure', check: () => Promise.reject(new Error('network unavailable')), message: 'Could not check whether this domain is already monitored.'}
    ];

    for (const scenario of cases) {
        const harness = createHarness({
            search: '?monitor=pending',
            responses: {
                '/user/session': response(200, {code: 0}),
                '/api/domain-watch/check/example.com': scenario.check,
                '/api/domain-watch/watch': response(200, {code: 0})
            }
        }).run();
        await harness.flushPromises();

        assert.equal(harness.modal.style.display, 'flex', scenario.name);
        assert.equal(harness.emailMessage.textContent, scenario.message, scenario.name);
        assert.equal(harness.fetchCalls.some((call) => call.url === '/api/domain-watch/watch'), false, scenario.name);
        assert.equal(harness.button.disabled, false, scenario.name);
    }
});

test('monitoring check authentication failures use the sign-in path and never POST watch', async () => {
    const cases = [
        {name: 'HTTP 401', check: response(401, {code: 500})},
        {name: 'HTTP 403', check: response(403, {code: 0})},
        {name: 'business 401', check: response(200, {code: 401})},
        {name: 'business -401', check: response(200, {code: -401})}
    ];

    for (const scenario of cases) {
        const harness = createHarness({
            search: '?monitor=pending',
            responses: {
                '/user/session': response(200, {code: 0}),
                '/api/domain-watch/check/example.com': scenario.check,
                '/api/domain-watch/watch': response(200, {code: 0})
            }
        }).run();
        await harness.flushPromises();

        assert.equal(harness.modal.style.display, 'flex', scenario.name);
        assert.equal(harness.emailMessage.textContent, '', scenario.name);
        assert.equal(harness.fetchCalls.some((call) => call.url === '/api/domain-watch/watch'), false, scenario.name);
        assert.equal(harness.button.disabled, false, scenario.name);
    }
});

test('monitor dialog falls back to email focus when Google is disabled', async () => {
    const harness = createHarness({
        googleEnabled: false,
        responses: {
            '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
            '/user/session': response(401, {code: 401})
        }
    }).run();
    await harness.flushPromises();

    harness.button.dispatch('click');
    await harness.flushPromises();

    assert.equal(harness.modal.style.display, 'flex');
    assert.equal(harness.email.focused, true);
});

test('close button and Escape close the monitor dialog and restore focus to its trigger', async () => {
    const harness = createHarness({
        responses: {
            '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
            '/user/session': response(401, {code: 401})
        }
    }).run();
    await harness.flushPromises();

    harness.button.dispatch('click');
    await harness.flushPromises();
    harness.closeButton.dispatch('click');
    assert.equal(harness.modal.style.display, 'none');
    assert.equal(harness.button.focused, true);

    harness.button.focused = false;
    harness.button.dispatch('click');
    await harness.flushPromises();
    harness.document.dispatch('keydown', {key: 'Escape'});
    assert.equal(harness.modal.style.display, 'none');
    assert.equal(harness.button.focused, true);
});
