const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const templatePath = path.resolve(__dirname, '../../main/resources/views/user/domain-watch.html');
const template = fs.readFileSync(templatePath, 'utf8');
const script = Array.from(template.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g), match => match[1])
    .find(candidate => candidate.includes('function checkLoginAndLoad()'));

function summaryMarkup(summary) {
    const editModal = { addEventListener() {} };
    const context = vm.createContext({
        document: { getElementById: () => editModal, addEventListener() {} },
        window: {},
        Date,
        encodeURIComponent
    });
    vm.runInContext(script, context, { filename: templatePath });
    return context.window.DomainWatchNotifications.monitoringSummary(summary);
}

test('watch monitoring summary renders the latest persisted risk, last successful check, and a domain-filtered notification link', () => {
    const markup = summaryMarkup({
        latestRisk: 'LOW',
        unreadCount: 2,
        lastSuccessfulCheck: '2026-08-09T12:30:00Z',
        latestEventSummary: 'WEBSITE_DOWN: offline',
        watch: { domainName: 'example.com' }
    });

    assert.match(markup, /Low risk/);
    assert.match(markup, /Last successful check: 2026-08-09T12:30:00Z/);
    assert.match(markup, /2 unread events/);
    assert.match(markup, /href="\/user\/notifications\?domain=example.com"/);
});

test('watch monitoring summary keeps zero-event watches quiet and does not create a notification link', () => {
    const markup = summaryMarkup({
        latestRisk: 'UNKNOWN',
        unreadCount: 0,
        lastSuccessfulCheck: null,
        latestEventSummary: 'No monitoring events yet',
        watch: { domainName: 'example.com' }
    });

    assert.match(markup, /Unrated/);
    assert.match(markup, /No successful checks yet/);
    assert.match(markup, /No monitoring events yet/);
    assert.doesNotMatch(markup, /user\/notifications/);
});
