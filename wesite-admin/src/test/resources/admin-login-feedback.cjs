// Run the login script against controlled network and DOM boundaries.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const [configPath, viewPath, loginPath] = process.argv.slice(2);
function scenario(response, networkError = false, fields = {username: 'test-only', password: 'invalid'}) {
  const notices = [];
  const storage = {};
  const elements = {};
  function element(key) {
    return elements[key] ||= {
      disabled: false, value: '', events: {},
      prop(name, value) { if (arguments.length === 1) return this[name]; this[name] = value; return this; },
      toggleClass() { return this; },
      attr(name, value) { if (arguments.length === 1) return this[name]; this[name] = value; return this; },
      text(value) { this.value = value; return this; },
      on(name, handler) { this.events[name] = handler; return this; },
      trigger(name) { this.events[name]?.(); return this; }
    };
  }
  const button = element('button');
  let submit, requests = 0;
  const $ = selector => typeof selector === 'string' ? element(selector) : selector;
  $.extend = Object.assign;
  $.ajax = options => {
    requests++;
    if (networkError) options.error({status: 0}, 'error');
    else options.success(response);
    options.complete();
  };
  const layer = { open: options => notices.push(options.content), msg: text => notices.push(text) };
  const layui = {
    $: $, jquery: $, layer, cache: {base: ''},
    device: () => ({}), hint: () => ({}),
    define: (_, factory) => factory((name, value) => { layui[name] = value; }),
    use: (_, factory) => factory(),
    form: {on: (_, handler) => { submit = handler; }},
    router: () => ({search: {redirect: '%2Fdomain%2Flist'}}),
    data: (_, entry) => {
      if (entry) storage[entry.key] = entry.value;
      return storage;
    }
  };
  const location = {hash: '/user/login'};
  const context = vm.createContext({layui, layer, location});
  vm.runInContext(fs.readFileSync(configPath, 'utf8'), context);
  vm.runInContext(fs.readFileSync(viewPath, 'utf8'), context);
  layui.admin = {req: layui.view.req};
  const login = fs.readFileSync(loginPath, 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];
  vm.runInContext(login, context);
  submit({elem: button, field: fields});
  assert.equal(button.disabled, false, 'submission must be available again');
  assert.equal(notices.length, 0, 'login feedback must never open a popup or toast');
  return {feedback: element('#admin-login-feedback'), location, storage, requests, element};
}
const failure = scenario({code: 1, msg: '用户名或密码错误'});
assert.match(failure.feedback.value, /用户名或密码错误/);
assert.equal(failure.location.hash, '/user/login');
failure.element('#admin-login-password').trigger('input');
assert.equal(failure.feedback.value, '', 'editing credentials must clear stale feedback');
const network = scenario(null, true);
assert.match(network.feedback.value, /网络/);
assert.equal(network.location.hash, '/user/login');
const success = scenario({code: 0, data: {token: 'test-token'}});
assert.equal(success.feedback.value, '');
assert.equal(success.storage.access_token, 'test-token');
assert.equal(success.location.hash, '/domain/list');
const incomplete = scenario({code: 0, data: {}});
assert.ok(incomplete.feedback.value, 'missing token must not fail silently');
assert.equal(incomplete.location.hash, '/user/login');
for (const fields of [{username: '', password: ''}, {username: 'test-only', password: ''}]) {
  const empty = scenario(null, false, fields);
  assert.equal(empty.requests, 0, 'missing credentials must not send a request');
  assert.ok(empty.feedback.value);
}
console.log('Inline login feedback scenarios passed');
