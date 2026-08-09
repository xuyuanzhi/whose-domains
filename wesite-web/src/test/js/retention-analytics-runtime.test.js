const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const runtimePath = path.resolve(__dirname, '../../main/resources/static/js/retention-analytics.js');

function loadAnalytics(gtag) {
    const source = fs.readFileSync(runtimePath, 'utf8');
    const window = {};
    if (typeof gtag === 'function') window.gtag = gtag;
    const context = vm.createContext({
        window
    });
    context.window.window = context.window;
    vm.runInContext(source, context, { filename: runtimePath });
    return context.window.WhoseRetentionAnalytics;
}

test('analytics is a no-op when GA4 is unavailable', () => {
    const analytics = loadAnalytics(undefined);

    assert.equal(analytics.track('watch_created', {
        type: 'watch_created', category: 'watchlist', source: 'watchlist'
    }), false);
});

test('analytics sends only allowlisted event names and privacy-safe parameters', () => {
    const calls = [];
    const analytics = loadAnalytics((...args) => calls.push(args));

    assert.equal(analytics.track('watch_created', {
        type: 'watch_created',
        category: 'watchlist',
        risk: 'HIGH',
        source: 'watchlist',
        domain: 'private-example.com',
        email: 'person@example.com',
        userId: 'user-42',
        eventId: 'event-99',
        notificationText: 'Private notification details'
    }), true);
    assert.equal(analytics.track('not_an_event', { type: 'watch_created', category: 'watchlist', source: 'watchlist' }), false);

    assert.deepEqual(JSON.parse(JSON.stringify(calls)), [[
        'event',
        'watch_created',
        { type: 'watch_created', category: 'watchlist', risk: 'HIGH', source: 'watchlist' }
    ]]);
    assert.deepEqual(Object.keys(calls[0][2]).sort(), ['category', 'risk', 'source', 'type']);
});

test('analytics drops unrecognized parameter values instead of forwarding caller data', () => {
    const calls = [];
    const analytics = loadAnalytics((...args) => calls.push(args));

    assert.equal(analytics.track('notification_opened', {
        type: 'unrecognized-action',
        category: 'customer-entered-category',
        risk: 'UNTRUSTED',
        source: 'external-widget'
    }), true);

    assert.deepEqual(JSON.parse(JSON.stringify(calls)), [['event', 'notification_opened', {}]]);
});

test('domain-detail and evidence CTAs use explicit privacy-safe allowlist values', () => {
    const calls = [];
    const analytics = loadAnalytics((...args) => calls.push(args));

    assert.equal(analytics.track('domain_detail_cta_clicked', {
        type: 'monitor_domain', category: 'watchlist', source: 'domain_detail', domain: 'private.example'
    }), true);
    assert.equal(analytics.track('notification_action_clicked', {
        type: 'open_evidence', category: 'security', source: 'notification_center'
    }), true);

    assert.deepEqual(JSON.parse(JSON.stringify(calls)), [
        ['event', 'domain_detail_cta_clicked', { type: 'monitor_domain', category: 'watchlist', source: 'domain_detail' }],
        ['event', 'notification_action_clicked', { type: 'open_evidence', category: 'security', source: 'notification_center' }]
    ]);
});

test('analytics swallows a throwing gtag and reports the failed delivery', () => {
    const analytics = loadAnalytics(() => { throw new Error('GA4 unavailable'); });

    assert.doesNotThrow(() => analytics.track('watch_created', {
        type: 'watch_created', category: 'watchlist', source: 'watchlist'
    }));
    assert.equal(analytics.track('watch_created', {
        type: 'watch_created', category: 'watchlist', source: 'watchlist'
    }), false);
});
