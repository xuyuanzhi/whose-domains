// Real Chromium layout checks with isolated API fixtures; no database writes.
// PLAYWRIGHT_MODULE and CHROMIUM_EXECUTABLE may point to an existing local install.
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const output = process.env.VISUAL_OUTPUT || 'target/admin-visual';
fs.mkdirSync(output, {recursive: true});
function contrast(foreground, background) {
  const luminance = rgb => rgb.match(/[\d.]+/g).slice(0,3).map(Number).map(c=>c/255).map(c=>c<=.04045?c/12.92:((c+.055)/1.055)**2.4).reduce((sum,c,i)=>sum+c*[.2126,.7152,.0722][i],0);
  const a=luminance(foreground), b=luminance(background);
  return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);
}
const row = {
  id: 'visual-fixture', name: 'Example User', phoneNo: '13300000000', status: 1,
  statusText: '启用', createTimeText: '2026-09-06 20:47:22', updateTimeText: '2026-09-06 20:47:22',
  displayName: '.example', type: 'generic-restricted', orgName: 'Example Domain Registry Corporation',
  orgCountry: 'United Kingdom', countryName: 'United Kingdom', tldName: '.example',
  note: '用于检查较长字段的布局和横向滚动。', title: '域名安全监测与注册信息管理指南',
  slug: 'domain-security-and-registration-management-guide', category: '域名管理',
  publishDate: '2026-09-06T20:47:22.000+00:00', contentUpdatedAt: '2026-09-06T20:47:22.000+00:00',
  email: 'contact@example.com', subject: '域名注册信息咨询', message: '这是一条用于验证表格布局的较长消息。',
  requestIp: '2001:db8:1234:5678:abcd:1234:5678:90ab'
};
const rows = [row, {...row,id:'visual-fixture-2',status:0}, {...row,id:'visual-fixture-3',status:1}];
(async () => {
  const browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE});
  const context = await browser.newContext({viewport: {width: 1440, height: 1000}});
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => { errors.push(error.message); console.error(error.message); });
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== 'http://localhost:8082') return route.abort();
    let body;
    if (url.pathname === '/login') body = {code: 0, data: {token: 'visual-fixture-only', name: '布局检查'}};
    if (url.pathname === '/userInfo') body = {code: 0, data: {name: '布局检查'}};
    if (/^\/(user|domain\/tld|domain\/sld|blog|contact)\/list$/.test(url.pathname)) {
      body = {code: 0, count: rows.length, data: rows};
    }
    if (url.pathname === '/admin/contacts/list') body = {code: 0, data: {total: rows.length, records: rows}};
    if (url.pathname === '/admin/contacts/stats') body = {code: 0, data: {total: 1, pending: 1, processed: 0}};
    if (/^\/(user|domain\/tld|domain\/sld|blog)\/detail$/.test(url.pathname) || url.pathname === '/admin/contacts/visual-fixture') body = {code: 0, data: row};
    if (url.pathname === '/blog/preview') body = {code: 0, data: {html: '<p>文章预览布局检查</p>'}};
    if (url.pathname === '/dashboard/summary') body = {code: 0, data: {userCount: 1, tldCount: 1, sldCount: 1, recentBlogs: [], recentContacts: []}};
    if (body) return route.fulfill({json: body});
    if (!['GET','HEAD'].includes(route.request().method())) throw new Error('Unexpected mutation in layout test: ' + url.pathname);
    return route.continue();
  });
  try {
    await page.goto('http://localhost:8082/');
    await page.locator('#admin-login-username').fill('visual-fixture');
    await page.locator('#admin-login-password').fill('visual-fixture');
    await page.locator('button[lay-filter="admin-login-submit"]').click();
    await page.locator('#LAY-system-side-menu').waitFor();
    const results = [];
    for (const [width,height] of [[1440,1000],[1024,1000],[768,1000],[390,1000],[390,667],[844,390]]) {
      await page.setViewportSize({width, height});
      const size = height===1000 ? String(width) : width+'x'+height;
      for (const route of ['person/list', 'domain/tld/index', 'domain/sld/index', 'blog/list', 'contact/list']) {
        await page.goto('http://localhost:8082/#/' + route);
        const tableId = {'person/list':'LAY-user-manage','domain/tld/index':'LAY-domain-tlds','domain/sld/index':'LAY-domain-slds','blog/list':'blog-table','contact/list':'LAY-contact-list'}[route];
        await page.locator('.layui-table-view[lay-id="'+tableId+'"] .layui-table-main tbody tr[data-index="0"]').waitFor();
        await page.locator('.layui-table-main tbody tr[data-index="0"]').waitFor();
        await page.evaluate(() => document.fonts.ready);
        // Wait for Layui's resize/navigation transitions before measuring fixed columns.
        await page.waitForTimeout(350);
        const metrics = await page.evaluate(() => {
          const shown = element => element.getBoundingClientRect().width > 0 && element.getBoundingClientRect().height > 0;
          const rect = element => {const r = element.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height};};
          const buttons = [...document.querySelectorAll('.layuiadmin-card-header-auto .layui-btn')].filter(shown).map(button => {
            const icon = button.querySelector('.layui-icon');
            const text = [...button.childNodes].find(n => n.nodeType === 3 && n.textContent.trim());
            const range = document.createRange();
            if (text) range.selectNodeContents(text);
            const textRect = text ? range.getBoundingClientRect() : null;
            const iconRect = icon?.getBoundingClientRect();
            return {label: button.textContent.trim(), button: rect(button), iconPosition: icon && getComputedStyle(icon).position,
              overlap: !!(iconRect && textRect && iconRect.left < textRect.right && iconRect.right > textRect.left),
              inViewport: button.getBoundingClientRect().right <= innerWidth};
          });
          const actionCells = [...document.querySelectorAll('.layui-table-body tbody .layui-table-cell')].filter(shown).filter(cell=>cell.querySelector('button')).map(cell => {
            const buttons = [...cell.querySelectorAll('button')].filter(shown);
            const r = cell.getBoundingClientRect();
            const style = getComputedStyle(cell);
            return {width:r.width, clipped:buttons.some(b => {const br=b.getBoundingClientRect(); return br.right > r.right - parseFloat(style.paddingRight) + 1 || br.left < r.left - 1 || br.bottom > r.bottom + 1;}), labels:buttons.map(b=>b.textContent.trim())};
          });
          const headers = [...document.querySelectorAll('.layui-table-header .layui-table-cell')].filter(shown).filter(cell => cell.scrollWidth > cell.clientWidth + 1).map(cell=>cell.textContent.trim());
          const clippedDates = [...document.querySelectorAll('.layui-table-main td[data-field="publishDate"] .layui-table-cell, .layui-table-main td[data-field="contentUpdatedAt"] .layui-table-cell')].filter(shown).some(cell=>cell.scrollWidth > cell.clientWidth + 1);
          const badges = [...document.querySelectorAll('.layui-table-body .layui-badge')].filter(shown).map(b=>({text:b.textContent,color:getComputedStyle(b).color,background:getComputedStyle(b).backgroundColor}));
          const misalignedRows = [...document.querySelectorAll('.layui-table-fixed-r tbody tr')].filter(shown).some(tr=> {
            const main = document.querySelector('.layui-table-main tr[data-index="'+tr.dataset.index+'"]');
            return main && Math.abs(main.getBoundingClientRect().top-tr.getBoundingClientRect().top)>1;
          });
          return {buttons, actionCells, badges, misalignedRows, clippedDates, clippedHeaders:headers, pageOverflow:document.documentElement.scrollWidth > innerWidth + 1};
        });
        results.push({width, height, route, ...metrics});
        if (width === 1440 || width === 390) await page.screenshot({path:path.join(output, size+'-'+route.replaceAll('/','-')+'.png'),fullPage:true});
        if (width === 1440 || width === 390 || height < 600) {
          const action = route === 'contact/list' ? 'detail' : 'edit';
          const fixedButton = page.locator('.layui-table-fixed-r button[lay-event="'+action+'"]').first();
          const button = await fixedButton.isVisible() ? fixedButton : page.locator('.layui-table-main button[lay-event="'+action+'"]').first();
          await button.click();
          await page.locator('.layui-layer-page').waitFor({timeout: 5000}).catch(async error => {
            console.error('Popup failed:', width, route, await page.locator('.layui-layer').allTextContents());
            await page.screenshot({path:path.join(output,'popup-failure.png')});
            throw error;
          });
          await page.locator('.layui-layer-page input:not([type="hidden"]), .layui-layer-page .contact-detail').first().waitFor();
          await page.waitForTimeout(250);
          const popup = await page.locator('.layui-layer-page').evaluate(element => {
            const r = element.getBoundingClientRect();
            const content = element.querySelector('.layui-layer-content');
            return {offscreen:r.left < 0 || r.right > innerWidth + 1 || r.top < 0 || r.bottom > innerHeight + 1, overflow:content.scrollWidth > content.clientWidth + 1};
          });
          results[results.length-1].popup = popup;
          await page.screenshot({path:path.join(output, size+'-'+route.replaceAll('/','-')+'-popup.png'),fullPage:true});
          const save = page.locator('.layui-layer-page button:visible').last();
          if (await save.count()) {
            await save.scrollIntoViewIfNeeded();
            popup.saveReachable = await save.evaluate(b=>{const r=b.getBoundingClientRect();const c=b.closest('.layui-layer-content').getBoundingClientRect(); return r.top>=Math.max(0,c.top) && r.bottom<=Math.min(innerHeight,c.bottom)+1;});
          }
          await page.locator('.layui-layer-page .layui-layer-close, .layui-layer-page i[close]').click();
          if (route === 'person/list') {
            await page.locator('.layui-table-tool button[lay-event="add"]').click();
            const saveNew = page.locator('button[lay-filter="person-add-submit"]');
            await saveNew.scrollIntoViewIfNeeded();
            results[results.length-1].newUserReachable = await saveNew.evaluate(b=>{const r=b.getBoundingClientRect(); return r.left>=0 && r.right<=innerWidth && r.top>=0 && r.bottom<=innerHeight;});
            await page.locator('.layui-layer-page i[close]').click();
          }
        }
      }
    }
    fs.writeFileSync(path.join(output,'report.json'), JSON.stringify({results,errors},null,2));
    const failed = results.filter(r=>r.pageOverflow || r.clippedDates || r.clippedHeaders.length || r.buttons.some(b=>b.overlap || !b.inViewport) || r.actionCells.some(c=>c.clipped) || r.misalignedRows || r.badges.some(b=>contrast(b.color,b.background)<4.5) || r.popup?.offscreen || r.popup?.overflow || r.popup?.saveReachable === false || r.newUserReachable === false);
    console.log(JSON.stringify({checks:results.length, failures:failed, errors},null,2));
    if (failed.length || errors.length) process.exitCode = 1;
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
