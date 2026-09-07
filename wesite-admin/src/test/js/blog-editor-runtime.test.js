const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function editor() {
  const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/layuiadmin/views/blog/edit.html'), 'utf8');
  const elements = new Map();
  const requests = [];
  let confirmations = 0;
  function $(selector) {
    if (!elements.has(selector)) {
      elements.set(selector, {
        value: '', content: '', handlers: {}, props: {},
        val(value) { if (arguments.length) { this.value = value; return this; } return this.value; },
        text(value) { this.content = value; return this; },
        prop(key, value) { this.props[key] = value; return this; },
        attr() { return this; }, toggleClass() { return this; }, toggle(value) { this.visible = value; return this; }, hide() { this.visible = false; return this; },
        on(event, callback) { this.handlers[event] = callback; return this; }
      });
    }
    return elements.get(selector);
  }
  const layui = { $, admin: { req: request => requests.push(request) }, form: { render() {} },
    use(names, callback) { callback(); } };
  vm.runInNewContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], {
    layui, layer: { msg() {}, close() {}, confirm(text, done) { confirmations++; done(1); } },
    document: { getElementById() { return {}; } }
  });
  function respond(request, data) { request.done({ data }); request.complete(); }
  return { $, requests, respond, init: layui.blogEditor.init, confirmations: () => confirmations };
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
  e.respond(e.requests[1], { html: '<p>Preserved</p>' });
  e.init('archived');
  e.respond(e.requests[2], { id: 'archived', status: 0, content: '<p>Preserved</p>' });
  assert.equal(e.$('#blog-editor-form :input').props.disabled, false);
  assert.equal(e.$('#blog-save-button').visible, true);
  assert.equal(e.$('#blog-publish-button').visible, true);
});
