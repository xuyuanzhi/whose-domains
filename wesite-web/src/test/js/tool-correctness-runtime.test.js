const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const views = path.resolve(__dirname, '../../main/resources/views/tools');
const timezone = fs.readFileSync(path.join(views, 'timezone-converter.html'), 'utf8');
const conversion = timezone.slice(timezone.indexOf('    function getUTCMs('), timezone.indexOf('    function offsetDiff('));
function convert(input, source) {
    const context = vm.createContext({input, source});
    vm.runInContext(conversion, context);
    return vm.runInContext('getUTCMs(input, source)', context);
}

for (const browserZone of ['UTC', 'Asia/Shanghai', 'America/Los_Angeles']) {
    test(`wall time conversion is independent of browser timezone (${browserZone})`, () => {
        const previous = process.env.TZ;
        process.env.TZ = browserZone;
        try {
            assert.equal(convert('2026-09-22T12:00', 'Asia/Shanghai'), Date.parse('2026-09-22T04:00:00Z'));
            assert.equal(convert('2026-09-22T12:00', 'UTC'), Date.parse('2026-09-22T12:00:00Z'));
            assert.equal(convert('2026-07-01T12:00', 'America/New_York'), Date.parse('2026-07-01T16:00:00Z'));
            assert.equal(convert('2026-01-01T12:00', 'America/New_York'), Date.parse('2026-01-01T17:00:00Z'));
            assert.equal(convert('2026-09-22T12:00', 'Asia/Kathmandu'), Date.parse('2026-09-22T06:15:00Z'));
        } finally {
            if (previous === undefined) delete process.env.TZ; else process.env.TZ = previous;
        }
    });
}
test('nonexistent DST wall time is rejected rather than silently shifted', () => {
    assert.throws(() => convert('2026-03-08T02:30', 'America/New_York'), /does not exist/i);
});
test('ambiguous DST wall time requires an unambiguous time', () => {
    assert.throws(() => convert('2026-11-01T01:30', 'America/New_York'), /occurs twice|ambiguous/i);
});

const ssl = fs.readFileSync(path.join(views, 'ssl_checker.html'), 'utf8');
function recommendations(algorithm, bits) {
    const nodes = new Map();
    const context = vm.createContext({
        document: {getElementById(id) { if (!nodes.has(id)) nodes.set(id, {}); return nodes.get(id); }},
        esc: value => String(value)
    });
    vm.runInContext(ssl.slice(ssl.indexOf('        function updateSslResults('), ssl.indexOf('        (function()', ssl.indexOf('        function updateSslResults('))), context);
    context.updateBestPractices({certificate:{publicKeyAlgorithm:algorithm,publicKeySize:bits},security:{}});
    return nodes.get('bestPractices').innerHTML;
}
test('EC 256-bit certificates are not given RSA weak-key advice', () => {
    assert.doesNotMatch(recommendations('EC','256 bits'), /weak|2048|bits bits/);
    assert.match(recommendations('EC','256 bits'), /Strong key/);
});
test('RSA still distinguishes 1024-bit from 2048-bit keys', () => {
    assert.match(recommendations('RSA',1024), /weak/);
    assert.match(recommendations('RSA',2048), /Strong key/);
});
test('unknown public key algorithms are not assigned RSA strength', () => {
    assert.doesNotMatch(recommendations('UNKNOWN',4096), /Strong key|weak/);
});
