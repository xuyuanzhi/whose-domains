// Local template regression harness: real page CSS/JS, deterministic API fixtures.
// Shared Thymeleaf header/footer and live services are outside this harness.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const resources = path.resolve(__dirname, '../../main/resources');
const output = path.resolve(process.env.FRONTEND_AUDIT_OUTPUT || 'reports/frontend-2026-09-22/fix-browser');
fs.mkdirSync(output, {recursive:true});
const css = fs.readFileSync(path.join(resources, 'static/style/common.css'), 'utf8');
const dns = {nsRecords:{nameServers:['ns1.example.com','ns2.example.com'],count:2},addressRecords:{A:['192.0.2.1'],AAAA:['2001:db8::1234'],hasIPv6:true},mailRecords:{SPF:'v=spf1 include:example.com -all'},securityRecords:{DMARC:'v=DMARC1; p=reject',TXT:['verification='+'a'.repeat(60)]}};
const ssl = {certificate:{currentlyValid:true,publicKeyAlgorithm:'EC',publicKeySize:'256 bits',signatureAlgorithm:'SHA256withECDSA',issuer:'Test issuer'},validity:{daysUntilExpiration:90},security:{hstsEnabled:true,protocolSupport:['TLSv1.3']}};
const pages = {domain:'domain_detail.html',api:'api_docs.html',dns:'tools/dns_analyzer.html',timezone:'tools/timezone-converter.html',ssl:'tools/ssl_checker.html'};
function template(name) {
    let html = fs.readFileSync(path.join(resources, 'views', pages[name]), 'utf8');
    html = html.replace(/<script\b[^>]*(?:\bsrc=|th:src=)[^>]*>[\s\S]*?<\/script>/gi, '');
    if (name === 'domain' || name === 'api') html = html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '');
    return html.replace('</head>', '<meta name="viewport" content="width=device-width,initial-scale=1"><style>'+css+'</style></head>');
}
(async () => {
    const browser = await chromium.launch({channel:'chrome',headless:true});
    const results = [];
    try {
        const context = await browser.newContext({timezoneId:'Asia/Shanghai'});
        await context.route('**/*', async route => {
            const url = new URL(route.request().url());
            if (url.hostname !== 'frontend.test') return route.abort();
            if (url.pathname.startsWith('/api/tools/')) return route.fulfill({json:{code:0,data:url.pathname.includes('dns-analyze') ? dns : ssl}});
            const name = url.pathname.slice(1);
            return pages[name] ? route.fulfill({contentType:'text/html; charset=utf-8',body:template(name)}) : route.fulfill({status:404,body:''});
        });
        for (const width of [390,768,1440]) {
            for (const name of Object.keys(pages)) {
                const page = await context.newPage();
                const errors = [];
                page.on('pageerror', e => errors.push(e.message));
                await page.setViewportSize({width,height:900});
                await page.goto('http://frontend.test/'+name);
                if (name === 'domain') await page.evaluate(() => {
                    document.querySelector('#resultDomain').textContent = 'example.com';
                    document.querySelectorAll('.domain-raw-response').forEach(e => e.textContent = JSON.stringify({ldhName:'example.com',longValue:'a'.repeat(700)},null,2));
                    document.querySelectorAll('.info-value').forEach(e => {if (!e.textContent.trim()) e.textContent = 'ns1.example.com';});
                });
                if (name === 'dns') {
                    await page.locator('#domainInput').fill('example.com');
                    await page.locator('#analyzeBtn').click();
                    await page.locator('#resultsSection.active').waitFor();
                    assert.equal(await page.locator('#addressRecords tr').first().locator('td').nth(2).innerText(), '192.0.2.1');
                    assert(await page.locator('#addressRecords tr').first().locator('td').nth(2).isVisible());
                    assert(await page.getByRole('columnheader',{name:'Address family',exact:true}).isVisible());
                    const clipped = await page.locator('.dns-table th').evaluateAll(els => els.filter(e => e.scrollWidth > e.clientWidth + 1).map(e => e.textContent));
                    assert.deepEqual(clipped, [], 'DNS headers must fit: '+width);
                }
                if (name === 'timezone') {
                    await page.locator('#inputDateTime').fill('2026-09-22T12:00');
                    await page.locator('#sourceTimezone').selectOption('Asia/Shanghai');
                    await page.locator('#targetTimezone0').selectOption('UTC');
                    await page.locator('#btnConvert').click();
                    assert.match(await page.locator('#conversionResults').innerText(), /04:00:00 AM/);
                    assert.match(await page.locator('#conversionResults').innerText(), /Sep 22, 2026/);
                }
                if (name === 'ssl') {
                    await page.locator('#sslDomainInput').fill('example.com');
                    await page.locator('#sslDomainInput').press('Enter');
                    await page.locator('#resultsSection.active').waitFor();
                    const advice = await page.locator('#bestPractices').innerText();
                    assert.match(advice,/Strong key size \(256 bits, EC\)/);
                    assert.doesNotMatch(advice,/weak|2048|bits bits/);
                }
                const dimensions = await page.evaluate(() => ({viewport:innerWidth,document:document.documentElement.scrollWidth}));
                await page.screenshot({path:path.join(output,`${name}-${width}.png`),fullPage:true,animations:'disabled'});
                if (dimensions.document > dimensions.viewport + 1) console.error(await page.evaluate(() => Array.from(document.querySelectorAll('body *')).filter(e => e.getBoundingClientRect().right > innerWidth + 1).slice(0,20).map(e => ({tag:e.tagName,cls:e.className,text:e.textContent.slice(0,100),width:e.getBoundingClientRect().width,right:e.getBoundingClientRect().right}))));
                assert(dimensions.document <= dimensions.viewport + 1, `${name} at ${width}: ${JSON.stringify(dimensions)}`);
                assert.deepEqual(errors, [], `${name} page errors`);
                results.push({name,width,...dimensions,pass:true});
                await page.close();
            }
        }
        console.log(JSON.stringify(results,null,2));
    } finally {
        fs.writeFileSync(path.join(output,'results.json'), JSON.stringify(results,null,2));
        await browser.close();
    }
})().catch(e => {console.error(e);process.exitCode=1;});
