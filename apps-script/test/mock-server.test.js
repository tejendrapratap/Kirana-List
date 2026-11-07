'use strict';
const { describe, it, before, after } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const http = require('node:http');
const { createMockServer, resolveCodePath, EXEC_PATH, ECHO_PATH, V2_CODE_GS_PATH, V3_CODE_GS_PATH } = require('./mock-server');
const { CODE_GS_PATH } = require('./fake-gas');

const TOKEN = 'mock-test-token';

function request(method, url, { body, headers = {} } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(url, { method, headers }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString('utf8') }));
    });
    req.on('error', reject);
    if (body !== undefined) req.write(body);
    req.end();
  });
}

/** Like OkHttp: follow the 302 from /exec with a GET. Returns both hops. */
async function callExec(method, url, options) {
  const first = await request(method, url, options);
  if (first.status !== 302) return { first, final: first };
  return { first, final: await request('GET', first.headers.location) };
}

describe('mock web app server', () => {
  const logLines = [];
  let mock;
  let base;
  let execUrl;

  before(async () => {
    mock = createMockServer({ token: TOKEN, log: (line) => logLines.push(line) });
    const { port, url } = await mock.listen(0);
    base = `http://127.0.0.1:${port}`;
    execUrl = url;
  });
  after(() => mock.close());

  const get = (params) => callExec('GET', `${execUrl}?${new URLSearchParams({ token: TOKEN, ...params })}`);
  const post = (payload) => callExec('POST', execUrl, {
    body: JSON.stringify(payload),
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });

  it('answers /exec with a 302 to a same-host echo URL that returns the JSON output', async () => {
    const { first, final } = await get({ action: 'ping' });
    assert.equal(first.status, 302);
    assert.ok(first.headers.location.startsWith(`${base}${ECHO_PATH}?user_content_key=`));
    assert.equal(final.status, 200);
    assert.match(final.headers['content-type'], /^application\/json/);
    assert.deepEqual(JSON.parse(final.body), { ok: true, app: 'grocery-sync', version: 3, spreadsheet: 'Grocery Sync (mock)' });
  });

  it('builds the redirect from the Host header (e.g. the Android emulator alias)', async () => {
    const res = await request('GET', `${execUrl}?action=ping&token=${TOKEN}`, { headers: { Host: '10.0.2.2:8787' } });
    assert.equal(res.status, 302);
    assert.ok(res.headers.location.startsWith(`http://10.0.2.2:8787${ECHO_PATH}?user_content_key=`));
  });

  it('applies changes over POST and serves them to list, debug and hand edits', async () => {
    const id = crypto.randomUUID();
    const { first, final } = await post({
      token: TOKEN,
      action: 'apply',
      device: 'Home',
      changes: [{ id, op: 'upsert', name: 'Milk', type: 'boolean', value: true, time: Date.now() }],
    });
    assert.equal(first.status, 302);
    const applied = JSON.parse(final.body);
    assert.equal(applied.ok, true);
    assert.deepEqual(applied.results, [{ id, status: 'applied' }]);

    const listed = JSON.parse((await get({ action: 'list' })).final.body);
    assert.equal(listed.revision, applied.revision);
    assert.deepEqual(listed.items.map((i) => [i.name, i.value, i.updatedBy]), [['Milk', true, 'Home']]);
    const unchanged = JSON.parse((await get({ action: 'list', since: listed.revision })).final.body);
    assert.equal(unchanged.unchanged, true);

    const dump = JSON.parse((await request('GET', `${base}/debug/sheets`)).body);
    const items = dump.sheets.find((s) => s.name === 'Items');
    assert.deepEqual(items.values[1].slice(0, 4), ['Milk', true, '', 'boolean']);
    assert.match(items.values[1][4], /^\d{4}-\d{2}-\d{2}T/);
    assert.equal(items.validations[1][1], 'CHECKBOX');
    assert.equal(dump.sheets.find((s) => s.name === '_Tombstones').hidden, true);

    const edit = await request('POST', `${base}/debug/hand-edit`, { body: JSON.stringify({ row: 2, col: 2, value: false }) });
    assert.equal(edit.status, 200);
    assert.equal(JSON.parse(edit.body).range, 'B2');
    const afterEdit = JSON.parse((await get({ action: 'list', since: listed.revision })).final.body);
    assert.deepEqual(afterEdit.items.map((i) => [i.name, i.value, i.updatedBy]), [['Milk', false, 'Sheet']]);
  });

  it('rejects malformed hand edits', async () => {
    const res = await request('POST', `${base}/debug/hand-edit`, { body: JSON.stringify({ row: 0, col: 2, value: 1 }) });
    assert.equal(res.status, 400);
    assert.equal(JSON.parse(res.body).ok, false);
  });

  it('answers 404 for an unknown deployment or echo key', async () => {
    const wrongDeployment = await request('GET', `${base}/macros/s/OTHER/exec?action=ping&token=${TOKEN}`);
    assert.equal(wrongDeployment.status, 404);
    assert.match(wrongDeployment.headers['content-type'], /^text\/html/);
    assert.equal((await request('GET', `${base}${ECHO_PATH}?user_content_key=nope`)).status, 404);
  });

  it('reports protocol errors in the JSON body with HTTP 200', async () => {
    const { final } = await get({ action: 'ping', token: 'wrong' });
    assert.equal(final.status, 200);
    assert.deepEqual(JSON.parse(final.body), { ok: false, error: 'unauthorized' });
  });

  it('can imitate a deployment that is not shared with "Anyone"', async () => {
    const set = await request('POST', `${base}/debug/fail-mode`, { body: JSON.stringify({ mode: 'html' }) });
    assert.equal(set.status, 200);
    try {
      const { first } = await get({ action: 'ping' });
      assert.equal(first.status, 200);
      assert.match(first.headers['content-type'], /^text\/html/);
      assert.match(first.body, /Sign in/);
    } finally {
      await request('POST', `${base}/debug/fail-mode`, { body: JSON.stringify({ mode: '' }) });
    }
    assert.equal((await get({ action: 'ping' })).first.status, 302);
  });

  const debugPost = async (path, payload) => {
    const res = await request('POST', `${base}${path}`, { body: JSON.stringify(payload) });
    return { status: res.status, body: JSON.parse(res.body) };
  };

  it('adds and clears a data validation rule that makes the sheet refuse changes', async () => {
    const add = await debugPost('/debug/validation', {
      sheet: 'Items', column: 4, values: ['Boolean', 'Integer'], reject: true, helpText: 'Choose Boolean or Integer.',
    });
    assert.equal(add.status, 200);
    assert.equal(add.body.ok, true);
    assert.match(add.body.range, /^D2:D\d+$/);

    const change = { id: crypto.randomUUID(), op: 'upsert', name: 'Rice', type: 'integer', value: 2, time: Date.now() };
    const refused = JSON.parse((await post({ token: TOKEN, action: 'apply', device: 'Home', changes: [change] })).final.body);
    assert.equal(refused.ok, true);
    assert.deepEqual(refused.results, [{
      id: change.id,
      status: 'error',
      error: 'sheet_rejected: Choose Boolean or Integer. (data validation on the Type column of the Items sheet)',
    }]);
    const listed = JSON.parse((await get({ action: 'list' })).final.body);
    assert.equal(listed.ok, true, 'list keeps working');
    assert.ok(!listed.items.some((i) => i.name === 'Rice'));
    const dump = JSON.parse((await request('GET', `${base}/debug/sheets`)).body);
    assert.equal(dump.sheets.find((s) => s.name === 'Items').validations[1][3], 'VALUE_IN_LIST');

    const clear = await debugPost('/debug/validation', { clear: true });
    assert.equal(clear.status, 200);
    assert.ok(clear.body.cleared > 0);
    const applied = JSON.parse((await post({ token: TOKEN, action: 'apply', device: 'Home', changes: [change] })).final.body);
    assert.deepEqual(applied.results, [{ id: change.id, status: 'applied' }], 'the same change goes through once the rule is gone');
    const afterClear = JSON.parse((await request('GET', `${base}/debug/sheets`)).body);
    const items = afterClear.sheets.find((s) => s.name === 'Items');
    assert.ok(items.validations.every((row) => row[3] === null), 'no rule left on Type');
  });

  it('rejects malformed validation requests', async () => {
    for (const payload of [
      { column: 0, values: ['a'] },
      { column: 3, values: [] },
      { column: 3, values: ['a'], reject: 'yes' },
      { sheet: 'Nope', column: 3, values: ['a'] },
      { column: 3, values: ['a'], helpText: 5 },
    ]) {
      const res = await debugPost('/debug/validation', payload);
      assert.equal(res.status, 400, JSON.stringify(payload));
      assert.equal(res.body.ok, false);
    }
  });

  it('finds a validation column by its title in row 1', async () => {
    const add = await debugPost('/debug/validation', { column: 'type', values: ['Boolean', 'Integer'], helpText: 'Choose Boolean or Integer.' });
    assert.equal(add.status, 200);
    assert.match(add.body.range, /^D2:D\d+$/, 'Type is column D next to the Unit column');
    try {
      const change = { id: crypto.randomUUID(), op: 'upsert', name: 'Oats', type: 'quantity', value: 0.5, unit: 'kg', time: Date.now() };
      const refused = JSON.parse((await post({ token: TOKEN, action: 'apply', device: 'Home', protocol: 3, changes: [change] })).final.body);
      assert.equal(refused.version, 3);
      assert.deepEqual(refused.results, [{
        id: change.id,
        status: 'error',
        error: 'sheet_rejected: Choose Boolean or Integer. (data validation on the Type column of the Items sheet)',
      }]);
    } finally {
      await debugPost('/debug/validation', { clear: true });
    }
    const unknown = await debugPost('/debug/validation', { column: 'Colour', values: ['a'] });
    assert.equal(unknown.status, 400);
    assert.match(unknown.body.error, /no column titled "Colour"/);
  });

  it('serves units to an app that speaks version 3', async () => {
    const change = { id: crypto.randomUUID(), op: 'upsert', name: 'Milk', type: 'quantity', value: 1.5, unit: 'litres', time: Date.now() };
    const applied = JSON.parse((await post({ token: TOKEN, action: 'apply', device: 'Home', protocol: 3, changes: [change] })).final.body);
    assert.deepEqual(applied.results, [{ id: change.id, status: 'applied' }]);
    const v3 = JSON.parse((await get({ action: 'list', v: '3' })).final.body);
    assert.equal(v3.version, 3);
    const milk = v3.items.find((i) => i.name === 'Milk');
    assert.deepEqual([milk.type, milk.value, milk.unit], ['quantity', 1.5, 'L']);
    const v2 = JSON.parse((await get({ action: 'list' })).final.body);
    assert.equal('version' in v2, false);
    assert.deepEqual(v2.items.find((i) => i.name === 'Milk').value, 2);
    const items = JSON.parse((await request('GET', `${base}/debug/sheets`)).body).sheets.find((s) => s.name === 'Items');
    assert.deepEqual(items.values[0].slice(0, 6), ['Item', 'Need', 'Unit', 'Type', 'Updated At', 'Updated By']);
    const script = JSON.parse((await request('GET', `${base}/debug/script`)).body);
    assert.deepEqual([script.ok, script.path, script.version], [true, CODE_GS_PATH, 3]);
  });

  it('can imitate an old deployment whose applies fail with internal while ping and list work', async () => {
    const set = await debugPost('/debug/fail-mode', { mode: 'apply_internal', detail: 'Choose Boolean or Integer.' });
    assert.deepEqual(set, { status: 200, body: { ok: true, mode: 'apply_internal', detail: 'Choose Boolean or Integer.' } });
    try {
      const change = { id: crypto.randomUUID(), op: 'upsert', name: 'Salt', type: 'boolean', value: true, time: Date.now() };
      const { first, final } = await post({ token: TOKEN, action: 'apply', device: 'Home', changes: [change] });
      assert.equal(first.status, 302, 'the same redirect dance as a real answer');
      assert.match(final.headers['content-type'], /^application\/json/);
      assert.deepEqual(JSON.parse(final.body), { ok: false, error: 'internal: Choose Boolean or Integer.' });
      const wrongToken = JSON.parse((await post({ token: 'wrong', action: 'apply', changes: [change] })).final.body);
      assert.deepEqual(wrongToken, { ok: false, error: 'unauthorized' });

      assert.equal(JSON.parse((await get({ action: 'ping' })).final.body).ok, true);
      const listed = JSON.parse((await get({ action: 'list' })).final.body);
      assert.equal(listed.ok, true);
      assert.ok(!listed.items.some((i) => i.name === 'Salt'), 'nothing was applied');

      const defaultDetail = await debugPost('/debug/fail-mode', { mode: 'apply_internal' });
      assert.equal(defaultDetail.body.detail, 'Choose Boolean or Integer.');
      assert.equal((await debugPost('/debug/fail-mode', { mode: 'apply_internal', detail: 42 })).status, 400);
      assert.equal((await debugPost('/debug/fail-mode', { mode: 'nope' })).status, 400);
    } finally {
      await debugPost('/debug/fail-mode', { mode: '' });
    }
    const change = { id: crypto.randomUUID(), op: 'upsert', name: 'Salt', type: 'boolean', value: true, time: Date.now() };
    const applied = JSON.parse((await post({ token: TOKEN, action: 'apply', device: 'Home', changes: [change] })).final.body);
    assert.deepEqual(applied.results, [{ id: change.id, status: 'applied' }]);
  });

  it('logs one line per request without the token', () => {
    assert.ok(logLines.length > 0);
    assert.ok(logLines.every((line) => !line.includes(TOKEN)));
    assert.ok(logLines.some((line) => line.includes(`GET ${EXEC_PATH}?action=ping&token=***`)));
  });
});

describe('mock web app serving the script from before units (CODE_GS=v2)', () => {
  let mock;
  let base;
  let execUrl;

  before(async () => {
    mock = createMockServer({ token: TOKEN, codeGs: 'v2', log: () => {} });
    const { port, url } = await mock.listen(0);
    base = `http://127.0.0.1:${port}`;
    execUrl = url;
  });
  after(() => mock.close());

  const get = async (params) => JSON.parse((await callExec('GET', `${execUrl}?${new URLSearchParams({ token: TOKEN, ...params })}`)).final.body);
  const post = async (payload) => JSON.parse((await callExec('POST', execUrl, {
    body: JSON.stringify({ token: TOKEN, action: 'apply', device: 'Home', ...payload }),
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  })).final.body);
  const debug = async (method, path, payload) => {
    const res = await request(method, `${base}${path}`, payload === undefined ? {} : { body: JSON.stringify(payload) });
    return { status: res.status, body: JSON.parse(res.body) };
  };
  const itemsSheet = async () => (await debug('GET', '/debug/sheets')).body.sheets.find((s) => s.name === 'Items');

  it('answers like the old script: version 2, no units, the old sheet layout', async () => {
    assert.deepEqual(await get({ action: 'ping', v: '3' }), { ok: true, app: 'grocery-sync', version: 2, spreadsheet: 'Grocery Sync (mock)' });
    const script = await debug('GET', '/debug/script');
    assert.equal(script.body.version, 2);
    assert.ok(script.body.path.endsWith(V2_CODE_GS_PATH.split(/[\\/]/).pop()));
    assert.equal('version' in await get({ action: 'list', v: '3' }), false);
    assert.deepEqual((await itemsSheet()).values[0], ['Item', 'Need', 'Type', 'Updated At', 'Updated By']);
  });

  it('seeds the old layout, then serves the new script: version 3, units and the Unit column on the first change', async () => {
    const seeded = await debug('POST', '/debug/seed-v2', {
      items: [['Milk', true, 'boolean'], ['Rice', 2, 'integer', Date.now() - 60000, 'Anna'], { name: 'Eggs', need: 0, type: 'integer' }],
      history: [[Date.now() - 60000, 'Rice', 'added', 2, 'integer', 'Anna', 'seed-1']],
    });
    assert.deepEqual(seeded, { status: 200, body: { ok: true, layout: 'v2', items: 3, history: 1 } });
    const old = await get({ action: 'list' });
    assert.deepEqual(old.items.map((i) => [i.name, i.type, i.value]), [['Milk', 'boolean', true], ['Rice', 'integer', 2], ['Eggs', 'integer', 0]]);
    const blocked = await post({ protocol: 3, changes: [{ id: crypto.randomUUID(), op: 'upsert', name: 'Oil', type: 'quantity', value: 1, unit: 'L', time: Date.now() }] });
    assert.equal(blocked.results[0].status, 'error', 'the old script does not know quantities');
    assert.equal('version' in blocked, false);

    const swapped = await debug('POST', '/debug/script', { script: 'current' });
    assert.equal(swapped.status, 200);
    assert.equal(swapped.body.version, 3);
    const listed = await get({ action: 'list', v: '3' });
    assert.equal(listed.version, 3);
    assert.deepEqual(listed.items.map((i) => [i.name, i.type, i.value, i.unit]),
      [['Milk', 'boolean', true, undefined], ['Rice', 'quantity', 2, ''], ['Eggs', 'quantity', 0, '']]);
    assert.deepEqual((await get({ action: 'list' })).revision, old.revision, 'an old app sees no change');
    assert.deepEqual((await itemsSheet()).values[0], ['Item', 'Need', 'Type', 'Updated At', 'Updated By'], 'not migrated by a list');

    const change = { id: crypto.randomUUID(), op: 'upsert', name: 'Rice', type: 'quantity', value: 1.5, unit: 'kg', time: Date.now() };
    const applied = await post({ protocol: 3, changes: [change] });
    assert.deepEqual([applied.version, applied.results], [3, [{ id: change.id, status: 'applied' }]]);
    const items = await itemsSheet();
    assert.deepEqual(items.values[0], ['Item', 'Need', 'Unit', 'Type', 'Updated At', 'Updated By', 'Added At']);
    assert.deepEqual(items.values[2].slice(0, 4), ['Rice', 1.5, 'kg', 'quantity']);
    assert.deepEqual(items.validations[1].slice(1, 3), ['CHECKBOX', null]);
    assert.deepEqual(items.validations[2].slice(1, 3), [null, 'VALUE_IN_LIST']);
    assert.equal((await get({ action: 'list' })).items[1].value, 2, '1.5 kg is 2 for an old app');

    assert.equal((await debug('POST', '/debug/script', { script: 'startup' })).body.version, 2);
    assert.equal((await debug('POST', '/debug/script', { script: 'current' })).body.version, 3);
  });

  it('rejects malformed seed and script requests', async () => {
    for (const payload of [{ items: 'Milk' }, { items: [42] }, { items: [], history: [{}] }, { items: Array.from({ length: 501 }, () => ['x', true, 'boolean']) }]) {
      const res = await debug('POST', '/debug/seed-v2', payload);
      assert.equal(res.status, 400, JSON.stringify(payload).slice(0, 60));
      assert.equal(res.body.ok, false);
    }
    assert.equal((await debug('POST', '/debug/script', { script: '/etc/passwd' })).status, 400);
    assert.match((await debug('POST', '/debug/script', { script: 'v4' })).body.error, /"current", "v3", "v2" or "startup"/);
    const notJson = await request('POST', `${base}/debug/seed-v2`, { body: 'nope' });
    assert.equal(notJson.status, 400);
  });
});

describe('mock web app serving the script of app 1.1 (CODE_GS=v3), then the current one', () => {
  let mock;
  let base;
  let execUrl;

  before(async () => {
    mock = createMockServer({ token: TOKEN, codeGs: 'v3', log: () => {} });
    const { port, url } = await mock.listen(0);
    base = `http://127.0.0.1:${port}`;
    execUrl = url;
  });
  after(() => mock.close());

  const get = async (params) => JSON.parse((await callExec('GET', `${execUrl}?${new URLSearchParams({ token: TOKEN, ...params })}`)).final.body);
  const post = async (payload) => JSON.parse((await callExec('POST', execUrl, {
    body: JSON.stringify({ token: TOKEN, action: 'apply', device: 'Home', protocol: 3, ...payload }),
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  })).final.body);
  const debug = async (method, path, payload) => {
    const res = await request(method, `${base}${path}`, payload === undefined ? {} : { body: JSON.stringify(payload) });
    return { status: res.status, body: JSON.parse(res.body) };
  };
  const itemsSheet = async () => (await debug('GET', '/debug/sheets')).body.sheets.find((s) => s.name === 'Items');
  const upsert = (name, value, extra = {}) => ({ id: crypto.randomUUID(), op: 'upsert', name, type: 'quantity', value, unit: 'kg', time: Date.now(), ...extra });
  const withoutTime = ({ serverTime, ...rest }) => rest;

  it('answers like the 1.1 script; the current one adds Added At on the first change', async () => {
    assert.equal((await get({ action: 'ping', v: '3' })).version, 3);
    const script = await debug('GET', '/debug/script');
    assert.deepEqual([script.body.path, script.body.version], [V3_CODE_GS_PATH, 3]);
    assert.deepEqual((await itemsSheet()).values[0], ['Item', 'Need', 'Unit', 'Type', 'Updated At', 'Updated By']);
    const added = await post({ changes: [upsert('Rice', 2), upsert('Milk', 1)] });
    assert.deepEqual(added.results.map((r) => r.status), ['applied', 'applied']);
    const old = await get({ action: 'list', v: '3' });
    assert.ok(old.items.every((i) => !('addedAt' in i)), 'the 1.1 script sends no addedAt');

    assert.equal((await debug('POST', '/debug/script', { script: 'current' })).body.version, 3);
    assert.deepEqual(withoutTime(await get({ action: 'list', v: '3' })), withoutTime(old), 'the same list (and revision) until something changes');
    assert.equal((await itemsSheet()).values[0].length, 6, 'a list adds no column');

    const later = Date.now();
    const res = await post({ changes: [upsert('Rice', 3, { time: later }), upsert('Tea', 1, { time: later }), upsert('Oat milk', 1, { time: later, addedAt: later - 60000 })] });
    assert.deepEqual(res.results.map((r) => r.status), ['applied', 'applied', 'applied']);
    assert.deepEqual((await itemsSheet()).values[0], ['Item', 'Need', 'Unit', 'Type', 'Updated At', 'Updated By', 'Added At']);
    const listed = await get({ action: 'list', v: '3' });
    const by = (name) => listed.items.find((i) => i.name === name);
    assert.equal('addedAt' in by('Rice'), false, 'an item from before keeps Added At unknown');
    assert.equal('addedAt' in by('Milk'), false);
    assert.equal(by('Tea').addedAt, by('Tea').updatedAt, 'a new item: Added At = Updated At');
    assert.equal(by('Oat milk').addedAt, later - 60000, 'the hint of a rename');
    assert.ok((await get({ action: 'list' })).items.every((i) => !('addedAt' in i)), 'an app from before units gets no addedAt');
  });

  it('switches back to the 1.1 script, which keeps working on the new layout', async () => {
    assert.equal((await debug('POST', '/debug/script', { script: 'v3' })).body.version, 3);
    const res = await post({ changes: [upsert('Salt', 1), upsert('Tea', 2)] });
    assert.deepEqual(res.results.map((r) => r.status), ['applied', 'applied']);
    const items = await itemsSheet();
    assert.equal(items.values[0][6], 'Added At');
    const row = (name) => items.values.find((line) => line[0] === name);
    assert.equal(row('Salt')[6], '', 'the 1.1 script does not fill in Added At');
    assert.match(row('Tea')[6], /^\d{4}-\d{2}-\d{2}T/, 'and leaves it alone on a change');
    assert.equal((await debug('POST', '/debug/script', { script: 'current' })).body.version, 3);
    const listed = await get({ action: 'list', v: '3' });
    assert.equal('addedAt' in listed.items.find((i) => i.name === 'Salt'), false);
    assert.equal(typeof listed.items.find((i) => i.name === 'Tea').addedAt, 'number');
  });
});

describe('CODE_GS', () => {
  it('resolves "current", "v3", "v2" and paths, and refuses a missing file', () => {
    assert.equal(resolveCodePath(''), CODE_GS_PATH);
    assert.equal(resolveCodePath('current'), CODE_GS_PATH);
    assert.equal(resolveCodePath('v3'), V3_CODE_GS_PATH);
    assert.equal(resolveCodePath('v2'), V2_CODE_GS_PATH);
    assert.equal(resolveCodePath(V2_CODE_GS_PATH), V2_CODE_GS_PATH);
    assert.throws(() => resolveCodePath('no/such/Code.gs'), /CODE_GS: no file at/);
    assert.throws(() => createMockServer({ codeGs: 'no/such/Code.gs', log: () => {} }), /CODE_GS: no file at/);
  });
});
