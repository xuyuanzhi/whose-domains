const assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});
 const base=process.env.ISSUE_TEST_BASE,token=process.env.ISSUE_TEST_TOKEN;
 try {
  const page=await browser.newPage({viewport:{width:1280,height:850}});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(({token})=>{
   const admin={req(options){fetch(options.url,{method:options.type.toUpperCase(),headers:{'access_token':token,'Content-Type':'application/json'},body:options.type==='post'?options.data:undefined}).then(async response=>{
    if(!response.ok){options.error({status:response.status});return;}options.success(await response.json());
   }).catch(()=>options.error({status:0}));}};
   window.layui={admin,use(modules,fn){fn();}};
  },{token});
  await page.goto(base+'/test-page');await page.getByRole('button',{name:'Lookup'}).click();
  await page.getByText('Unable to look up this domain. Please try again.').waitFor();
  assert.equal(await page.locator('#loader').evaluate(e=>e.classList.contains('active')),false);
  // Wait for the real client correlation request rather than sleeping for a guessed duration.
  await page.waitForResponse(r=>r.url().endsWith('/diagnostics/events') && r.status()===200);
  await page.goto(base+'/test-issues');
  async function waitCount(count) {
   await page.waitForFunction(async ({token,count})=>{
    const r=await fetch('/admin/issues/list?status=open',{headers:{access_token:token}});const body=await r.json();
    return body.data && body.data.items.some(item=>Number(item.occurrence_count)===count);
   },{token,count},{polling:100});
  }
  const first=page.locator('tbody button').first();await first.waitFor();await first.click();
  await page.getByText('客户端也观察到此请求失败',{exact:true}).waitFor();
  assert.equal(await page.locator('.issue-detail').innerText().then(t=>t.includes('sensitive information')),false);
  await page.getByLabel('处理状态').selectOption('resolved');await page.getByLabel('新增处理备注').fill('Browser verified');
  await page.getByRole('button',{name:'保存处理记录'}).click();await page.getByText('Browser verified',{exact:true}).waitFor();
  await page.getByRole('button',{name:'关闭',exact:true}).click();
  await page.getByText('当前筛选下暂无问题。',{exact:true}).waitFor();
  // A new server occurrence reopens the same issue.
  await page.request.post(base+'/domain/www.chinabbs.com/search');
  await waitCount(2);
  await page.getByRole('button',{name:'刷新',exact:true}).first().click();await first.waitFor();
  assert.equal(await page.locator('tbody tr').count(),1);
  assert.equal(await page.locator('tbody tr td').nth(3).innerText(),'2');
  await page.setViewportSize({width:390,height:844});await first.click();await page.getByRole('button',{name:'保存处理记录'}).waitFor();
  assert.ok(await page.locator('.issue-detail').evaluate(el=>el.getBoundingClientRect().width<=window.innerWidth));
  // Save with a stale version after a new occurrence: retain the draft and show conflict.
  await page.request.post(base+'/domain/www.chinabbs.com/search');
  await waitCount(3);
  await page.getByLabel('新增处理备注').fill('draft kept');await page.getByRole('button',{name:'保存处理记录'}).click();
  await page.getByText('问题已更新，请刷新详情后重新保存。',{exact:true}).waitFor();
  assert.equal(await page.getByLabel('新增处理备注').inputValue(),'draft kept');
  const denied=await page.request.get(base+'/admin/issues/list');assert.equal((await denied.json()).code,401);
  assert.deepEqual(errors,[]);
  console.log('Issue center browser: capture, association, resolution, recurrence, conflict, authorization and narrow viewport passed');
 } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
