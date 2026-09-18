const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function loadSearch(fetch) {
    const active = new Set();
    const notices = [];
    const context = vm.createContext({
        document: { getElementById: id => id === 'domainInput' ? {value: 'www.chinabbs.com'}
            : id === 'loader' ? {classList: {add: key => active.add(key), remove: key => active.delete(key)}} : null },
        window: {location: {href: '/'}},
        $: () => ({ready() {}}),
        console: {error() {}}, fetch
    });
    vm.runInContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/common.js'), 'utf8'), context);
    context.showNotification = (msg, type) => notices.push({msg, type});
    return {context, active, notices};
}

for (const [name, fetch] of [
    ['HTTP 500', async () => ({ok: false, status: 500})],
    ['network failure', async () => {throw new Error('offline');}],
    ['invalid JSON', async () => ({ok: true, json: async () => {throw new Error('invalid JSON');}})],
    ['lookup failure', async () => ({ok: true, json: async () => ({code: 400, msg: 'Not found'})})]
]) {
    test(name + ' clears loading and displays an error without navigation', async () => {
        const {context, active, notices} = loadSearch(fetch);
        await context.performSearch();
        assert.equal(active.size, 0);
        assert.equal(notices.length, 1);
        assert.ok(notices[0].msg);
        assert.equal(context.window.location.href, '/');
    });
}

test('successful search navigates to the requested domain', async () => {
    const {context, active, notices} = loadSearch(async () => ({ok: true, json: async () => ({code: 0})}));
    await context.performSearch();
    assert.equal(context.window.location.href, '/domain/www.chinabbs.com');
    assert.equal(active.size, 0);
    assert.equal(notices.length, 0);
});
