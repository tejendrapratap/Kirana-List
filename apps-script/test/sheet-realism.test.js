'use strict';
/**
 * Regression tests for behaviour of the real Sheets / Apps Script services that a naive fake hides:
 * people editing the sheet while an apply runs, Sheets' parsing of written strings, stored times in
 * the future, names typed by hand, hand removals, checkbox-filled columns and best-effort caching.
 */
const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { loadCodeGs, getEvent, postEvent, DEFAULT_NOW } = require('./fake-gas');

const TOKEN = 'test-secret-token';
const NOW = DEFAULT_NOW;
const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;

function harness() {
  const gas = loadCodeGs({ token: TOKEN });
  const parse = (output) => JSON.parse(output.getContent());
  const h = {
    gas,
    list: () => parse(gas.doGet(getEvent({ token: TOKEN, action: 'list' }))),
    apply: (changes, device = 'Home') =>
      parse(gas.doPost(postEvent(JSON.stringify({ token: TOKEN, action: 'apply', device, changes })))),
    sheet: (name) => gas.spreadsheet.getSheetByName(name),
    rows: (name = 'Items') => {
      const sheet = gas.spreadsheet.getSheetByName(name);
      return sheet ? sheet.dump().slice(1) : [];
    },
    /** Runs fn once, right after the apply has read the Items data rows (columns A-G). */
    duringApply: (fn, times = 1) =>
      gas.afterNextRead('Items', fn, { times, when: (range) => range.getRow() === 2 && range.getNumColumns() === 7 }),
  };
  return h;
}

let idCounter = 0;
const nextId = () => `00000000-0000-4000-9000-${String(++idCounter).padStart(12, '0')}`;
const upsert = (name, type, value, time = NOW) => ({ id: nextId(), op: 'upsert', name, type, value, time });
const remove = (name, time = NOW) => ({ id: nextId(), op: 'delete', name, time });
const statuses = (response) => response.results.map((r) => r.status);
const names = (h) => h.rows().map((r) => r[0]);

/** Items: Apples, Bread, Cheese, Dates (rows 2-5), written an hour ago by "Home". */
function fourItems() {
  const h = harness();
  h.apply([
    upsert('Apples', 'integer', 1, NOW - HOUR),
    upsert('Bread', 'boolean', true, NOW - HOUR),
    upsert('Cheese', 'integer', 1, NOW - HOUR),
    upsert('Dates', 'integer', 1, NOW - HOUR),
  ]);
  h.gas.advance(MINUTE);
  return h;
}

describe('hand edits while an apply runs', () => {
  it('re-reads Items when a person inserts a row between the read and the writes', () => {
    const h = fourItems();
    h.duringApply((sheet) => {
      sheet.insertRowBefore(3);
      h.gas.handEdit({ row: 3, col: 1, value: 'Eggs' });
    });
    const res = h.apply([upsert('Dates', 'integer', 9, NOW + MINUTE), remove('Bread', NOW + MINUTE)], 'Shop');

    assert.deepEqual(statuses(res), ['applied', 'applied']);
    assert.deepEqual(h.rows().map((r) => [r[0], r[1]]), [['Apples', 1], ['Eggs', ''], ['Cheese', 1], ['Dates', 9]]);
    assert.deepEqual(h.list().items.map((i) => i.name), ['Apples', 'Eggs', 'Cheese', 'Dates']);
    const eggs = h.rows()[1];
    assert.deepEqual([eggs[4].getTime(), eggs[5]], [NOW + MINUTE, 'Sheet'], 'the deferred onEdit stamped the typed row');
  });

  it('answers busy (writing nothing) when the sheet keeps changing under the apply', () => {
    const h = fourItems();
    let quantity = 1;
    h.duringApply(() => h.gas.handEdit({ row: 2, col: 2, value: ++quantity }), 10);
    const res = h.apply([remove('Bread', NOW + MINUTE)]);
    assert.deepEqual(res, { ok: false, error: 'busy' });
    assert.deepEqual(names(h), ['Apples', 'Bread', 'Cheese', 'Dates']);
    assert.equal(h.gas.lock.held, false);
  });

  it('onEdit waits for the lock and does not stamp a row that moved meanwhile', () => {
    const h = fourItems();
    // The person sets Cheese (row 4) to 5 while an apply deletes Bread (row 3) above it.
    h.duringApply(() => h.gas.handEdit({ row: 4, col: 2, value: 5 }));
    assert.deepEqual(statuses(h.apply([remove('Bread', NOW + MINUTE)], 'Shop')), ['applied']);

    const rows = h.rows();
    assert.deepEqual(rows.map((r) => [r[0], r[1], r[5]]), [['Apples', 1, 'Home'], ['Cheese', 5, 'Home'], ['Dates', 1, 'Home']],
      'the hand edit survives and the item now at row 4 (Dates) is not marked as changed by the sheet');
    assert.ok(h.gas.lock.timeouts.includes(0), 'onEdit tries the script lock');
    assert.equal(h.gas.lock.held, false);
  });

  it('onEdit outside an apply takes and releases the lock', () => {
    const h = fourItems();
    h.gas.handEdit({ row: 2, col: 2, value: 4 });
    assert.deepEqual(h.gas.lock.timeouts.slice(-1), [0]);
    assert.equal(h.gas.lock.held, false);
    assert.equal(h.rows()[0][5], 'Sheet');
  });
});

describe("text that Sheets would parse is stored verbatim", () => {
  const tricky = ['Jan 5', '5 March', 'Mar 2026', '5pm', '10:30 am', '₹50', '50 €', '1,000', "'Nduja", "''quoted", '=1+1', '@home'];

  it('keeps month names, times, currencies and leading apostrophes as the names that were sent', () => {
    const h = harness();
    const res = h.apply(tricky.map((n) => upsert(n, 'boolean', true, NOW - MINUTE)), "'Anna");
    assert.deepEqual(statuses(res), tricky.map(() => 'applied'));
    assert.deepEqual(h.list().items.map((i) => i.name), tricky);
    assert.deepEqual(res.items, h.list().items, 'the apply response matches what the sheet holds');
    assert.ok(h.rows().every((r) => typeof r[0] === 'string' && r[5] === "'Anna"));
    assert.ok(h.rows('History').every((r) => typeof r[1] === 'string' && r[6] === "'Anna"));

    // Same key again: the existing row is updated, no duplicate is appended.
    assert.deepEqual(statuses(h.apply([upsert('jan 5', 'integer', 2), upsert("'nduja", 'integer', 3)])), ['applied', 'applied']);
    assert.equal(h.rows().length, tricky.length);
  });

  it('keeps tombstone keys intact, so a deleted date-like name stays deleted', () => {
    const h = harness();
    h.apply([upsert('Jan 5', 'boolean', true, NOW - HOUR), upsert("'Nduja", 'boolean', true, NOW - HOUR)]);
    h.apply([remove('Jan 5', NOW - MINUTE), remove("'Nduja", NOW - MINUTE)]);
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['jan 5', "'nduja"]);
    assert.deepEqual(statuses(h.apply([upsert('Jan 5', 'boolean', true, NOW - 30 * MINUTE), upsert("'Nduja", 'boolean', true, NOW - 30 * MINUTE)])), ['stale', 'stale']);
  });

  it('writes text without an apostrophe into cells formatted as Plain text', () => {
    const h = harness();
    h.gas.setupSheets();
    h.sheet('Items').getRange(2, 1, 20, 1).setNumberFormat('@');
    h.sheet('Items').getRange(2, 6, 20, 1).setNumberFormat('@');
    h.apply(tricky.map((n) => upsert(n, 'boolean', true)), "'Anna");
    assert.deepEqual(names(h), tricky, 'no apostrophe is added to Plain text cells');
    assert.ok(h.rows().every((r) => r[5] === "'Anna"));
  });
});

describe('stored times in the future', () => {
  it('a future Updated At does not make changes stale', () => {
    const h = harness();
    h.apply([upsert('Milk', 'integer', 2, NOW - MINUTE), upsert('Tea', 'boolean', true, NOW - MINUTE)]);
    h.sheet('Items').getRange(2, 4, 2, 1).setValues([[new Date(NOW + 8 * HOUR)], [new Date(NOW + 8 * HOUR)]]);
    assert.deepEqual(statuses(h.apply([upsert('Milk', 'integer', 0), remove('Tea')], 'Shop')), ['applied', 'applied']);
    assert.deepEqual(h.list().items.map((i) => [i.name, i.value]), [['Milk', 0]]);
  });

  it('a changed spreadsheet time zone does not block changes', () => {
    const h = harness();
    h.apply([upsert('Milk', 'boolean', true, NOW - MINUTE)]);
    h.gas.spreadsheet.setTimeZoneOffsetForTest(-8 * HOUR); // e.g. GMT -> America/Los_Angeles
    assert.equal(h.list().items[0].updatedAt, NOW - MINUTE + 8 * HOUR, 'stored wall-clock times now read 8 h later');
    assert.deepEqual(statuses(h.apply([upsert('Milk', 'boolean', false)], 'Shop')), ['applied']);
  });

  it('a tombstone dated in the future only blocks changes older than now', () => {
    const h = harness();
    h.apply([remove('Milk', NOW - MINUTE)]);
    h.sheet('_Tombstones').getRange(2, 2).setValue(new Date(NOW + 8 * HOUR));
    assert.deepEqual(statuses(h.apply([upsert('Milk', 'integer', 1, NOW - 2 * MINUTE)])), ['stale']);
    assert.deepEqual(statuses(h.apply([upsert('Milk', 'integer', 1, NOW)])), ['applied']);
  });
});

describe('names typed into the sheet', () => {
  const long = 'Very long name '.repeat(10).trim(); // 149 characters

  function handTyped() {
    const h = harness();
    h.gas.setupSheets();
    h.sheet('Items').getRange(2, 1, 3, 6).setValues([
      ['Milk\n2%', true, '', 'boolean', new Date(NOW - HOUR), 'Sheet'],
      [long, 3, '', 'integer', new Date(NOW - HOUR), 'Sheet'],
      ['Tea', true, '', 'boolean', new Date(NOW - HOUR), 'Sheet'],
    ]);
    return h;
  }

  it('existing rows with line breaks or long names can still be changed and deleted', () => {
    const h = handTyped();
    assert.deepEqual(h.list().items.map((i) => i.name), ['Milk\n2%', long, 'Tea']);
    const res = h.apply([upsert('Milk 2%', 'boolean', false), upsert(long, 'integer', 0)], 'Shop');
    assert.deepEqual(res.results.map((r) => [r.status, r.error]), [['applied', undefined], ['applied', undefined]]);
    assert.deepEqual(h.rows().map((r) => [r[0], r[1]]), [['Milk 2%', false], [long, 0], ['Tea', true]]);

    assert.deepEqual(statuses(h.apply([remove(long), remove('Milk 2%')])), ['applied', 'applied']);
    assert.deepEqual(names(h), ['Tea']);
  });

  it('a delete may name the hand-typed spelling, line break included', () => {
    const h = handTyped();
    assert.deepEqual(statuses(h.apply([remove('Milk\n2%')])), ['applied']);
    assert.deepEqual(names(h), [long, 'Tea']);
  });

  it('new items still follow the app rules', () => {
    const h = handTyped();
    const res = h.apply([upsert('New\nline', 'boolean', true), upsert('x'.repeat(101), 'boolean', true)]);
    assert.deepEqual(statuses(res), ['error', 'error']);
    assert.match(res.results[0].error, /line break/);
    assert.match(res.results[1].error, /longer than 100/);
    assert.equal(h.rows().length, 3);
  });
});

describe('removing items by hand', () => {
  function milkAndTea() {
    const h = harness();
    h.apply([upsert('Milk', 'integer', 2, NOW - HOUR), upsert('Tea', 'boolean', true, NOW - HOUR)]);
    h.gas.advance(2 * MINUTE);
    return h;
  }

  it('clearing an Item cell remembers the item as deleted, so an older queued change cannot bring it back', () => {
    const h = milkAndTea();
    h.gas.handEdit({ row: 2, col: 1, value: '' });
    assert.deepEqual(h.rows('_Tombstones').map((r) => [r[0], r[1].getTime(), r[2]]), [['milk', NOW + 2 * MINUTE, 'Sheet']]);
    // The shopper ticked Milk offline a minute before it was removed at home.
    assert.deepEqual(statuses(h.apply([upsert('Milk', 'integer', 0, NOW + MINUTE)], 'Shop')), ['stale']);
    assert.deepEqual(h.list().items.map((i) => i.name), ['Tea']);
    // A change made after the removal adds it again.
    assert.deepEqual(statuses(h.apply([upsert('Milk', 'integer', 1, NOW + 2 * MINUTE)], 'Shop')), ['applied']);
  });

  it('renaming an Item cell remembers the old name as deleted', () => {
    const h = milkAndTea();
    h.gas.handEdit({ row: 2, col: 1, value: 'Oat milk' });
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['milk']);
    assert.deepEqual(statuses(h.apply([upsert('Milk', 'integer', 5, NOW + MINUTE)], 'Shop')), ['stale']);
    assert.deepEqual(h.list().items.map((i) => i.name), ['Oat milk', 'Tea']);
  });

  it('a change of spelling only, or a remaining duplicate row, is not a removal', () => {
    const h = milkAndTea();
    h.gas.handEdit({ row: 2, col: 1, value: 'MILK' });
    h.gas.handEdit({ row: 4, col: 1, value: 'Tea' });
    h.gas.handEdit({ row: 3, col: 1, value: 'Green tea' });
    assert.deepEqual(h.rows('_Tombstones'), []);
  });
});

describe('a Need column filled with checkboxes', () => {
  it('appends new items right below the list instead of after the last checkbox', () => {
    const h = harness();
    h.apply([upsert('Milk', 'boolean', true, NOW - MINUTE)]);
    const items = h.sheet('Items');
    const checkbox = h.gas.spreadsheetApp.newDataValidation().requireCheckbox().build();
    const count = items.getMaxRows() - 2;
    items.getRange(3, 2, count, 1).setDataValidation(checkbox);
    items.getRange(3, 2, count, 1).setValues(Array.from({ length: count }, () => [false]));
    assert.equal(items.getLastRow(), items.getMaxRows());

    h.apply([upsert('Bread', 'integer', 2), upsert('Eggs', 'boolean', true)]);
    assert.deepEqual(items.getRange(2, 1, 4, 4).getValues(), [
      ['Milk', true, '', 'boolean'],
      ['Bread', 2, '', 'quantity'],
      ['Eggs', true, '', 'boolean'],
      ['', false, '', ''],
    ]);
    assert.equal(items.getRange(3, 2).getDataValidation(), null, 'the quantity row lost its checkbox');
    assert.deepEqual(h.list().items.map((i) => i.name), ['Milk', 'Bread', 'Eggs']);
  });
});

describe('best-effort change-id cache', () => {
  it('a change re-sent after the cache dropped its id is applied again without changing the result', () => {
    const h = harness();
    const add = upsert('Milk', 'integer', 2, NOW - MINUTE);
    const del = remove('Tea', NOW - MINUTE);
    h.apply([upsert('Tea', 'boolean', true, NOW - HOUR)]);
    h.apply([add, del]);
    const before = h.list();

    h.gas.cache.evictAll();
    assert.deepEqual(statuses(h.apply([add, del])), ['applied', 'applied']);
    const after = h.list();
    assert.deepEqual(after.items, before.items);
    assert.equal(after.revision, before.revision);
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['tea']);
  });
});
