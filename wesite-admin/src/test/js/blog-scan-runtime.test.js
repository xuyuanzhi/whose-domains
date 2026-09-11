const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
function page(){
  const elements=new Map(),requests=[],renders=[],timers=[],events={},editors=[];
  const $=selector=>{
    if(!elements.has(selector))elements.set(selector,{
      value:'',content:'',props:{},state:{},handlers:{},
      val(v){if(arguments.length){this.value=v;return this;}return this.value;},
      text(v){this.content=v;return this;},prop(k,v){this.props[k]=v;return this;},
      data(k,v){if(arguments.length>1){this.state[k]=v;return this;}return this.state[k];},
      attr(){return this;},toggleClass(){return this;},on(k,f){this.handlers[k]=f;return this;}
    });return elements.get(selector);
  };
  const layui={$,
    admin:{req:r=>requests.push(r),popup(options){options.success.call(options);}},
    view(){return {render(){return {done(callback){callback();}};}};},
    blogEditor:{init(...args){editors.push(args);}},
    table:{render:r=>renders.push(r),on:(n,f)=>events[n]=f,reload(){}},
    form:{render(){},on(){}},util:{escape:s=>s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;')},
    use(n,f){f();}
  };
  const html=fs.readFileSync(path.join(__dirname,'../../main/resources/static/layuiadmin/views/blog/list.html'),'utf8');
  vm.runInNewContext(html.match(/<script>([\s\S]*?)<\/script>/)[1],{layui,document:{getElementById(id){return $('#'+id);}},setTimeout:f=>timers.push(f)});
  return {$,requests,renders,timers,events,editors,respond(r,data){r.done({data});r.complete();}};
}
const findings=[{id:'one',title:'<img src=x onerror=alert(1)>',status:1,review:{wordCount:57,blockers:[{code:'THIN_CONTENT',message:'<b>Short</b>'}],matches:[]}},
  {id:'two',title:'Other',status:0,review:{wordCount:900,blockers:[{code:'MISSING_SOURCE',message:'Source'}],matches:[]}}];
test('scan result renders counts, escapes article text and filters without another server scan',()=>{
  const p=page();p.$('#blog-scan-button').handlers.click();
  assert.equal(p.requests[0].url,'/blog/assist/scan');
  p.respond(p.requests[0],{id:'scan',status:'SUCCEEDED',message:'Done',result:{scanned:3,affected:2,issues:{THIN_CONTENT:1,MISSING_SOURCE:1},findings,topics:[]}});
  assert.match(p.$('#blog-audit-summary').content,/扫描 3 篇/);
  const table=p.renders.at(-1);assert.equal(table.data.length,2);
  assert.match(table.cols[0][0].templet(findings[0]),/&lt;img/);
  assert.match(table.cols[0][3].templet(findings[0]),/&lt;b/);
  p.$('#blog-audit-filter').val('THIN_CONTENT');p.$('#blog-audit-filter').handlers.change();
  assert.equal(p.renders.at(-1).data.length,1);assert.equal(p.requests.length,1);
});
test('scan progress and failed polling can resume without starting another scan',()=>{
  const p=page();p.$('#blog-scan-button').handlers.click();
  p.respond(p.requests[0],{id:'scan',status:'RUNNING',message:'Checking',completed:3,total:48});
  assert.match(p.$('#blog-audit-state').content,/3\/48/);
  p.timers.shift()();p.requests[1].complete();
  assert.equal(p.$('#blog-scan-resume').props.hidden,false);
  p.$('#blog-scan-resume').handlers.click();p.timers.shift()();
  assert.equal(p.requests[2].url,'/blog/assist/job');
  p.respond(p.requests[2],{id:'scan',status:'FAILED',message:'Database unavailable'});
  assert.equal(p.$('#blog-audit-state').content,'Database unavailable');
  assert.equal(p.$('#blog-scan-button').props.disabled,false);
});

test('separate review and optimize actions open the corresponding editor mode without generating',()=>{
  const p=page();
  const tr={find(selector){return selector;}};
  p.events['tool(blog-table)']({event:'review',data:{id:'one'},tr});
  p.events['tool(blog-table)']({event:'optimize',data:{id:'two'},tr});
  assert.equal(p.editors[0][0],'one');
  assert.equal(p.editors[0][2],true);
  assert.equal(p.editors[0][3],undefined);
  assert.equal(p.editors[1][0],'two');
  assert.equal(p.editors[1][2],true);
  assert.equal(p.editors[1][3],true);
  assert.equal(p.requests.length,0);
});
