'use strict';
const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { loadCodeGs, getEvent, postEvent, DEFAULT_NOW } = require('./fake-gas');

const TOKEN = 'test-secret-token';
const NOW = DEFAULT_NOW;
const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const md5 = (text) => crypto.createHash('md5').update(text, 'utf8').digest('hex');

/** Code.gs in a fresh fake environment plus protocol helpers. */
function harness(options = {}) {
  const gas = loadCodeGs({ token: TOKEN, ...options });
  const parse = (output) => {
    assert.equal(output.getMimeType(), 'JSON');
    return JSON.parse(output.getContent());
  };
  const h = {
    gas,
    get: (params) => parse(gas.doGet(getEvent({ token: TOKEN, ...params }))),
    postRaw: (contents) => parse(gas.doPost(postEvent(contents))),
    post: (body) => h.postRaw(JSON.stringify({ token: TOKEN, action: 'apply', device: 'Home', ...body })),
    apply: (changes, device = 'Home') => h.post({ changes, device }),
    list: (since) => h.get(since === undefined ? { action: 'list' } : { action: 'list', since }),
    sheet: (name) => gas.spreadsheet.getSheetByName(name),
    /** Data rows (header excluded) of a sheet. */
    rows: (name = 'Items') => {
      const sheet = gas.spreadsheet.getSheetByName(name);
      return sheet ? sheet.dump().slice(1) : [];
    },
    needValidation: (row) => h.sheet('Items').getRange(row, 2).getDataValidation(),
  };
  return h;
}

let idCounter = 0;
const nextId = () => `00000000-0000-4000-8000-${String(++idCounter).padStart(12, '0')}`;
const upsert = (name, type, value, time = NOW, id = nextId()) => ({ id, op: 'upsert', name, type, value, time });
const remove = (name, time = NOW, id = nextId()) => ({ id, op: 'delete', name, time });
const statuses = (response) => response.results.map((r) => r.status);
const time = (cell) => cell.getTime();

describe('authentication and configuration', () => {
  it('ping answers with the app identity and spreadsheet name', () => {
    const h = harness();
    assert.deepEqual(h.get({ action: 'ping' }), { ok: true, app: 'grocery-sync', version: 3, spreadsheet: 'Groceries' });
    assert.equal(h.get({ action: 'ping', v: '3' }).version, 3, 'the same answer for an app that speaks version 3');
  });

  it('rejects a wrong or missing token on GET and POST', () => {
    const h = harness();
    const unauthorized = { ok: false, error: 'unauthorized' };
    assert.deepEqual(h.get({ action: 'ping', token: 'nope' }), unauthorized);
    assert.deepEqual(JSON.parse(h.gas.doGet(getEvent({ action: 'list' })).getContent()), unauthorized);
    assert.deepEqual(h.post({ token: 'nope', changes: [upsert('Milk', 'boolean', true)] }), unauthorized);
    assert.deepEqual(h.post({ token: 42, changes: [upsert('Milk', 'boolean', true)] }), unauthorized);
    assert.deepEqual(h.get({ action: 'nuke', token: 'nope' }), unauthorized, 'auth is checked before the action');
    assert.equal(h.sheet('Items'), null, 'nothing is written for unauthorized calls');
  });

  it('accepts the token with surrounding whitespace', () => {
    const h = harness();
    assert.equal(h.get({ action: 'ping', token: `  ${TOKEN} ` }).ok, true);
  });

  for (const [label, token] of [['the CHANGE_ME placeholder', undefined], ['an empty token', ''], ['a blank token', '   ']]) {
    it(`answers server_not_configured while SECRET_TOKEN is ${label}`, () => {
      const gas = loadCodeGs(token === undefined ? {} : { token });
      const expected = { ok: false, error: 'server_not_configured' };
      for (const t of ['CHANGE_ME', '', 'anything']) {
        assert.deepEqual(JSON.parse(gas.doGet(getEvent({ action: 'ping', token: t })).getContent()), expected);
      }
      assert.deepEqual(JSON.parse(gas.doPost(postEvent('{"token":"CHANGE_ME","action":"apply","changes":[]}')).getContent()), expected);
      assert.deepEqual(JSON.parse(gas.doPost(postEvent('not json')).getContent()), expected);
    });
  }

  it('rejects unknown GET actions after authentication', () => {
    const h = harness();
    assert.deepEqual(h.get({ action: 'nuke' }), { ok: false, error: 'bad_request: unknown action "nuke"' });
    assert.deepEqual(h.get({}), { ok: false, error: 'bad_request: unknown action (none)' });
    assert.equal(h.get({ action: 'apply' }).error, 'bad_request: unknown action "apply"', 'apply is POST-only');
  });

  it('never throws out of doGet/doPost, even for absent event objects', () => {
    const gas = loadCodeGs({ token: TOKEN });
    assert.equal(JSON.parse(gas.doGet(undefined).getContent()).error, 'unauthorized');
    assert.equal(JSON.parse(gas.doPost(undefined).getContent()).error, 'bad_request: missing JSON body');
    assert.equal(JSON.parse(gas.doPost({ postData: { contents: '' } }).getContent()).error, 'bad_request: missing JSON body');
  });
});

describe('list', () => {
  it('returns an empty list for a new spreadsheet and creates the sheets', () => {
    const h = harness();
    const res = h.list();
    assert.deepEqual(res, { ok: true, revision: md5('[]'), serverTime: NOW, spreadsheet: 'Groceries', items: [] });
    assert.equal(res.revision, 'd751713988987e9331980363e24189ce');
    const names = h.gas.spreadsheet.getSheets().map((s) => s.getName());
    assert.deepEqual(names, ['Items', 'History', '_Tombstones'], 'the blank Sheet1 is reused as Items');
    assert.deepEqual(h.sheet('Items').dump(), [['Item', 'Need', 'Unit', 'Type', 'Updated At', 'Updated By', 'Added At']]);
    assert.equal(h.gas.lock.held, false);
  });

  it('answers unchanged when since equals the current revision', () => {
    const h = harness();
    h.apply([upsert('Milk', 'integer', 2)]);
    const full = h.list();
    const unchanged = h.list(full.revision);
    assert.deepEqual(unchanged, { ok: true, revision: full.revision, serverTime: NOW, unchanged: true });
    assert.deepEqual(Object.keys(unchanged), ['ok', 'revision', 'serverTime', 'unchanged']);
    assert.deepEqual(h.list('0123456789abcdef0123456789abcdef').items, full.items, 'a stale revision gets the full list');
  });

  it('revision is the MD5 of the canonical items JSON, stable until something changes', () => {
    const h = harness();
    const applied = h.apply([upsert('Milk', 'integer', 2), upsert('Bread', 'boolean', true)]);
    const first = h.list();
    assert.equal(first.revision, md5(JSON.stringify(first.items)));
    assert.match(first.revision, /^[0-9a-f]{32}$/);
    assert.equal(applied.revision, first.revision, 'apply returns the same revision a list would');
    assert.deepEqual(applied.items, first.items);
    h.gas.advance(MINUTE);
    assert.equal(h.list().revision, first.revision, 'time passing alone does not change the revision');

    const updated = h.apply([upsert('Milk', 'integer', 3, h.gas.now())]);
    assert.notEqual(updated.revision, first.revision);
    assert.equal(h.list().revision, updated.revision);
  });

  it('returns items in sheet row order with the exact ItemJson shape', () => {
    const h = harness();
    h.apply([upsert('Zucchini', 'boolean', false), upsert('Apples', 'integer', 6)], 'Anna');
    const { items } = h.list();
    assert.deepEqual(items, [
      { name: 'Zucchini', type: 'boolean', value: false, updatedAt: NOW, updatedBy: 'Anna' },
      { name: 'Apples', type: 'integer', value: 6, updatedAt: NOW, updatedBy: 'Anna' },
    ]);
    assert.deepEqual(Object.keys(items[0]), ['name', 'type', 'value', 'updatedAt', 'updatedBy']);
  });
});

describe('apply: upserts', () => {
  it('adds an integer item: Items row, no checkbox, History row', () => {
    const h = harness();
    const change = upsert('Milk', 'integer', 2, NOW - MINUTE);
    const res = h.apply([change]);
    assert.equal(res.ok, true);
    assert.deepEqual(res.results, [{ id: change.id, status: 'applied' }]);
    assert.equal(res.serverTime, NOW);
    assert.equal(res.spreadsheet, 'Groceries');

    const [row] = h.rows();
    // The protocol 2 type "integer" is stored as a quantity without a unit.
    assert.deepEqual([row[0], row[1], row[2], row[3], time(row[4]), row[5]], ['Milk', 2, '', 'quantity', NOW - MINUTE, 'Home']);
    assert.equal(h.needValidation(2), null);
    assert.equal(h.sheet('Items').getRange(2, 5).getNumberFormat(), 'yyyy-mm-dd hh:mm:ss');

    const [history] = h.rows('History');
    assert.deepEqual([time(history[0]), ...history.slice(1)], [NOW - MINUTE, 'Milk', 'added', 2, '', 'quantity', 'Home', change.id]);
  });

  it('adds a boolean item as a real checkbox', () => {
    const h = harness();
    h.apply([upsert('Bread', 'boolean', true)]);
    assert.equal(h.rows()[0][1], true);
    assert.equal(h.rows()[0][2], '', 'no unit');
    assert.equal(h.rows()[0][3], 'boolean');
    assert.equal(h.needValidation(2).getCriteriaType(), 'CHECKBOX');
  });

  it('applies newer and equal-time updates, rejects older ones as stale', () => {
    const h = harness();
    h.apply([upsert('Milk', 'integer', 1, NOW - 10 * MINUTE)]);

    const newer = h.apply([upsert('Milk', 'integer', 2, NOW - 5 * MINUTE)], 'Shop');
    assert.deepEqual(statuses(newer), ['applied']);
    assert.deepEqual(newer.items, [{ name: 'Milk', type: 'integer', value: 2, updatedAt: NOW - 5 * MINUTE, updatedBy: 'Shop' }]);

    assert.deepEqual(statuses(h.apply([upsert('Milk', 'integer', 3, NOW - 5 * MINUTE)])), ['applied'], 'equal time wins');
    assert.equal(h.rows()[0][1], 3);

    const older = h.apply([upsert('Milk', 'integer', 9, NOW - 7 * MINUTE)]);
    assert.deepEqual(statuses(older), ['stale']);
    assert.equal(h.rows()[0][1], 3, 'stale change leaves the row alone');
    assert.deepEqual(h.rows('History').map((r) => r[2]), ['added', 'updated', 'updated'], 'no History row for stale');
  });

  it('switches boolean -> integer (validation cleared) and back (checkbox restored)', () => {
    const h = harness();
    h.apply([upsert('Eggs', 'boolean', true, NOW - 2 * MINUTE)]);
    assert.equal(h.needValidation(2).getCriteriaType(), 'CHECKBOX');

    h.apply([upsert('Eggs', 'integer', 12, NOW - MINUTE)]);
    assert.deepEqual(h.rows()[0].slice(0, 4), ['Eggs', 12, '', 'quantity']);
    assert.equal(h.needValidation(2), null);

    h.apply([upsert('Eggs', 'boolean', false, NOW)]);
    assert.deepEqual(h.rows()[0].slice(0, 4), ['Eggs', false, '', 'boolean']);
    assert.equal(h.needValidation(2).getCriteriaType(), 'CHECKBOX');
  });

  it('writes a quantity even when a person made the checkbox rule strict', () => {
    const h = harness();
    h.apply([upsert('Eggs', 'boolean', true, NOW - MINUTE)]);
    const strict = h.gas.spreadsheetApp.newDataValidation().requireCheckbox().setAllowInvalid(false).build();
    h.sheet('Items').getRange(2, 2).setDataValidation(strict);
    assert.deepEqual(statuses(h.apply([upsert('Eggs', 'integer', 6)])), ['applied']);
    assert.equal(h.rows()[0][1], 6);
  });

  it('matches names case- and whitespace-insensitively and stores the new spelling', () => {
    const h = harness();
    h.apply([upsert('Oat  Milk', 'boolean', true, NOW - MINUTE), upsert('Tea', 'boolean', true, NOW - MINUTE)]);
    const res = h.apply([upsert('  oat milk ', 'integer', 2)]);
    assert.deepEqual(statuses(res), ['applied']);
    assert.equal(h.rows().length, 2);
    assert.deepEqual(h.rows()[0].slice(0, 4), ['oat milk', 2, '', 'quantity']);
    assert.equal(res.items[0].name, 'oat milk');
    assert.deepEqual(h.rows('History').map((r) => r[2]), ['added', 'added', 'updated']);
  });

  it('trims the name of new items', () => {
    const h = harness();
    h.apply([upsert('   Apples  ', 'boolean', true)]);
    assert.equal(h.rows()[0][0], 'Apples');
  });

  it('defaults the device to Phone and limits it to 40 characters', () => {
    const h = harness();
    h.post({ device: undefined, changes: [upsert('A', 'boolean', true)] });
    h.post({ device: '   ', changes: [upsert('B', 'boolean', true)] });
    h.post({ device: `  ${'x'.repeat(60)}  `, changes: [upsert('C', 'boolean', true)] });
    h.post({ device: ' Kitchen\n tablet ', changes: [upsert('D', 'boolean', true)] });
    assert.deepEqual(h.rows().map((r) => r[5]), ['Phone', 'Phone', 'x'.repeat(40), 'Kitchen tablet']);
  });

  it('appends after the last used row, keeping blank rows and extra columns untouched', () => {
    const h = harness();
    h.gas.setupSheets();
    const items = h.sheet('Items');
    items.getRange(2, 1, 3, 8).setValues([
      ['Milk', true, '', 'boolean', new Date(NOW - HOUR), 'Home', '', 'note: lactose free'],
      ['', '', '', '', '', '', '', ''],
      ['Tea', 2, '', 'integer', new Date(NOW - HOUR), 'Home', '', ''],
    ]);
    h.apply([upsert('Rice', 'integer', 1), upsert('tea', 'integer', 5)]);
    const rows = items.dump().slice(1);
    assert.deepEqual(rows.map((r) => [r[0], r[1], r[7]]), [
      ['Milk', true, 'note: lactose free'],
      ['', '', ''],
      ['tea', 5, ''],
      ['Rice', 1, ''],
    ]);
  });
});

describe('apply: deletes and tombstones', () => {
  it('deletes the row, records a tombstone and History; rows below move up with their extra columns', () => {
    const h = harness();
    h.apply([upsert('Milk', 'integer', 2, NOW - HOUR), upsert('Bread', 'boolean', true, NOW - HOUR), upsert('Tea', 'boolean', false, NOW - HOUR)]);
    h.sheet('Items').getRange(4, 8).setValue('green tea'); // a column of one's own, right of Added At
    const change = remove('BREAD', NOW - MINUTE);
    const res = h.apply([change], 'Shop');

    assert.deepEqual(res.results, [{ id: change.id, status: 'applied' }]);
    assert.deepEqual(res.items.map((i) => i.name), ['Milk', 'Tea']);
    assert.deepEqual(h.rows().map((r) => [r[0], r[7] === undefined ? '' : r[7]]), [['Milk', ''], ['Tea', 'green tea']]);
    assert.equal(h.needValidation(3).getCriteriaType(), 'CHECKBOX', 'validation moved up with the Tea row');
    assert.equal(h.needValidation(2), null);

    const tombstones = h.rows('_Tombstones');
    assert.deepEqual(tombstones.map((r) => [r[0], time(r[1]), r[2]]), [['bread', NOW - MINUTE, 'Shop']]);
    assert.equal(h.sheet('_Tombstones').isSheetHidden(), true);

    const deleted = h.rows('History').at(-1);
    assert.deepEqual(deleted.slice(1), ['Bread', 'deleted', true, '', 'boolean', 'Shop', change.id]);
  });

  it('rejects a delete older than the row as stale', () => {
    const h = harness();
    h.apply([upsert('Milk', 'integer', 2, NOW - MINUTE)]);
    const res = h.apply([remove('Milk', NOW - HOUR)]);
    assert.deepEqual(statuses(res), ['stale']);
    assert.equal(h.rows().length, 1);
    assert.deepEqual(h.rows('_Tombstones'), []);
  });

  it('applies a delete of a missing item and keeps the later Deleted At', () => {
    const h = harness();
    assert.deepEqual(statuses(h.apply([remove('Ghost', NOW - MINUTE)])), ['applied']);
    assert.deepEqual(statuses(h.apply([remove('ghost', NOW - HOUR)], 'Other')), ['applied']);
    const tombstones = h.rows('_Tombstones');
    assert.equal(tombstones.length, 1);
    assert.deepEqual([tombstones[0][0], time(tombstones[0][1]), tombstones[0][2]], ['ghost', NOW - MINUTE, 'Home']);
    assert.deepEqual(h.rows('History').map((r) => [r[1], r[2], r[3], r[4]]), [['Ghost', 'deleted', '', ''], ['ghost', 'deleted', '', '']]);
  });

  it('does not resurrect a deleted item from an older offline upsert', () => {
    const h = harness();
    h.apply([upsert('Milk', 'integer', 2, NOW - HOUR)]);
    h.apply([remove('Milk', NOW - 10 * MINUTE)]);
    const res = h.apply([upsert('Milk', 'integer', 5, NOW - 20 * MINUTE)]);
    assert.deepEqual(statuses(res), ['stale']);
    assert.deepEqual(h.rows(), []);
    assert.deepEqual(res.items, []);
  });

  it('re-adds an item with a newer upsert and removes its tombstone', () => {
    const h = harness();
    h.apply([upsert('Milk', 'integer', 2, NOW - HOUR), upsert('Tea', 'boolean', true, NOW - HOUR)]);
    h.apply([remove('Milk', NOW - 10 * MINUTE), remove('Coffee', NOW - 10 * MINUTE)]);
    const res = h.apply([upsert('milk', 'boolean', true, NOW - 10 * MINUTE)]);
    assert.deepEqual(statuses(res), ['applied'], 'an upsert at exactly the delete time wins');
    assert.deepEqual(res.items.map((i) => i.name), ['Tea', 'milk']);
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['coffee']);
    assert.equal(h.rows('History').at(-1)[2], 'added');
  });

  it('handles add+delete and delete+re-add of the same item inside one batch', () => {
    const h = harness();
    h.apply([upsert('Jam', 'boolean', true, NOW - HOUR)]);
    const res = h.apply([
      upsert('Soap', 'boolean', true, NOW - 3 * MINUTE),
      remove('Soap', NOW - 2 * MINUTE),
      remove('Jam', NOW - 2 * MINUTE),
      upsert('Jam', 'integer', 2, NOW - MINUTE),
    ]);
    assert.deepEqual(statuses(res), ['applied', 'applied', 'applied', 'applied']);
    assert.deepEqual(h.rows().map((r) => r.slice(0, 4)), [['Jam', 2, '', 'quantity']]);
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['soap']);
    assert.deepEqual(h.list().items, res.items);
  });

  it('removes hand-entered duplicate rows of a deleted item so they cannot surface', () => {
    const h = harness();
    h.gas.setupSheets();
    h.sheet('Items').getRange(2, 1, 3, 6).setValues([
      ['Milk', true, '', 'boolean', new Date(NOW - HOUR), 'Home'],
      ['Tea', true, '', 'boolean', new Date(NOW - HOUR), 'Home'],
      ['milk ', 3, 'kg', 'integer', new Date(NOW - HOUR), 'Sheet'],
    ]);
    h.apply([remove('Milk')]);
    assert.deepEqual(h.list().items.map((i) => i.name), ['Tea']);
  });

  it('prunes tombstones older than 30 days on the next write', () => {
    const h = harness();
    h.apply([remove('Old', NOW - 31 * DAY), remove('Recent', NOW - 29 * DAY)]);
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['recent'], 'expired on arrival');

    h.sheet('_Tombstones').getRange(3, 1, 2, 3).setValues([
      ['ancient', new Date(NOW - 40 * DAY), 'Home'],
      ['', '', ''],
    ]);
    h.sheet('_Tombstones').getRange(5, 1, 1, 3).setValues([['fresh', new Date(NOW - DAY), 'Home']]);
    h.apply([upsert('Milk', 'boolean', true)]);
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['recent', 'fresh']);
    assert.equal(h.sheet('_Tombstones').getLastRow(), 3, 'surplus rows are cleared');

    h.gas.advance(2 * DAY);
    h.apply([upsert('Milk', 'boolean', false, h.gas.now())]);
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['fresh']);
    assert.deepEqual(statuses(h.apply([upsert('Recent', 'boolean', true, NOW - 30 * DAY)])), ['applied'], 'pruned tombstones no longer block');
  });

  it('copes with sheets that have no spare rows (deleting the last row, appending past the end)', () => {
    const h = harness();
    h.gas.setupSheets();
    const items = h.sheet('Items');
    items.deleteRows(3, items.getMaxRows() - 2);
    assert.equal(items.getMaxRows(), 2);

    assert.deepEqual(statuses(h.apply([upsert('A', 'boolean', true, NOW - HOUR)])), ['applied']);
    assert.deepEqual(statuses(h.apply([remove('A', NOW - MINUTE)])), ['applied']);
    assert.deepEqual(h.rows(), []);

    const adds = ['B', 'C', 'D', 'E'].map((n) => upsert(n, 'integer', 1, NOW - MINUTE));
    assert.deepEqual(statuses(h.apply(adds)), ['applied', 'applied', 'applied', 'applied']);
    assert.deepEqual(h.rows().map((r) => r[0]), ['B', 'C', 'D', 'E']);
    h.apply(['B', 'C', 'D', 'E'].map((n) => remove(n)));
    assert.deepEqual(h.list().items, []);
    assert.ok(items.getMaxRows() >= 2);
  });
});

describe('apply: time handling', () => {
  it('clamps a phone clock running ahead to the server time', () => {
    const h = harness();
    const res = h.apply([upsert('Milk', 'integer', 2, NOW + DAY)]);
    assert.equal(res.items[0].updatedAt, NOW);
    assert.equal(time(h.rows()[0][4]), NOW);

    h.gas.advance(MINUTE);
    const later = h.apply([upsert('Milk', 'integer', 3, NOW + 30 * SECOND)], 'Other');
    assert.deepEqual(statuses(later), ['applied'], 'a correct later edit is not blocked by the future timestamp');
  });

  it('uses the server time when the change time is missing or invalid', () => {
    const h = harness();
    const changes = [
      { id: nextId(), op: 'upsert', name: 'A', type: 'boolean', value: true },
      { id: nextId(), op: 'upsert', name: 'B', type: 'boolean', value: true, time: 'yesterday' },
      { id: nextId(), op: 'upsert', name: 'C', type: 'boolean', value: true, time: -5 },
      { id: nextId(), op: 'upsert', name: 'D', type: 'boolean', value: true, time: null },
    ];
    const res = h.apply(changes);
    assert.deepEqual(statuses(res), ['applied', 'applied', 'applied', 'applied']);
    assert.deepEqual(res.items.map((i) => i.updatedAt), [NOW, NOW, NOW, NOW]);
  });
});

describe('apply: change-id dedupe', () => {
  it('reports a re-sent change id as duplicate without applying it again', () => {
    const h = harness();
    const change = upsert('Milk', 'integer', 2, NOW - MINUTE);
    assert.deepEqual(statuses(h.apply([change])), ['applied']);
    const again = h.apply([{ ...change, value: 7, time: NOW }]);
    assert.deepEqual(again.results, [{ id: change.id, status: 'duplicate' }]);
    assert.equal(h.rows()[0][1], 2);
    assert.equal(h.rows('History').length, 1);
  });

  it('treats a repeated id inside one batch as duplicate', () => {
    const h = harness();
    const change = upsert('Milk', 'integer', 2);
    assert.deepEqual(statuses(h.apply([change, { ...change, value: 3 }])), ['applied', 'duplicate']);
    assert.equal(h.rows()[0][1], 2);
  });

  it('remembers applied ids for 6 hours in the script cache', () => {
    const h = harness();
    const change = upsert('Milk', 'integer', 2);
    h.apply([change]);
    assert.ok(h.gas.cache.get(`grocery-sync:change:${change.id}`));
    h.gas.advance(6 * HOUR - SECOND);
    assert.deepEqual(statuses(h.apply([change])), ['duplicate']);
    h.gas.advance(2 * SECOND);
    assert.deepEqual(statuses(h.apply([change])), ['applied'], 'after expiry last-write-wins decides');
  });

  it('keeps working when CacheService fails', () => {
    const h = harness();
    h.gas.cache.failing = true;
    assert.deepEqual(statuses(h.apply([upsert('Milk', 'integer', 2)])), ['applied']);
    assert.equal(h.rows().length, 1);
  });
});

describe('apply: validation', () => {
  it('reports per-change errors without blocking the valid changes', () => {
    const h = harness();
    const good1 = upsert('Milk', 'integer', 2);
    const good2 = remove('Ghost');
    const bad = [
      'not an object',
      { op: 'upsert', name: 'NoId', type: 'boolean', value: true },
      { id: '', op: 'upsert', name: 'EmptyId', type: 'boolean', value: true },
      { id: 'x'.repeat(65), op: 'upsert', name: 'LongId', type: 'boolean', value: true },
      { id: nextId(), op: 'rename', name: 'Op' },
      { id: nextId(), op: 'upsert', name: '   ', type: 'boolean', value: true },
      { id: nextId(), op: 'delete', name: 42 },
      { id: nextId(), op: 'upsert', name: 'n'.repeat(101), type: 'boolean', value: true },
      { id: nextId(), op: 'upsert', name: 'Two\nlines', type: 'boolean', value: true },
      { id: nextId(), op: 'upsert', name: 'Type', type: 'number', value: 1 },
      { id: nextId(), op: 'upsert', name: 'BoolAsInt', type: 'boolean', value: 1 },
      { id: nextId(), op: 'upsert', name: 'Negative', type: 'integer', value: -1 },
      { id: nextId(), op: 'upsert', name: 'Fraction', type: 'integer', value: 1.5 },
      { id: nextId(), op: 'upsert', name: 'TooMany', type: 'integer', value: 1000000 },
      { id: nextId(), op: 'upsert', name: 'Text', type: 'integer', value: '2' },
      { id: nextId(), op: 'upsert', name: 'IntAsBool', type: 'integer', value: true },
    ];
    const res = h.apply([good1, ...bad, good2]);
    assert.equal(res.ok, true);
    assert.equal(res.results.length, bad.length + 2);
    assert.deepEqual(res.results[0], { id: good1.id, status: 'applied' });
    assert.deepEqual(res.results.at(-1), { id: good2.id, status: 'applied' });
    res.results.slice(1, -1).forEach((r, i) => {
      assert.equal(r.status, 'error', `change ${i} should be an error`);
      assert.equal(typeof r.error, 'string');
      assert.ok(r.error.length > 0 && r.error.length < 80);
      const expectedId = typeof bad[i] === 'object' && typeof bad[i].id === 'string' ? bad[i].id : '';
      assert.equal(r.id, expectedId);
    });
    assert.ok(res.results.filter((r) => r.status !== 'error').every((r) => !('error' in r)));
    assert.deepEqual(h.rows().map((r) => r[0]), ['Milk']);
    assert.equal(h.rows('History').length, 2);
  });

  it('accepts the boundary values', () => {
    const h = harness();
    const res = h.apply([
      upsert('n'.repeat(100), 'integer', 999999),
      upsert('Zero', 'integer', 0),
      { ...upsert('LongId', 'boolean', false), id: 'i'.repeat(64) },
    ]);
    assert.deepEqual(statuses(res), ['applied', 'applied', 'applied']);
  });

  const badRequests = [
    ['more than 100 changes', () => JSON.stringify({ token: TOKEN, action: 'apply', changes: Array.from({ length: 101 }, (_, i) => upsert(`Item ${i}`, 'boolean', true)) })],
    ['an empty change list', () => JSON.stringify({ token: TOKEN, action: 'apply', changes: [] })],
    ['changes that are not an array', () => JSON.stringify({ token: TOKEN, action: 'apply', changes: { id: 'x' } })],
    ['missing changes', () => JSON.stringify({ token: TOKEN, action: 'apply' })],
    ['malformed JSON', () => `{"token":"${TOKEN}","action":"apply","changes":[`],
    ['a JSON array body', () => '[1,2,3]'],
    ['an unknown action', () => JSON.stringify({ token: TOKEN, action: 'wipe', changes: [upsert('A', 'boolean', true)] })],
    ['a missing action', () => JSON.stringify({ token: TOKEN, changes: [upsert('A', 'boolean', true)] })],
  ];
  for (const [label, body] of badRequests) {
    it(`answers bad_request for ${label}`, () => {
      const h = harness();
      const res = h.postRaw(body());
      assert.equal(res.ok, false);
      assert.match(res.error, /^bad_request: .+/);
      assert.equal(h.sheet('Items'), null, 'nothing was written');
    });
  }

  it('accepts exactly 100 changes', () => {
    const h = harness();
    const res = h.apply(Array.from({ length: 100 }, (_, i) => upsert(`Item ${i}`, 'integer', i)));
    assert.equal(res.results.length, 100);
    assert.ok(res.results.every((r) => r.status === 'applied'));
    assert.equal(h.rows().length, 100);
    assert.equal(h.rows('History').length, 100);
  });
});

describe('locking', () => {
  it('answers busy when the script lock cannot be acquired and writes nothing', () => {
    const h = harness();
    h.gas.lock.forceFail();
    assert.deepEqual(h.apply([upsert('Milk', 'integer', 2)]), { ok: false, error: 'busy' });
    assert.equal(h.sheet('Items'), null);
    assert.equal(h.gas.lock.held, false);
    h.gas.lock.forceFail(false);
    assert.equal(h.apply([upsert('Milk', 'integer', 2)]).ok, true);
  });

  it('waits up to 30 s, flushes and always releases the lock', () => {
    const h = harness();
    h.apply([upsert('Milk', 'integer', 2)]);
    assert.deepEqual(h.gas.lock.timeouts, [30000]);
    assert.equal(h.gas.lock.acquired, 1);
    assert.equal(h.gas.lock.released, 1);
    assert.ok(h.gas.spreadsheetApp.flushCount >= 1);

    h.gas.spreadsheet.getSheetByName = () => {
      throw new Error('Service Spreadsheets failed while accessing document');
    };
    const res = h.apply([upsert('Tea', 'boolean', true)]);
    assert.deepEqual(res, { ok: false, error: 'internal: Service Spreadsheets failed while accessing document' });
    assert.equal(h.gas.lock.held, false);
    assert.equal(h.gas.lock.released, 2);
  });

  it('list does not take the lock once the sheets exist', () => {
    const h = harness();
    h.list();
    const acquired = h.gas.lock.acquired;
    h.gas.lock.forceFail();
    assert.equal(h.list().ok, true);
    assert.equal(h.gas.lock.acquired, acquired);
  });
});

describe('reading hand-entered rows', () => {
  it('parses rows per the tolerance rules', () => {
    const h = harness();
    h.gas.setupSheets();
    const rows = [
      ['Apples', true, '', new Date(NOW - DAY), 'Anna'],
      ['Bananas', 3, '', '', ''],
      ['Bread', 'yes', '', 'not a date', '  Ben  '],
      ['Butter', 'X', '', '', ''],
      ['Cheese', 'no', '', '', ''],
      ['Eggs', '', '', '', ''],
      ['', true, 'boolean', new Date(NOW), 'Ghost'],
      ['Flour', 'maybe', '', '', ''],
      ['Garlic', 2.6, '', '', ''],
      ['Honey', -4, '', '', ''],
      ['Ice', 5000000, ' Integer ', '', ''],
      ['Jam', 'lots', 'integer', '', ''],
      ['Kale', "'12", 'integer', '', ''],
      ['Lemons', 3, 'boolean', '', ''],
      ['Mango', "'1", 'weird', '', ''],
      ['Nuts', "'0", '', '', ''],
      ['apples', false, '', '', 'Dup'],
      ['  Oranges  ', 'n', '', '', ''],
      ['Pears', true, 'integer', '', ''],
    ];
    // Typed into the current layout (Unit, column C, left empty); a version 2 client gets the same as before.
    h.sheet('Items').getRange(2, 1, rows.length, 6).setValues(rows.map(([item, need, ...rest]) => [item, need, '', ...rest]));

    const items = h.list().items;
    const summary = items.map((i) => [i.name, i.type, i.value]);
    assert.deepEqual(summary, [
      ['Apples', 'boolean', true],
      ['Bananas', 'integer', 3],
      ['Bread', 'boolean', true],
      ['Butter', 'boolean', true],
      ['Cheese', 'boolean', false],
      ['Eggs', 'boolean', false],
      ['Flour', 'boolean', true],
      ['Garlic', 'integer', 3],
      ['Honey', 'integer', 0],
      ['Ice', 'integer', 999999],
      ['Jam', 'integer', 0],
      ['Kale', 'integer', 12],
      ['Lemons', 'boolean', true],
      ['Mango', 'boolean', true],
      ['Nuts', 'boolean', false],
      ['Oranges', 'boolean', false],
      ['Pears', 'integer', 0],
    ]);
    assert.equal(items[0].updatedAt, NOW - DAY);
    assert.equal(items[0].updatedBy, 'Anna');
    assert.equal(items[1].updatedAt, 0, 'blank Updated At');
    assert.equal(items[2].updatedAt, 0, 'invalid Updated At');
    assert.equal(items[2].updatedBy, 'Ben');
  });

  it('updates only the first of duplicate rows and leaves the duplicate untouched', () => {
    const h = harness();
    h.gas.setupSheets();
    h.sheet('Items').getRange(2, 1, 3, 6).setValues([
      ['Milk', true, '', 'boolean', new Date(NOW - HOUR), 'Home'],
      ['Tea', true, '', 'boolean', new Date(NOW - HOUR), 'Home'],
      ['MILK', 4, '', 'integer', new Date(NOW - 2 * HOUR), 'Sheet'],
    ]);
    const res = h.apply([upsert('milk', 'integer', 2)]);
    assert.deepEqual(res.items.map((i) => [i.name, i.value]), [['milk', 2], ['Tea', true]]);
    assert.deepEqual(h.rows()[2].slice(0, 4), ['MILK', 4, '', 'integer']);
  });

  it('lets a hand edit win over an older offline change', () => {
    const h = harness();
    h.apply([upsert('Milk', 'integer', 2, NOW - HOUR)]);
    h.gas.handEdit({ row: 2, col: 2, value: 5 });
    assert.deepEqual(statuses(h.apply([upsert('Milk', 'integer', 1, NOW - MINUTE)])), ['stale']);
    assert.deepEqual(h.list().items, [{ name: 'Milk', type: 'integer', value: 5, updatedAt: NOW, updatedBy: 'Sheet' }]);
  });
});

describe('onEdit (hand edits in Google Sheets)', () => {
  function seeded() {
    const h = harness();
    h.apply([upsert('Milk', 'integer', 2, NOW - HOUR), upsert('Tea', 'boolean', true, NOW - HOUR), upsert('Jam', 'boolean', false, NOW - HOUR)]);
    h.gas.advance(MINUTE);
    return h;
  }
  const stamps = (h) => h.rows().map((r) => [time(r[4]), r[5]]);
  const before = [NOW - HOUR, 'Home'];
  const stamped = [NOW + MINUTE, 'Sheet'];

  it('stamps Updated At / Updated By for an edit of Need', () => {
    const h = seeded();
    h.gas.handEdit({ row: 3, col: 2, value: false });
    assert.deepEqual(stamps(h), [before, stamped, before]);
    assert.equal(h.sheet('Items').getRange(3, 5).getNumberFormat(), 'yyyy-mm-dd hh:mm:ss');
  });

  it('stamps every row of a multi-row edit within columns A-D', () => {
    const h = seeded();
    h.gas.handEdit({ row: 2, col: 1, values: [['Milk', 3, 'kg', 'quantity'], ['Tea', false, '', 'boolean']] });
    assert.deepEqual(stamps(h), [stamped, stamped, before]);
  });

  it('ignores the header row but stamps the data rows of a range that includes it', () => {
    const h = seeded();
    h.gas.handEdit({ row: 1, col: 4, values: [['Type'], ['quantity'], ['boolean']] });
    assert.deepEqual(stamps(h), [stamped, stamped, before]);
    assert.deepEqual(h.sheet('Items').dump()[0], ['Item', 'Need', 'Unit', 'Type', 'Updated At', 'Updated By', 'Added At']);
  });

  it('ignores edits of other columns, the header row and other sheets', () => {
    const h = seeded();
    h.gas.handEdit({ row: 2, col: 5, value: new Date(NOW) });
    h.gas.handEdit({ row: 2, col: 8, value: 'note' }); // a column of one's own, right of Added At
    h.gas.handEdit({ row: 1, col: 1, value: 'Item' });
    h.gas.handEdit({ sheet: 'History', row: 2, col: 2, value: 'x' });
    assert.deepEqual(stamps(h).slice(1), [before, before]);
    assert.equal(time(h.rows()[0][4]), NOW, 'column E keeps what the person typed');
  });

  it('does not stamp rows whose Item was cleared', () => {
    const h = seeded();
    h.gas.handEdit({ row: 2, col: 1, values: [[''], ['Green tea'], ['']] });
    const rows = h.rows();
    assert.deepEqual([time(rows[0][4]), rows[0][5]], before);
    assert.deepEqual([time(rows[1][4]), rows[1][5]], stamped);
    assert.deepEqual([time(rows[2][4]), rows[2][5]], before);
  });

  it('stamps a brand-new row typed below the list', () => {
    const h = seeded();
    h.gas.handEdit({ row: 5, col: 1, value: 'Rice' });
    const items = h.list().items;
    assert.deepEqual(items.at(-1), { name: 'Rice', type: 'boolean', value: false, updatedAt: NOW + MINUTE, updatedBy: 'Sheet' });
  });

  it('never throws', () => {
    const gas = loadCodeGs({ token: TOKEN });
    assert.doesNotThrow(() => gas.onEdit(undefined));
    assert.doesNotThrow(() => gas.onEdit({}));
    assert.doesNotThrow(() => gas.onEdit({ range: { getSheet() { throw new Error('boom'); } } }));
    assert.ok(gas.logs.some((line) => line.includes('onEdit failed') && line.includes('boom')));
  });
});

describe('formula-injection guard', () => {
  it('stores names and device names that look like formulas as plain text', () => {
    const h = harness();
    const names = ['=SUM(A1:A9)', '+cmd', '-x', '@me', '=HYPERLINK("http://evil")'];
    const res = h.apply(names.map((n) => upsert(n, 'boolean', true)), '=Evil()');
    assert.deepEqual(statuses(res), names.map(() => 'applied'));
    assert.deepEqual(h.list().items.map((i) => i.name), names);
    assert.ok(h.list().items.every((i) => i.updatedBy === '=Evil()'));

    for (const sheet of ['Items', 'History']) {
      const range = h.sheet(sheet).getDataRange();
      assert.ok(range.getFormulas().flat().every((f) => f === ''), `no formulas in ${sheet}`);
    }
    h.apply([remove('=SUM(A1:A9)')], '@device');
    assert.deepEqual(h.rows('_Tombstones').map((r) => [r[0], r[2]]), [['=sum(a1:a9)', '@device']]);
    assert.ok(h.sheet('_Tombstones').getDataRange().getFormulas().flat().every((f) => f === ''));
  });

  it('keeps names that Sheets would turn into numbers, dates or booleans as text', () => {
    const h = harness();
    const names = ['123', '2%', 'TRUE', '1/2', '1,000', '007', '3:30', '1e5'];
    h.apply(names.map((n) => upsert(n, 'boolean', true)));
    assert.deepEqual(h.list().items.map((i) => i.name), names);
    assert.ok(h.rows().every((r) => typeof r[0] === 'string'));
  });
});

describe('KEEP_HISTORY = false', () => {
  it('does not create or write the History sheet', () => {
    const h = harness({ keepHistory: false });
    assert.deepEqual(statuses(h.apply([upsert('Milk', 'integer', 2), remove('Milk', NOW + MINUTE)])), ['applied', 'applied']);
    assert.equal(h.sheet('History'), null);
    h.gas.setupSheets();
    assert.equal(h.sheet('History'), null);
  });

  it('leaves an existing History sheet alone', () => {
    const h = harness();
    h.apply([upsert('Milk', 'integer', 2)]);
    h.gas.config.KEEP_HISTORY = false;
    h.apply([upsert('Tea', 'integer', 2)]);
    assert.equal(h.rows('History').length, 1);
  });
});

describe('setupSheets and generateToken', () => {
  it('creates formatted sheets, reusing the blank default sheet', () => {
    const h = harness();
    assert.equal(h.gas.setupSheets(), 'Groceries');
    const items = h.sheet('Items');
    assert.deepEqual(h.gas.spreadsheet.getSheets().map((s) => s.getName()), ['Items', 'History', '_Tombstones']);
    assert.equal(items.getFrozenRows(), 1);
    assert.equal(items.getRange(1, 1).getFontWeight(), 'bold');
    assert.deepEqual(h.sheet('Items').dump(), [['Item', 'Need', 'Unit', 'Type', 'Updated At', 'Updated By', 'Added At']]);
    assert.deepEqual(h.sheet('History').dump(), [['Time', 'Item', 'Action', 'Need', 'Unit', 'Type', 'By', 'Change ID']]);
    assert.deepEqual(h.sheet('_Tombstones').dump(), [['Key', 'Deleted At', 'By']]);
    assert.equal(h.sheet('_Tombstones').isSheetHidden(), true);
    assert.equal(items.isSheetHidden(), false);
    assert.ok(h.gas.logs.some((line) => line.includes('"Groceries"')));
    assert.equal(h.gas.lock.held, false);

    h.gas.setupSheets();
    assert.equal(h.gas.spreadsheet.getSheets().length, 3, 'idempotent');
  });

  it('keeps a non-empty first sheet and custom header text', () => {
    const h = harness();
    const first = h.gas.spreadsheet.getSheets()[0];
    first.getRange(1, 1).setValue('My notes');
    h.gas.spreadsheet.insertSheet('Items').getRange(1, 1, 1, 2).setValues([['Product', '']]);
    h.gas.setupSheets();
    assert.deepEqual(h.gas.spreadsheet.getSheets().map((s) => s.getName()), ['Sheet1', 'Items', 'History', '_Tombstones']);
    assert.deepEqual(h.sheet('Items').dump()[0], ['Product', 'Need', 'Unit', 'Type', 'Updated At', 'Updated By', 'Added At']);
    assert.equal(first.getRange(1, 1).getValue(), 'My notes');
  });

  it('generateToken logs a 64-character hex token', () => {
    const h = harness();
    const token = h.gas.generateToken();
    assert.match(token, /^[0-9a-f]{64}$/);
    assert.ok(h.gas.logs.some((line) => line.includes(token)));
    assert.notEqual(h.gas.generateToken(), token);
  });
});

describe('standalone script (SPREADSHEET_URL)', () => {
  it('opens the sheet by URL when the script is not bound', () => {
    const probe = loadCodeGs();
    const h = harness({ bound: false, spreadsheetUrl: probe.spreadsheet.getUrl() });
    assert.equal(h.get({ action: 'ping' }).ok, true);
    assert.deepEqual(statuses(h.apply([upsert('Milk', 'integer', 2)])), ['applied']);
  });

  it('reports an internal error when there is no spreadsheet', () => {
    const h = harness({ bound: false });
    const res = h.get({ action: 'ping' });
    assert.equal(res.ok, false);
    assert.match(res.error, /^internal: no spreadsheet/);
  });
});
