// Exercise the actual matcher in each script with a synthetic /proc tree.
// Never execute the scripts' start/stop commands.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const jar = '/usr/java/jar/wesite-admin-1.0.0.jar';
const bash = process.env.BASH_TEST_BIN || (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');
const scenarios = [
  ['normal service', ['java', '-jar', jar, '--spring.profiles.active=prod'], 0],
  ['audit', ['java', '-jar', jar, '--spring.profiles.active=prod,blog-audit'], 1],
  ['sanitize', ['java', '-jar', jar, '--spring.profiles.active=blog-sanitize,prod'], 1],
  ['non-web', ['java', '-jar', jar, '--spring.main.web-application-type=none'], 1],
  ['JVM maintenance property', ['java', '-Dspring.profiles.active=prod,blog-audit', '-jar', jar], 1],
  ['split non-web option', ['java', '-jar', jar, '--spring.main.web-application-type', 'none'], 1],
  ['other jar with matching argument', ['java', '-jar', '/other.jar', jar], 1],
];
for (const name of ['admin-start.sh', 'admin-stop.sh']) {
  test(`${name} distinguishes service from maintenance`, () => {
    const source = fs.readFileSync(path.join(__dirname, name), 'utf8').replace(/\r\n/g, '\n');
    const matcher = source.match(/matches_admin\(\) \{[\s\S]*?\n\}/)[0].replaceAll('/proc/$1', './proc/$1');
    const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'blog-process-test-'));
    try {
      fs.mkdirSync(path.join(fixture, 'proc/123'), {recursive:true});
      fs.writeFileSync(path.join(fixture, 'match.sh'), `JAR=${jar}\nreadlink() { echo /usr/java/jdk/bin/java; }\n${matcher}\nmatches_admin 123\n`);
      for (const [label, args, expected] of scenarios) {
        fs.writeFileSync(path.join(fixture, 'proc/123/cmdline'), [...args, ''].join('\0'));
        const result = spawnSync(bash, ['match.sh'], {cwd:fixture, encoding:'utf8'});
        assert.equal(result.status, expected, `${label}: ${result.error || result.stderr}`);
      }
    } finally {
      fs.rmSync(fixture, {recursive:true, force:true});
    }
  });
}
