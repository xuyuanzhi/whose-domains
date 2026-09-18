const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const {webcrypto} = require('node:crypto');
const source = fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/js/diagnostics.js'),'utf8');
function harness(fetchImpl, enabled=true) {
    const timers=[], reports=[], listeners={};
    class XHR extends EventTarget {
        open(method,url){this.method=method;this.url=url;}
        send(){this.status=503;this.dispatchEvent(new Event('load'));this.dispatchEvent(new Event('loadend'));}
        getResponseHeader(){return 'b88d846c-76d7-4d10-8642-470f77f5b000';}
    }
    const window={location:{href:'https://example.test/domain/private.example?token=secret',origin:'https://example.test',pathname:'/domain/private.example'},crypto:webcrypto,
        __diagnostics:{enabled,routes:['/domain/{name}','/domain/{name}/search'],scripts:['/static/js/common.js']},
        fetch:async (...args)=>{if(args[0]==='/diagnostics/events'){reports.push(JSON.parse(args[1].body));return {status:200};}return fetchImpl(...args);},
        XMLHttpRequest:XHR,addEventListener:(key,fn)=>listeners[key]=fn};
    const context=vm.createContext({window,XMLHttpRequest:XHR,URL,WeakSet,WeakMap,AbortController,Uint8Array,
        setTimeout:(fn,ms)=>{timers.push({fn,ms});return timers.length;},clearTimeout(){},console});
    vm.runInContext(source,context);
    const flush=async()=>{const work=timers.filter(t=>t.ms===1000);timers.length=0;for(const t of work)t.fn();await Promise.resolve();};
    return {window,reports,listeners,flush,XHR};
}
test('fetch preserves response and headers, normalizes dynamic route, associates request ID',async()=>{
    const response={status:500,headers:{get:()=> 'b88d846c-76d7-4d10-8642-470f77f5b000'}};
    let originalArgs;const h=harness(async(...args)=>{originalArgs=args;return response;});
    const init={method:'POST',headers:{'X-Api-Token':'secret'}};
    assert.equal(await h.window.fetch('/domain/private.example/search?api_key=secret',init),response);assert.equal(originalArgs[1],init);
    await h.flush();const e=h.reports[0].events[0];assert.equal(e.route,'/domain/{name}/search');assert.equal(e.requestId,'b88d846c-76d7-4d10-8642-470f77f5b000');
    assert.ok(!JSON.stringify(h.reports).includes('secret'));assert.ok(!JSON.stringify(h.reports).includes('private.example'));
});
test('network failure rethrows identical error without duplicate unhandled rejection',async()=>{
    const error=new TypeError('secret URL');const h=harness(async()=>{throw error;});
    await assert.rejects(h.window.fetch('/domain/private.example'),e=>e===error);
    h.listeners.unhandledrejection({reason:error});await h.flush();assert.equal(h.reports[0].events.length,1);assert.equal(h.reports[0].events[0].code,'NETWORK_FAILURE');
});
test('cancellation, third party failure, and diagnostics failure are ignored',async()=>{
    const error=new Error('cancel');error.name='AbortError';const h=harness(async()=>{throw error;});
    await assert.rejects(h.window.fetch('/domain/private.example'));await h.flush();assert.equal(h.reports.length,0);
    const foreign=harness(async()=>({status:500,headers:{get:()=>null}}));await foreign.window.fetch('https://ads.example/pixel');await foreign.flush();assert.equal(foreign.reports.length,0);
});
test('XHR/jQuery 5xx collected without changing open or send',async()=>{
    const h=harness(async()=>({status:200}));const xhr=new h.XHR();xhr.open('POST','/domain/private.example/search');xhr.send();await h.flush();
    assert.equal(h.reports[0].events[0].status,503);assert.equal(xhr.method,'POST');
});
test('self-hosted JS exception keeps only allowed position and error type',async()=>{
    const h=harness(async()=>({status:200}));const error=new TypeError('Authorization: secret');
    h.listeners.error({error,filename:'https://example.test/static/js/common.js?v=secret',lineno:12,colno:2});
    h.listeners.error({error:new Error('ad'),filename:'https://ads.example/ad.js',lineno:1,colno:1});
    await h.flush();assert.equal(h.reports[0].events.length,1);assert.equal(h.reports[0].events[0].script,'/static/js/common.js');assert.ok(!JSON.stringify(h.reports).includes('secret'));
});
test('queue is bounded and each batch has at most ten events',async()=>{
    const h=harness(async()=>({status:503,headers:{get:()=>null}}));
    for(let i=0;i<100;i++)await h.window.fetch('/domain/private.example');
    await h.flush();await h.flush();assert.equal(h.reports.length,2);assert.equal(h.reports.reduce((n,r)=>n+r.events.length,0),20);
});
test('disabled diagnostics leave fetch untouched',async()=>{
    const h=harness(async()=>({status:503}),false);assert.equal(h.window.__diagnosticsInstalled,undefined);await h.window.fetch('/domain/private.example');await h.flush();assert.equal(h.reports.length,0);
});
