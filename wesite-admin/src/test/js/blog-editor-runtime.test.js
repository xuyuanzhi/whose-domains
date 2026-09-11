const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function editor() {
  const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/layuiadmin/views/blog/edit.html'), 'utf8');
  const elements = new Map();
  const requests = [];
  const timers = [];
  const messages = [];
  let confirmations = 0;
  function $(selector) {
    if (!elements.has(selector)) {
      elements.set(selector, {
        value: '', content: '', handlers: {}, props: {},
        val(value) { if (arguments.length) { this.value = value; return this; } return this.value; },
        text(value) { this.content = value; return this; },
        focus() { this.focused = true; },
        prop(key, value) { this.props[key] = value; return this; },
        attr() { return this; }, toggleClass() { return this; }, toggle(value) { this.visible = value; return this; }, hide() { this.visible = false; return this; },
        on(event, callback) { this.handlers[event] = callback; return this; }
      });
    }
    return elements.get(selector);
  }
  const layui = { $, admin: { req: request => requests.push(request) }, form: { render() {} },
    use(names, callback) { callback(); } };
  function mount() { vm.runInNewContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], {
    layui, layer: { msg(text) { messages.push(text); }, close() {}, confirm(text, done) { confirmations++; done(1); } },
    setTimeout(callback) { timers.push(callback); },
    document: { getElementById(id) { return $('#' + id); } }
  }); }
  mount();
  function respond(request, data) { request.done({ data }); request.complete(); }
  return { $, requests, respond, timers, messages, init: layui.blogEditor.init, confirmations: () => confirmations,
    reopen() { elements.clear(); mount(); } };
}

test('blocked review shows untrusted titles as text and never saves or publishes', () => {
  const e = editor();
  e.$('#blog-publish-button').handlers.click();
  assert.equal(e.requests[0].url, '/blog/review');
  e.respond(e.requests[0], { publishable: false, wordCount: 12,
    blockers: [{ message: 'Need sources' }], warnings: [],
    matches: [{ title: '<img src=x onerror=alert(1)>', id: 'old', slug: 'old', kind: 'SAME_CONTENT' }] });
  assert.match(e.$('#blog-review').content, /Need sources/);
  assert.match(e.$('#blog-review').content, /<img/);
  assert.equal(e.confirmations(), 0);
  assert.equal(e.requests.length, 1);
  assert.equal(e.$('.blog-editor-action').props.disabled, false);
});

test('successful review requires confirmation then saves before publishing', () => {
  const e = editor();
  e.$('#blog-id').val('draft-1');
  e.$('#blog-title').val('Unsaved title');
  e.$('#blog-publish-button').handlers.click();
  assert.equal(JSON.parse(e.requests[0].data).title, 'Unsaved title');
  e.respond(e.requests[0], { publishable: true, wordCount: 300, blockers: [], warnings: [], matches: [] });
  assert.equal(e.confirmations(), 1);
  assert.equal(e.requests[1].url, '/blog/save');
  assert.equal(e.requests.length, 2);
  e.respond(e.requests[1], {});
  assert.equal(e.requests[2].url, '/blog/publish');
  assert.equal(JSON.parse(e.requests[2].data).id, 'draft-1');
  e.respond(e.requests[2], {});
  assert.equal(e.$('.blog-editor-action').props.disabled, false);
});

test('failed review request recovers controls without offering publication', () => {
  const e = editor();
  e.$('#blog-publish-button').handlers.click();
  e.requests[0].complete();
  assert.equal(e.confirmations(), 0);
  assert.equal(e.requests.length, 1);
  assert.equal(e.$('.blog-editor-action').props.disabled, false);
});

test('archived articles render read-only and restoring a draft re-enables editing', () => {
  const e = editor();
  e.init('archived');
  e.respond(e.requests[0], { id: 'archived', status: 2, content: '<p>Preserved</p>' });
  assert.equal(e.$('#blog-content').value, '<p>Preserved</p>');
  assert.equal(e.$('#blog-editor-form :input').props.disabled, true);
  assert.equal(e.$('#blog-save-button').visible, false);
  assert.equal(e.$('#blog-publish-button').visible, false);
  assert.equal(e.$('#blog-assist-controls').visible, false);
  e.respond(e.requests[1], { html: '<p>Preserved</p>' });
  e.init('archived');
  e.respond(e.requests[2], { id: 'archived', status: 0, content: '<p>Preserved</p>' });
  assert.equal(e.$('#blog-editor-form :input').props.disabled, false);
  assert.equal(e.$('#blog-save-button').visible, true);
  assert.equal(e.$('#blog-publish-button').visible, true);
});

function proposal() {
  return {article: {title:'Improved', summary:'Summary', content:'<h2>Steps</h2><p>Improved body</p>', metaTitle:'Meta', metaDescription:'Description'},
    before:{wordCount:20, blockers:[{message:'Missing source'}]}, after:{wordCount:250, blockers:[], matches:[]}, notes:'Review the evidence'};
}

test('rejected resubmission preserves recovery of the original optimization job', () => {
  const e = editor();
  e.$('#blog-optimize-button').handlers.click();
  e.respond(e.requests[0], {id:'original', status:'RUNNING'});
  e.timers.shift()(); e.requests[1].complete();
  e.$('#blog-optimize-button').handlers.click();
  e.requests[2].complete();
  assert.equal(e.$('#blog-resume-optimization').props.hidden, false);
  e.$('#blog-resume-optimization').handlers.click(); e.timers.shift()();
  assert.equal(JSON.parse(e.requests[3].data).id, 'original');
  e.respond(e.requests[3], {id:'original', status:'SUCCEEDED', result:proposal()});
  assert.equal(e.$('#blog-proposal').props.hidden, false);
});

test('late optimization response cannot mutate a newly mounted editor or unlock its controls', () => {
  const e = editor();
  e.$('#blog-id').val('A'); e.$('#blog-optimize-button').handlers.click();
  e.respond(e.requests[0], {id:'old', status:'RUNNING'});
  e.timers.shift()();
  e.reopen();
  e.$('#blog-id').val('B'); e.$('#blog-optimize-button').handlers.click();
  e.respond(e.requests[1], {id:'old', status:'SUCCEEDED', result:proposal()});
  assert.equal(e.$('#blog-proposal-summary').content, '');
  assert.equal(e.$('#blog-id').value, 'B');
  assert.equal(e.$('.blog-editor-action').props.disabled, true);
  e.respond(e.requests[2], {id:'new', status:'SUCCEEDED', result:proposal()});
  assert.equal(e.$('#blog-proposal').props.hidden, false);
  assert.equal(e.$('.blog-editor-action').props.disabled, false);
});

test('optimization is previewed without saving and only applied content is marked AI-assisted', () => {
  const e=editor(); e.$('#blog-id').val('post'); e.$('#blog-content').val('<p>Original</p>');
  e.$('#blog-optimize-button').handlers.click();
  assert.equal(e.requests[0].url,'/blog/assist/optimize');
  e.respond(e.requests[0],{id:'job',status:'SUCCEEDED',result:proposal()});
  assert.equal(e.$('#blog-content').value,'<p>Original</p>');
  assert.equal(e.requests.length,1);
  assert.equal(e.$('#blog-proposal').props.hidden,false);
  e.$('#blog-apply-proposal').handlers.click();
  assert.match(e.$('#blog-content').value,/Improved body/);
  assert.equal(e.requests[1].url,'/blog/preview');
  e.respond(e.requests[1],{html:'safe'});
  e.$('#blog-save-button').handlers.click();
  assert.equal(e.requests[2].url,'/blog/save');
  assert.equal(JSON.parse(e.requests[2].data).aiAssisted,true);
});

test('edits made while AI runs cannot be overwritten by a stale proposal', () => {
  const e=editor(); e.$('#blog-content').val('Original');
  e.$('#blog-optimize-button').handlers.click();
  e.$('#blog-content').val('User changed content');
  e.respond(e.requests[0],{id:'job',status:'SUCCEEDED',result:proposal()});
  e.$('#blog-apply-proposal').handlers.click();
  assert.equal(e.$('#blog-content').value,'User changed content');
  assert.equal(e.requests.length,1);
  assert.match(e.messages[0],/原文已修改/);
});

test('failed polling recovers controls and resume polls the same job without another AI request', () => {
  const e=editor(); e.$('#blog-optimize-button').handlers.click();
  e.respond(e.requests[0],{id:'job',status:'RUNNING',message:'Working'});
  e.timers.shift()(); e.requests[1].complete();
  assert.equal(e.$('.blog-editor-action').props.disabled,false);
  assert.equal(e.$('#blog-resume-optimization').props.hidden,false);
  e.$('#blog-resume-optimization').handlers.click(); e.timers.shift()();
  assert.equal(e.requests[2].url,'/blog/assist/job');
  assert.equal(JSON.parse(e.requests[2].data).id,'job');
  e.respond(e.requests[2],{id:'job',status:'FAILED',message:'AI unavailable'});
  assert.equal(e.$('#blog-optimize-state').content,'AI unavailable');
  assert.equal(e.$('.blog-editor-action').props.disabled,false);
});

test('discard leaves article unchanged and serializer isPublishable spelling is accepted', () => {
  const e=editor();e.$('#blog-content').val('Original');
  e.$('#blog-optimize-button').handlers.click();
  e.respond(e.requests[0],{id:'job',status:'SUCCEEDED',result:proposal()});
  e.$('#blog-discard-proposal').handlers.click();
  assert.equal(e.$('#blog-content').value,'Original');
  e.$('#blog-publish-button').handlers.click();
  e.respond(e.requests[1],{isPublishable:true,wordCount:300,blockers:[],matches:[],warnings:[]});
  assert.equal(e.requests[2].url,'/blog/save');
  assert.equal(JSON.parse(e.requests[2].data).aiAssisted,false);
});

test('missing AI configuration disables optimization but leaves review and save available', () => {
  const e = editor();
  e.init('post');
  e.respond(e.requests[0], {id: 'post', status: 0, content: 'Original'});
  const capability = e.requests.find(r => r.url === '/blog/assist/capabilities');
  assert.ok(capability, 'editor should check AI configuration');
  e.respond(capability, {optimizationEnabled: false});
  e.respond(e.requests.find(r => r.url === '/blog/preview'), {html: 'Original'});
  assert.equal(e.$('#blog-optimize-button').props.disabled, true);
  assert.equal(e.$('.blog-editor-action').props.disabled, false);
  assert.match(e.$('#blog-optimize-state').content, /DEEPSEEK_API_KEY/);
  e.$('#blog-optimize-button').handlers.click();
  assert.equal(e.requests.filter(r => r.url === '/blog/assist/optimize').length, 0);
});

test('configuration lookup failure explains recovery without blocking article editing', () => {
  const e = editor();
  e.init('post');
  e.respond(e.requests[0], {id: 'post', status: 0, content: 'Original'});
  const capability = e.requests.find(r => r.url === '/blog/assist/capabilities');
  assert.ok(capability);
  capability.complete();
  e.respond(e.requests.find(r => r.url === '/blog/preview'), {html: 'Original'});
  assert.match(e.$('#blog-optimize-state').content, /重新打开/);
  assert.equal(e.$('#blog-optimize-button').props.disabled, true);
  assert.equal(e.$('.blog-editor-action').props.disabled, false);
});

test('optimization entry focuses its controls and reviews before the user starts AI generation', () => {
  const e = editor();
  e.init('post', null, true, true);
  e.respond(e.requests[0], {id: 'post', status: 0, content: 'Original'});
  assert.equal(e.$('#blog-optimize-heading').focused, true);
  assert.equal(e.requests.filter(r => r.url === '/blog/assist/optimize').length, 0);
  e.respond(e.requests.find(r => r.url === '/blog/assist/capabilities'), {optimizationEnabled: true});
  e.respond(e.requests.find(r => r.url === '/blog/preview'), {html: 'Original'});
  e.respond(e.requests.find(r => r.url === '/blog/review'), {wordCount: 20, blockers: [], matches: []});
  assert.equal(e.$('#blog-optimize-button').props.disabled, false);
  e.$('#blog-optimize-focus').val('补充排查步骤');
  e.$('#blog-optimize-button').handlers.click();
  const request = e.requests.at(-1);
  assert.equal(request.url, '/blog/assist/optimize');
  assert.equal(JSON.parse(request.data).focus, '补充排查步骤');
  assert.equal(JSON.parse(request.data).article.content, 'Original');
});
