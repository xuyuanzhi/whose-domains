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

    assert.doesNotThrow(() => analytics.track('watch_created', {
        type: 'watch_created', category: 'watchlist', source: 'watchlist'
    }));
});

test('analytics sends only allowlisted event names and privacy-safe parameters', () => {
    const calls = [];
    const analytics = loadAnalytics((...args) => calls.push(args));

    analytics.track('watch_created', {
        type: 'watch_created',
        category: 'watchlist',
        risk: 'HIGH',
        source: 'watchlist',
        domain: 'private-example.com',
        email: 'person@example.com',
        userId: 'user-42',
        eventId: 'event-99',
        notificationText: 'Private notification details'
    });
    analytics.track('not_an_event', { type: 'watch_created', category: 'watchlist', source: 'watchlist' });

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

    analytics.track('notification_opened', {
        type: 'unrecognized-action',
        category: 'customer-entered-category',
        risk: 'UNTRUSTED',
        source: 'external-widget'
    });

    assert.deepEqual(JSON.parse(JSON.stringify(calls)), [['event', 'notification_opened', {}]]);
});
