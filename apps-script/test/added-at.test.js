'use strict';
/**
 * The Added At column (app 1.2): when a row was created, so the app can say "Added by <name>" or
 * "Updated by <name>". Items: Item | Need | Unit | Type | Updated At | Updated By | Added At.
 *  - The script writes Added At only when it creates a row (an upsert of a name not on the sheet, a
 *    re-add after a delete included), with the value it writes to Updated At, or the upsert's "addedAt"
 *    hint when 0 < addedAt <= the change's time. Later changes never touch it.
 *  - onEdit gives a row typed by hand (no Updated At and no Added At yet) Added At = the "now" it stamps.
 *  - Version 3 lists send "addedAt" (ms) when Added At holds a date; version 2 lists are unchanged.
 *  - Sheets built by the script of app 1.1 (test/fixtures/Code.v3.gs) and by the one from before units
 *    (Code.v2.gs) get the column from setupSheets or the first change, under the script lock, the way
 *    the Unit column was added (see migration.test.js). Rows from before keep Added At blank (not known).
 */
const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const path = require('node:path');
const { loadCodeGs, getEvent, postEvent, DEFAULT_NOW } = require('./fake-gas');

const TOKEN = 'test-secret-token';
const NOW = DEFAULT_NOW;
const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const V3_CODE = path.join(__dirname, 'fixtures', 'Code.v3.gs');
const V2_CODE = path.join(__dirname, 'fixtures', 'Code.v2.gs');
const V3_HEADER = ['Item', 'Need', 'Unit', 'Type', 'Updated At', 'Updated By'];
const HEADER = [...V3_HEADER, 'Added At'];
const HISTORY_HEADER = ['Time', 'Item', 'Action', 'Need', 'Unit', 'Type', 'By', 'Change ID'];
const DATE_FORMAT = 'yyyy-mm-dd hh:mm:ss';
const ADDED_AT = 7; // column G
const TIMEOUT = 'Service Spreadsheets timed out while accessing document with id fake-spreadsheet-id.';
const PROTECTED = 'You are trying to edit a protected cell or object. Please contact the spreadsheet owner to remove protection if you need to edit.';
const md5 = (text) => crypto.createHash('md5').update(text, 'utf8').digest('hex');

function harness(codePath) {
  const gas = loadCodeGs({ token: TOKEN, ...(codePath ? { codePath } : {}) });
  const parse = (output) => JSON.parse(output.getContent());
  const h = {
    gas,
    get: (params) => parse(gas.doGet(getEvent({ token: TOKEN, ...params }))),
    /** list as an app speaking version 3 (1.1 or 1.2) asks for it. */
    list: () => h.get({ action: 'list', v: '3' }),
    /** list as an app from before units asks for it. */
    listV2: () => h.get({ action: 'list' }),
    post: (body) => parse(gas.doPost(postEvent(JSON.stringify({ token: TOKEN, action: 'apply', device: 'Home', ...body })))),
    apply: (changes, device = 'Home') => h.post({ protocol: 3, device, changes }),
    applyV2: (changes, device = 'Home') => h.post({ device, changes }),
    sheet: (name = 'Items') => gas.spreadsheet.getSheetByName(name),
    header: (name = 'Items') => h.sheet(name).dump()[0],
    rows: (name = 'Items') => h.sheet(name).dump().slice(1),
    cell: (row, column) => h.sheet().getRange(row, column).getValue(),
    format: (row, column) => h.sheet().getRange(row, column).getNumberFormat(),
    rule: (row, column) => {
      const rule = h.sheet().getRange(row, column).getDataValidation();
      return rule ? rule.getCriteriaType() : null;
    },
    item: (name, listed = h.list()) => listed.items.find((i) => i.name === name),
    logged: (text) => gas.logs.filter((line) => line.includes(text)).length,
  };
  return h;
}

let idCounter = 0;
const nextId = () => `00000000-0000-4000-d000-${String(++idCounter).padStart(12, '0')}`;
const qty = (name, value, unit, time = NOW, extra = {}) => ({ id: nextId(), op: 'upsert', name, type: 'quantity', value, unit, time, ...extra });
const flag = (name, value, time = NOW, extra = {}) => ({ id: nextId(), op: 'upsert', name, type: 'boolean', value, time, ...extra });
const integer = (name, value, time = NOW) => ({ id: nextId(), op: 'upsert', name, type: 'integer', value, time });
const remove = (name, time = NOW) => ({ id: nextId(), op: 'delete', name, time });
const statuses = (response) => response.results.map((r) => r.status);
const ms = (cell) => (cell instanceof Date ? cell.getTime() : cell);
/** A row of the 1.1 layout with an empty Added At cell inserted after Updated By (index 6). */
const withAddedAt = (row) => [...row.slice(0, 6), '', ...row.slice(6)];

/**
 * The user's sheet today, built by the script of app 1.1 itself: Rice 2 kg, Milk (ticked) and Tea
 * (1 pack, changed an hour ago) at rows 2-4, Soap added and deleted (a tombstone); plus a Notes column of
 * the person's own right after Updated By (column G, "basmati" for Rice) unless notes is false. Then the
 * new Code.gs is pasted and deployed; nothing has run since.
 */
function v11Sheet({ notes = true } = {}) {
  const h = harness(V3_CODE);
  h.apply([qty('Rice', 2, 'kg', NOW - 3 * HOUR), flag('Milk', true, NOW - 3 * HOUR), qty('Tea', 0, 'pack', NOW - 3 * HOUR),
    flag('Soap', true, NOW - 3 * HOUR)]);
  h.apply([qty('Tea', 1, 'pack', NOW - HOUR), remove('Soap', NOW - 2 * HOUR)]);
  if (notes) {
    h.sheet().getRange(1, 7).setValue('Notes');
    h.sheet().getRange(2, 7).setValue('basmati');
  }
  h.gas.advance(MINUTE);
  assert.deepEqual(h.header(), notes ? [...V3_HEADER, 'Notes'] : V3_HEADER);
  assert.equal(h.get({ action: 'ping', v: '3' }).version, 3);
  const old = { list: h.list(), listV2: h.listV2(), items: h.sheet().dump(), history: h.rows('History'), tombstones: h.rows('_Tombstones') };
  assert.ok(old.list.items.every((i) => !('addedAt' in i)), 'the 1.1 script sends no addedAt');
  h.gas.reloadCode(); // the new Code.gs, pasted and deployed
  return { h, old };
}

/** A sheet of the current script: Rice (2 kg), Milk (yes/no) and Tea (1 pack) added an hour ago by Home; then a minute passes. */
function currentSheet() {
  const h = harness();
  h.apply([qty('Rice', 2, 'kg', NOW - HOUR), flag('Milk', true, NOW - HOUR), qty('Tea', 1, 'pack', NOW - HOUR)]);
  h.gas.advance(MINUTE);
  return h;
}

describe('a sheet built by the script of app 1.1, before anything runs', () => {
  it('is read as it is: the same lists (revisions included), no addedAt, nothing changed', () => {
    const { h, old } = v11Sheet();
    assert.equal(h.get({ action: 'ping', v: '3' }).version, 3, 'the protocol version stays 3');
    assert.deepEqual(h.list(), old.list);
    assert.deepEqual(h.listV2(), old.listV2);
    assert.deepEqual(h.sheet().dump(), old.items, 'listing changes nothing');
  });

  it('a hand edit is stamped as before, and onEdit neither adds the column nor writes into Notes', () => {
    const { h, old } = v11Sheet();
    h.gas.handEdit({ row: 2, col: 2, value: 3 });
    h.gas.handEdit({ row: 5, col: 1, value: 'Salt' });
    assert.deepEqual(h.header(), [...V3_HEADER, 'Notes']);
    const rows = h.rows();
    assert.deepEqual([rows[0][1], ms(rows[0][4]), rows[0][5], rows[0][6]], [3, NOW + MINUTE, 'Sheet', 'basmati']);
    assert.deepEqual([rows[3][0], ms(rows[3][4]), rows[3][5], rows[3][6]], ['Salt', NOW + MINUTE, 'Sheet', '']);
    assert.deepEqual(rows.slice(1, 3), old.items.slice(2, 4));
    assert.ok(h.list().items.every((i) => !('addedAt' in i)));
  });
});

describe('adding the Added At column', () => {
  it('the first change adds it right after Updated By; Notes moves right with its cells; old rows stay unknown', () => {
    const { h, old } = v11Sheet();
    const change = qty('Rice', 3, 'kg');
    const res = h.apply([change]);
    assert.deepEqual(res.results, [{ id: change.id, status: 'applied' }]);
    assert.deepEqual(h.header(), [...HEADER, 'Notes']);
    const expected = old.items.slice(1).map(withAddedAt);
    expected[0] = ['Rice', 3, 'kg', 'quantity', new Date(NOW), 'Home', '', 'basmati'];
    assert.deepEqual(h.rows(), expected, 'Added At blank on every row from before, the changed one included');

    assert.equal(h.sheet().getRange(1, ADDED_AT).getFontWeight(), 'bold');
    assert.deepEqual([2, 3, 4, 50].map((row) => h.format(row, ADDED_AT)), Array(4).fill(DATE_FORMAT), 'date-formatted like Updated At');
    assert.deepEqual([2, 3, 4, 50].map((row) => h.rule(row, ADDED_AT)), [null, null, null, null], 'no rules copied from Updated By');
    assert.equal(h.rule(3, 2), 'CHECKBOX', 'Milk keeps its checkbox');
    assert.equal(h.rule(2, 3), 'VALUE_IN_LIST', 'Rice keeps its unit dropdown');
    assert.equal(h.logged('added the Added At column (column G) to the Items sheet'), 1);

    const listed = h.list();
    assert.ok(listed.items.every((i) => !('addedAt' in i)), 'not known for the items from before');
    assert.deepEqual(listed.items.slice(1), old.list.items.slice(1));
    assert.deepEqual(res.items, listed.items);
    assert.deepEqual(h.listV2().items.slice(1), old.listV2.items.slice(1));

    assert.deepEqual(h.header('History'), HISTORY_HEADER, 'History is unchanged');
    assert.deepEqual(h.rows('History').slice(0, old.history.length), old.history);
    assert.deepEqual(h.rows('History').at(-1).slice(1), ['Rice', 'updated', 3, 'kg', 'quantity', 'Home', change.id]);
    assert.deepEqual(h.rows('_Tombstones'), old.tombstones);
    assert.deepEqual(statuses(h.apply([flag('Soap', true, NOW - 3 * HOUR)])), ['stale'], 'the deletion still holds');
  });

  it('a new item gets Added At = Updated At, which a version 3 list sends as addedAt', () => {
    const { h } = v11Sheet();
    const add = qty('Salt', 1, 'pack', NOW - 30 * SECOND);
    const res = h.apply([add], 'Shop');
    assert.deepEqual(statuses(res), ['applied']);
    const salt = h.rows()[3];
    assert.deepEqual([salt[0], ms(salt[4]), salt[5], ms(salt[6]), salt[7]], ['Salt', NOW - 30 * SECOND, 'Shop', NOW - 30 * SECOND, '']);
    assert.equal(h.format(5, ADDED_AT), DATE_FORMAT);
    const listed = h.list();
    assert.deepEqual(h.item('Salt', listed),
      { name: 'Salt', type: 'quantity', value: 1, unit: 'pack', updatedAt: NOW - 30 * SECOND, updatedBy: 'Shop', addedAt: NOW - 30 * SECOND });
    assert.deepEqual(res.items, listed.items);
    assert.equal(listed.revision, md5(JSON.stringify(listed.items)));
    assert.deepEqual(h.item('Salt', h.listV2()), { name: 'Salt', type: 'integer', value: 1, updatedAt: NOW - 30 * SECOND, updatedBy: 'Shop' });
    assert.deepEqual(h.rows('History').at(-1).slice(1), ['Salt', 'added', 1, 'pack', 'quantity', 'Shop', add.id], 'History as before');
  });

  it('setupSheets adds it once and says to deploy; the lists stay the same; idempotent', () => {
    const { h, old } = v11Sheet();
    assert.equal(h.gas.setupSheets(), 'Groceries');
    assert.deepEqual(h.header(), [...HEADER, 'Notes']);
    assert.deepEqual(h.header('History'), HISTORY_HEADER);
    assert.equal(h.logged('Added the Added At column (right after Updated By) to the Items sheet.'), 1);
    assert.equal(h.logged('Added the Unit column'), 0);
    assert.equal(h.logged('Deploy this code now if you have not yet'), 1);
    assert.equal(h.logged('leaves Added At empty for the items they add'), 1);
    assert.equal(h.logged('old column order'), 0);
    assert.deepEqual(h.list(), old.list, 'same items, same revision');
    assert.deepEqual(h.listV2(), old.listV2);

    h.gas.setupSheets();
    assert.deepEqual(statuses(h.apply([qty('Salt', 1, 'kg')])), ['applied']);
    h.gas.setupSheets();
    assert.deepEqual(h.header(), [...HEADER, 'Notes'], 'idempotent');
    assert.equal(h.logged('Added the Added At column'), 1);
    assert.equal(h.logged('added the Added At column'), 1);
    assert.equal(h.logged('Deploy this code now'), 1);
    assert.deepEqual(h.gas.logs.filter((line) => line.startsWith('[warn]')), [h.gas.logs.find((line) => line.includes('Deploy this code now'))]);
    assert.equal(h.gas.lock.held, false);
  });

  it('a sheet with no column to spare after Updated By gets one', () => {
    const { h } = v11Sheet({ notes: false });
    const items = h.sheet();
    items.deleteColumns(7, items.getMaxColumns() - 6);
    assert.equal(items.getMaxColumns(), 6);
    assert.deepEqual(statuses(h.apply([qty('Salt', 1, 'kg')])), ['applied']);
    assert.deepEqual(h.header(), HEADER);
    assert.equal(items.getMaxColumns(), 7);
    assert.equal(ms(h.item('Salt').addedAt), NOW);
  });

  it('a sheet still in the layout from before units gets Unit after Need, then Added At after Updated By', () => {
    const h = harness(V2_CODE);
    h.applyV2([flag('Milk', true, NOW - HOUR), integer('Rice', 2, NOW - HOUR)]);
    h.sheet().getRange(1, 6).setValue('Notes');
    h.sheet().getRange(3, 6).setValue('basmati');
    const oldItems = h.sheet().dump();
    h.gas.reloadCode();
    h.gas.setupSheets();
    assert.deepEqual(h.header(), [...HEADER, 'Notes']);
    assert.deepEqual(h.rows(), oldItems.slice(1).map((row) => [row[0], row[1], '', row[2], row[3], row[4], '', row[5]]));
    assert.deepEqual(h.gas.logs.filter((line) => line.startsWith('Added the')), [
      'Added the Unit column (right after Need) to the Items sheet.',
      'Added the Added At column (right after Updated By) to the Items sheet.',
      'Added the Unit column (right after Need) to the History sheet.',
    ]);
    assert.equal(h.logged('writes their changes in the old column order'), 1, 'the deploy warning of the units update');
    assert.deepEqual(h.header('History'), HISTORY_HEADER, 'History gets Unit only');
    assert.deepEqual(statuses(h.applyV2([integer('Eggs', 6)])), ['applied'], 'an app from before units creates an item');
    assert.deepEqual(h.item('Eggs'), { name: 'Eggs', type: 'quantity', value: 6, unit: '', updatedAt: NOW, updatedBy: 'Home', addedAt: NOW });
  });

  it('a column of the person\'s own after Updated By is never taken for Added At: empty, or dates typed by hand', () => {
    for (const [title, value, format] of [['Shop', '', null], ['Bought on', new Date(NOW - DAY), 'd mmm yyyy'], ['', new Date(NOW - DAY), 'd/m/yyyy']]) {
      const { h } = v11Sheet({ notes: false });
      h.sheet().getRange(1, 7).setValue(title);
      if (value !== '') h.sheet().getRange(2, 7).setValue(value);
      if (format) h.sheet().getRange(2, 7).setNumberFormat(format);
      assert.ok(h.list().items.every((i) => !('addedAt' in i)), `"${title}" is not read as Added At`);
      assert.deepEqual(statuses(h.apply([qty('Salt', 1, 'kg')])), ['applied']);
      assert.deepEqual(h.header(), value === '' ? [...HEADER, title] : [...HEADER, title], title);
      assert.equal(ms(h.cell(2, 8)), ms(value), 'its cells moved right with it');
      if (format) assert.equal(h.format(2, 8), format);
      assert.equal(ms(h.cell(5, ADDED_AT)), NOW, 'the new item\'s Added At goes into the new column');
      assert.equal(h.cell(5, 8), '');
    }
  });

  it('waits for the script lock: while another execution holds it nothing changes', () => {
    const { h, old } = v11Sheet();
    h.gas.lock.forceFail();
    assert.deepEqual(h.apply([qty('Salt', 1, 'kg')]), { ok: false, error: 'busy' });
    assert.deepEqual(h.sheet().dump(), old.items);
    assert.deepEqual(h.list(), old.list, 'lists keep working meanwhile');
    h.gas.lock.forceFail(false);
    assert.deepEqual(statuses(h.apply([qty('Salt', 1, 'kg')])), ['applied']);
    assert.deepEqual(h.header(), [...HEADER, 'Notes']);
  });

  it('an apply that waited for the one adding the column does not add another', () => {
    const { h } = v11Sheet();
    let second = null;
    h.gas.afterNextRead('Items', () => {
      h.gas.lock.whenFree(() => {
        second = h.apply([qty('Jam', 1, 'pack', NOW + MINUTE)], 'Shop');
      });
    }, { when: (range) => range.getRow() === 1 });
    assert.deepEqual(statuses(h.apply([qty('Salt', 1, 'kg')])), ['applied']);
    assert.ok(second, 'the second apply ran after the first released the lock');
    assert.deepEqual(statuses(second), ['applied']);
    assert.deepEqual(h.header(), [...HEADER, 'Notes']);
    assert.equal(h.logged('added the Added At column'), 1);
    assert.deepEqual(['Salt', 'Jam'].map((name) => h.item(name).addedAt), [NOW, NOW + MINUTE]);
  });

  it('a list while the column is being inserted (not titled yet) reads every item as before', () => {
    const { h, old } = v11Sheet();
    const items = h.sheet();
    const insert = items.insertColumnAfter.bind(items);
    const during = [];
    items.insertColumnAfter = (position) => {
      const result = insert(position);
      during.push({ header: items.dump()[0], v3: h.list(), v2: h.listV2() });
      return result;
    };
    h.apply([qty('Rice', 3, 'kg')]);
    assert.deepEqual(during.map((d) => d.header), [[...V3_HEADER, '', 'Notes']]);
    assert.deepEqual(during[0].v3, old.list);
    assert.deepEqual(during[0].v2, old.listV2);
  });

  it('a list that overlaps the apply adding the column reads one consistent snapshot', () => {
    const { h, old } = v11Sheet();
    h.gas.afterNextRead('Items', () => {
      assert.deepEqual(statuses(h.apply([qty('Salt', 1, 'kg')])), ['applied']);
    }, { when: (range) => range.getRow() === 1 });
    assert.deepEqual(h.list(), old.list, 'what the sheet held when the list read it');
    assert.deepEqual(h.header(), [...HEADER, 'Notes'], 'the apply ran meanwhile');
    assert.equal(h.item('Salt').addedAt, NOW);
  });

  it('hand edits made meanwhile are kept; their onEdit (which waited) stamps them, Added At for a new row', () => {
    const { h } = v11Sheet();
    h.gas.afterNextRead('Items', () => {
      h.gas.handEdit({ row: 4, col: 2, value: 5 }); // Tea's Need
      h.gas.handEdit({ row: 5, col: 1, value: 'Bread' }); // a new row typed below the list
    }, { when: (range) => range.getRow() === 1 });
    assert.deepEqual(statuses(h.apply([qty('Rice', 3, 'kg')])), ['applied']);
    const [, , tea, bread] = h.rows();
    assert.deepEqual([tea[1], ms(tea[4]), tea[5], tea[6]], [5, NOW + MINUTE, 'Sheet', ''], 'an existing row: Added At stays unknown');
    assert.deepEqual([bread[0], ms(bread[4]), bread[5], ms(bread[6]), bread[7]], ['Bread', NOW + MINUTE, 'Sheet', NOW + MINUTE, '']);
    assert.equal(h.item('Bread').addedAt, NOW + MINUTE);
    assert.equal(h.gas.lock.held, false);
  });

  it('an earlier attempt that stopped after inserting the column: that empty column is titled, no second one', () => {
    const { h, old } = v11Sheet();
    h.gas.failNextWrite('Items', TIMEOUT, { when: (range) => range.getRow() === 1 && range.getColumn() === ADDED_AT });
    assert.deepEqual(h.apply([qty('Salt', 1, 'kg')]), { ok: false, error: `internal: ${TIMEOUT}` });
    assert.deepEqual(h.header(), [...V3_HEADER, '', 'Notes']);
    assert.deepEqual(h.list(), old.list, 'lists still read every item');
    assert.deepEqual(h.listV2(), old.listV2);

    assert.deepEqual(statuses(h.apply([qty('Salt', 1, 'kg')])), ['applied']);
    assert.deepEqual(h.header(), [...HEADER, 'Notes'], 'no second column');
    assert.deepEqual(h.rows().slice(0, 3), old.items.slice(1).map(withAddedAt));
    assert.equal(h.rule(2, ADDED_AT), null);
    assert.equal(h.format(2, ADDED_AT), DATE_FORMAT);
  });

  it('a column between the other six: the phones are told to move it (not to add a title), then the next change adds Added At', () => {
    const { h } = v11Sheet();
    const items = h.sheet();
    items.insertColumnAfter(3);
    items.getRange(1, 4).setValue('Brand');
    const before = items.dump();
    const message = 'internal: keep the columns Item, Need, Unit, Type, Updated At, Updated By and Added At of the Items sheet next to' +
      ' each other: column D is between them (move it to the right of them).';
    assert.deepEqual(h.apply([qty('Rice', 3, 'kg')]), { ok: false, error: message });
    assert.deepEqual(items.dump(), before, 'nothing written, no column added');
    h.gas.setupSheets();
    assert.equal(h.logged('Warning: the phones cannot save changes until you fix the Items sheet: keep the columns'), 1);
    assert.equal(h.logged('has no Added At column'), 0);

    items.deleteColumn(4);
    assert.deepEqual(statuses(h.apply([qty('Rice', 3, 'kg')])), ['applied']);
    assert.deepEqual(h.header(), [...HEADER, 'Notes']);
  });

  it('when Items cannot get its Added At column (protected), changes fail with the sheet\'s message and nothing is written', () => {
    const { h, old } = v11Sheet();
    h.sheet().insertColumnAfter = () => {
      throw new Error(PROTECTED);
    };
    assert.deepEqual(h.apply([qty('Salt', 1, 'kg')]), { ok: false, error: `internal: ${PROTECTED}` });
    assert.deepEqual(h.sheet().dump(), old.items);
    assert.deepEqual(h.rows('History'), old.history);
    assert.deepEqual(h.list(), old.list);
    assert.equal(h.gas.lock.held, false);
  });
});

describe('the Added At column changed by hand', () => {
  /** A 1.1 sheet after the update, with Salt added since (Added At known) at row 5. */
  function updated() {
    const { h, old } = v11Sheet();
    assert.deepEqual(statuses(h.apply([qty('Salt', 1, 'kg', NOW - 10 * SECOND)])), ['applied']);
    h.gas.advance(MINUTE);
    return { h, old };
  }

  it('a cleared title: lists still send addedAt, and the next change puts the title back, keeping the dates', () => {
    const { h } = updated();
    h.sheet().getRange(1, ADDED_AT).setValue('');
    assert.equal(h.item('Salt').addedAt, NOW - 10 * SECOND);
    assert.deepEqual(statuses(h.apply([flag('Milk', false, NOW + MINUTE), qty('Jam', 1, 'pack', NOW + MINUTE)])), ['applied', 'applied']);
    assert.deepEqual(h.header(), [...HEADER, 'Notes'], 'no second column');
    assert.deepEqual(['Salt', 'Jam', 'Milk'].map((name) => h.item(name).addedAt), [NOW - 10 * SECOND, NOW + MINUTE, undefined]);
    assert.equal(h.cell(2, 8), 'basmati');
  });

  it('a renamed title: still the Added At column, no second one is added, new rows get it there', () => {
    const { h } = updated();
    h.sheet().getRange(1, ADDED_AT).setValue('Created');
    assert.equal(h.item('Salt').addedAt, NOW - 10 * SECOND);
    assert.deepEqual(statuses(h.apply([qty('Jam', 1, 'pack', NOW + MINUTE), qty('Salt', 2, 'kg', NOW + MINUTE)])), ['applied', 'applied']);
    h.gas.setupSheets();
    assert.deepEqual(h.header(), [...V3_HEADER, 'Created', 'Notes'], 'left as the person named it');
    assert.deepEqual(['Salt', 'Jam'].map((name) => h.item(name).addedAt), [NOW - 10 * SECOND, NOW + MINUTE]);
    h.gas.handEdit({ row: 7, col: 1, value: 'Bread' });
    assert.deepEqual([h.item('Bread').updatedAt, h.item('Bread').addedAt], [NOW + 2 * MINUTE, NOW + 2 * MINUTE], 'onEdit finds it too');
    assert.equal(h.logged('the phones cannot save changes'), 0);
  });

  it('renamed before any item was added, it cannot be told from a column of one\'s own: a new Added At goes before it, nothing is lost', () => {
    const { h } = v11Sheet();
    h.gas.setupSheets();
    h.sheet().getRange(1, ADDED_AT).setValue('Created');
    assert.deepEqual(statuses(h.apply([qty('Salt', 1, 'kg')])), ['applied']);
    assert.deepEqual(h.header(), [...HEADER, 'Created', 'Notes']);
    assert.equal(h.cell(2, 9), 'basmati');
    assert.equal(h.item('Salt').addedAt, NOW);
  });

  it('a column titled Added At elsewhere (one of the person\'s own) is moved next to Updated By and used', () => {
    const { h } = v11Sheet();
    h.sheet().getRange(1, 9).setValue('added at');
    h.sheet().getRange(2, 9).setValue(new Date(NOW - DAY));
    assert.equal(h.item('Rice').addedAt, NOW - DAY, 'read where it is before anything runs');
    h.gas.setupSheets();
    assert.deepEqual(h.header(), [...V3_HEADER, 'added at', 'Notes']);
    assert.equal(h.logged('moved the Added At column of the Items sheet from column I to column G, right after Updated By'), 1);
    assert.equal(h.logged('the phones cannot save changes'), 0);
    assert.deepEqual([ms(h.cell(2, ADDED_AT)), h.cell(2, 8)], [NOW - DAY, 'basmati']);
    assert.deepEqual(statuses(h.apply([qty('Rice', 4, 'kg'), qty('Salt', 1, 'kg')])), ['applied', 'applied']);
    assert.deepEqual(['Rice', 'Salt'].map((name) => h.item(name).addedAt), [NOW - DAY, NOW]);
  });

  it('setupSheets warns about a rule on Added At that rejects input, like the other columns', () => {
    const { h } = updated();
    h.gas.addListValidation({ column: ADDED_AT, values: ['x'] });
    h.gas.setupSheets();
    assert.ok(h.gas.logs.some((line) => line.startsWith('[warn]') && line.includes('data validation on the Added At column of the Items sheet')));
  });
});

describe('upserts that create a row', () => {
  it('write Added At = Updated At, whichever app sends them (1.2, 1.1 without a hint, before units)', () => {
    const h = harness();
    const changes = [qty('Rice', 2, 'kg', NOW - 3 * MINUTE), flag('Bread', true, NOW - 2 * MINUTE)];
    const res = h.apply(changes, 'Phone 1.1');
    const old = h.applyV2([integer('Eggs', 6, NOW - MINUTE), flag('Tea', false, NOW - MINUTE)], 'Old phone');
    assert.deepEqual([...statuses(res), ...statuses(old)], ['applied', 'applied', 'applied', 'applied']);
    assert.deepEqual(h.header(), HEADER);
    assert.deepEqual(h.rows().map((r) => [r[0], ms(r[4]), ms(r[6])]),
      [['Rice', NOW - 3 * MINUTE, NOW - 3 * MINUTE], ['Bread', NOW - 2 * MINUTE, NOW - 2 * MINUTE], ['Eggs', NOW - MINUTE, NOW - MINUTE], ['Tea', NOW - MINUTE, NOW - MINUTE]]);
    assert.deepEqual([2, 3, 4, 5].map((row) => h.format(row, ADDED_AT)), Array(4).fill(DATE_FORMAT));
    assert.deepEqual(h.list().items, [
      { name: 'Rice', type: 'quantity', value: 2, unit: 'kg', updatedAt: NOW - 3 * MINUTE, updatedBy: 'Phone 1.1', addedAt: NOW - 3 * MINUTE },
      { name: 'Bread', type: 'boolean', value: true, updatedAt: NOW - 2 * MINUTE, updatedBy: 'Phone 1.1', addedAt: NOW - 2 * MINUTE },
      { name: 'Eggs', type: 'quantity', value: 6, unit: '', updatedAt: NOW - MINUTE, updatedBy: 'Old phone', addedAt: NOW - MINUTE },
      { name: 'Tea', type: 'boolean', value: false, updatedAt: NOW - MINUTE, updatedBy: 'Old phone', addedAt: NOW - MINUTE },
    ]);
    assert.equal('version' in old, false);
    assert.ok(old.items.every((i) => !('addedAt' in i)), 'an app from before units gets the items as before');
    assert.deepEqual(old.items, h.listV2().items);
    assert.deepEqual(h.rows('History').map((r) => r[2]), ['added', 'added', 'added', 'added']);
  });

  it('take the "addedAt" hint when 0 < addedAt <= the change\'s time and ignore it otherwise, never failing the change', () => {
    const h = harness();
    const time = NOW - MINUTE;
    const cases = [
      ['Earlier', NOW - DAY, NOW - DAY],
      ['Same', time, time],
      ['Later', time + 1, time],
      ['Zero', 0, time],
      ['Negative', -5, time],
      ['Text', String(NOW - DAY), time],
      ['Null', null, time],
      ['Flag', true, time],
      ['Object', { at: NOW - DAY }, time],
      ['Fraction', NOW - DAY + 0.7, NOW - DAY],
      ['Below one', 0.5, time],
      ['Huge', 1e300, time],
    ];
    const res = h.apply(cases.map(([name, hint]) => flag(name, true, time, { addedAt: hint })));
    assert.deepEqual(statuses(res), cases.map(() => 'applied'));
    const listed = h.list();
    assert.deepEqual(listed.items.map((i) => [i.name, i.addedAt, i.updatedAt]), cases.map(([name, , expected]) => [name, expected, time]));
    assert.deepEqual(h.rows().map((r) => ms(r[6])), cases.map(([, , expected]) => expected), 'the sheet holds the same dates');
    assert.deepEqual(res.items, listed.items);
  });

  it('the hint is checked against the time as stored: a phone clock ahead of the server counts as the server\'s', () => {
    const h = harness();
    const ahead = NOW + HOUR;
    assert.deepEqual(statuses(h.apply([flag('A', true, ahead, { addedAt: NOW + 30 * MINUTE }), flag('B', true, ahead, { addedAt: NOW - HOUR })])),
      ['applied', 'applied']);
    assert.deepEqual(h.list().items.map((i) => [i.name, i.updatedAt, i.addedAt]), [['A', NOW, NOW], ['B', NOW, NOW - HOUR]],
      'Added At is never later than Updated At');
  });

  it('a change to an item that exists never touches its Added At, hint or not', () => {
    const h = currentSheet();
    const changes = [
      qty('Rice', 3, 'kg', NOW, { addedAt: NOW - DAY }), // a hint for a row that exists
      qty('Rice', 3, 'g', NOW + 10 * SECOND),
      integer('Rice', 4, NOW + 20 * SECOND),
      flag('Rice', true, NOW + 30 * SECOND),
      flag(' rice ', false, NOW + 40 * SECOND),
    ];
    changes.forEach((change, i) => {
      h.gas.advance(10 * SECOND);
      const res = i === 2 ? h.applyV2([change], 'Old phone') : h.apply([change], 'Shop');
      assert.deepEqual(statuses(res), ['applied']);
      assert.equal(ms(h.cell(2, ADDED_AT)), NOW - HOUR, `change ${i}`);
    });
    assert.deepEqual(h.item('rice'), { name: 'rice', type: 'boolean', value: false, updatedAt: NOW + 40 * SECOND, updatedBy: 'Shop', addedAt: NOW - HOUR });
    assert.deepEqual(h.rows('History').map((r) => r[2]).slice(-5), Array(5).fill('updated'));
  });

  it('a re-add after a delete gets a new Added At; Undo of the delete with its addedAt keeps the old one', () => {
    const h = harness();
    h.apply([qty('Rice', 2, 'kg', NOW - DAY)]);
    h.apply([remove('Rice', NOW - HOUR)]);
    h.apply([qty('Rice', 1, 'kg', NOW - 30 * MINUTE)]);
    assert.deepEqual([h.item('Rice').addedAt, h.item('Rice').updatedAt], [NOW - 30 * MINUTE, NOW - 30 * MINUTE], 're-added: Added');
    h.apply([remove('Rice', NOW - 20 * MINUTE)]);
    h.apply([qty('Rice', 1, 'kg', NOW - 10 * MINUTE, { addedAt: NOW - 30 * MINUTE })]); // Undo
    assert.deepEqual([h.item('Rice').addedAt, h.item('Rice').updatedAt], [NOW - 30 * MINUTE, NOW - 10 * MINUTE], 'Undo: Updated');
    assert.deepEqual(h.rows('History').map((r) => r[2]), ['added', 'deleted', 'added', 'deleted', 'added'], 'History as before');
  });

  it('a rename in the app (delete + upsert with the old item\'s addedAt) keeps when the item was added', () => {
    const h = currentSheet();
    const res = h.apply([remove('Milk', NOW), flag('Oat milk', true, NOW, { addedAt: NOW - HOUR })]);
    assert.deepEqual(statuses(res), ['applied', 'applied']);
    assert.deepEqual(h.item('Oat milk', res), { name: 'Oat milk', type: 'boolean', value: true, updatedAt: NOW, updatedBy: 'Home', addedAt: NOW - HOUR });
    assert.deepEqual(h.rows().map((r) => [r[0], ms(r[6])]), [['Rice', NOW - HOUR], ['Tea', NOW - HOUR], ['Oat milk', NOW - HOUR]]);
  });

  it('in one batch: two upserts of a new name keep the first one\'s time, a delete and a re-add start again', () => {
    const h = currentSheet();
    const res = h.apply([
      flag('Jam', true, NOW - 2 * MINUTE),
      flag('Jam', false, NOW - MINUTE, { addedAt: NOW - DAY }),
      remove('Tea', NOW - MINUTE),
      qty('Tea', 2, 'pack', NOW),
    ]);
    assert.deepEqual(statuses(res), ['applied', 'applied', 'applied', 'applied']);
    assert.deepEqual(['Jam', 'Tea'].map((name) => [h.item(name).addedAt, h.item(name).updatedAt]),
      [[NOW - 2 * MINUTE, NOW - MINUTE], [NOW, NOW]]);
    assert.deepEqual(res.items, h.list().items);
  });

  it('a change never alters what a person put into Added At (text stays text, a date keeps its format)', () => {
    const h = currentSheet();
    const items = h.sheet();
    items.getRange(2, ADDED_AT).setValue("'Jan 5"); // text that Sheets would read as a date if written without the apostrophe
    items.getRange(3, ADDED_AT).setValue(42);
    items.getRange(4, ADDED_AT).setNumberFormat('@').setValue('unknown');
    items.getRange(5, 1, 1, 7).setValues([['Salt', 1, 'kg', 'quantity', new Date(NOW - HOUR), 'Home', new Date(NOW - DAY)]]);
    items.getRange(5, ADDED_AT).setNumberFormat('d mmm yyyy');
    const res = h.apply([qty('Rice', 3, 'kg'), flag('Milk', false), qty('Tea', 2, 'pack'), qty('Salt', 2, 'kg')]);
    assert.deepEqual(statuses(res), ['applied', 'applied', 'applied', 'applied']);
    assert.deepEqual([2, 3, 4, 5].map((row) => ms(h.cell(row, ADDED_AT))), ['Jan 5', 42, 'unknown', NOW - DAY]);
    assert.deepEqual([h.format(4, ADDED_AT), h.format(5, ADDED_AT)], ['@', 'd mmm yyyy']);
    assert.deepEqual(res.items.map((i) => i.addedAt), [undefined, undefined, undefined, NOW - DAY]);
  });

  it('applied one at a time (the sheet refused the batch), a new row gets the same Added At', () => {
    const h = currentSheet();
    h.gas.addListValidation({ column: 4, row: 2, rows: 1, values: ['Boolean', 'Integer'], helpText: 'Choose Boolean or Integer.' });
    const salt = qty('Salt', 1, 'kg', NOW - MINUTE, { addedAt: NOW - DAY });
    const res = h.apply([qty('Rice', 5, 'kg'), salt]);
    assert.deepEqual(statuses(res), ['error', 'applied']);
    assert.deepEqual([h.item('Salt').addedAt, h.item('Rice').addedAt], [NOW - DAY, NOW - HOUR]);
    assert.deepEqual(h.rows('History').at(-1).slice(1, 3), ['Salt', 'added']);
  });
});

describe('rows typed by hand (onEdit)', () => {
  it('a new row typed below the list gets Added At = the Updated At it is stamped with; later edits keep it', () => {
    const h = currentSheet();
    h.gas.handEdit({ row: 5, col: 1, value: 'Bread' });
    const bread = h.rows()[3];
    assert.deepEqual([bread[0], ms(bread[4]), bread[5], ms(bread[6])], ['Bread', NOW + MINUTE, 'Sheet', NOW + MINUTE]);
    assert.equal(h.format(5, ADDED_AT), DATE_FORMAT);
    assert.deepEqual(h.item('Bread'), { name: 'Bread', type: 'boolean', value: false, updatedAt: NOW + MINUTE, updatedBy: 'Sheet', addedAt: NOW + MINUTE });
    h.gas.advance(11 * MINUTE);
    h.gas.handEdit({ row: 5, col: 2, value: true });
    assert.deepEqual([h.item('Bread').updatedAt, h.item('Bread').addedAt], [NOW + 12 * MINUTE, NOW + MINUTE]);
  });

  it('a row typed one cell at a time (Item, Need, Unit, Type seconds apart) is still just added', () => {
    const h = currentSheet();
    h.gas.handEdit({ row: 5, col: 1, value: 'Eggs' });
    h.gas.advance(4 * SECOND);
    h.gas.handEdit({ row: 5, col: 2, value: 12 });
    h.gas.advance(3 * SECOND);
    h.gas.handEdit({ row: 5, col: 3, value: 'pcs' });
    h.gas.advance(3 * SECOND);
    h.gas.handEdit({ row: 5, col: 4, value: 'quantity' });
    const end = NOW + MINUTE + 10 * SECOND;
    assert.deepEqual(h.item('Eggs'), { name: 'Eggs', type: 'quantity', value: 12, unit: 'pcs', updatedAt: end, updatedBy: 'Sheet', addedAt: end });
    // Up to 10 minutes after it was last typed, filling it in still counts as adding it.
    h.gas.advance(9 * MINUTE);
    h.gas.handEdit({ row: 5, col: 2, value: 6 });
    assert.deepEqual([h.item('Eggs').updatedAt, h.item('Eggs').addedAt], [end + 9 * MINUTE, end + 9 * MINUTE]);
    // Later, a hand edit is an update.
    h.gas.advance(15 * MINUTE);
    h.gas.handEdit({ row: 5, col: 2, value: 10 });
    assert.deepEqual([h.item('Eggs').updatedAt, h.item('Eggs').addedAt], [end + 24 * MINUTE, end + 9 * MINUTE]);
  });

  it('a row typed by hand that a phone changed meanwhile, or one a phone added, keeps its Added At when edited by hand', () => {
    const h = currentSheet();
    h.gas.handEdit({ row: 5, col: 1, value: 'Eggs' });
    h.gas.advance(SECOND);
    h.apply([qty('Eggs', 6, 'pcs', NOW + MINUTE + SECOND)], 'Shop');
    h.gas.advance(SECOND);
    h.gas.handEdit({ row: 5, col: 2, value: 12 });
    assert.deepEqual([h.item('Eggs').updatedAt, h.item('Eggs').updatedBy, h.item('Eggs').addedAt],
      [NOW + MINUTE + 2 * SECOND, 'Sheet', NOW + MINUTE]);
    // Salt added from a phone a second ago, then changed by hand: "Updated by Sheet".
    h.apply([qty('Salt', 1, 'kg', NOW + MINUTE + 2 * SECOND)]);
    h.gas.advance(SECOND);
    h.gas.handEdit({ row: 6, col: 2, value: 2 });
    assert.deepEqual([h.item('Salt').updatedAt, h.item('Salt').addedAt], [NOW + MINUTE + 3 * SECOND, NOW + MINUTE + 2 * SECOND]);
  });

  it('a name typed into an Item cell cleared by hand is a new item: it does not take the removed item\'s Added At', () => {
    const h = currentSheet();
    h.gas.handEdit({ row: 3, col: 1, value: '' }); // Milk cleared (the sheet's way to remove an item)
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['milk']);
    assert.equal(ms(h.cell(3, ADDED_AT)), NOW - HOUR, 'the cleared row keeps its cells');
    h.gas.advance(DAY);
    h.gas.handEdit({ row: 3, col: 1, value: 'Eggs' });
    assert.deepEqual([h.item('Eggs').updatedAt, h.item('Eggs').updatedBy, h.item('Eggs').addedAt], [NOW + MINUTE + DAY, 'Sheet', NOW + MINUTE + DAY]);
    // The same for the old name typed again, like a re-add in the app.
    h.gas.handEdit({ row: 4, col: 1, value: '' }); // Tea cleared
    h.gas.advance(DAY);
    h.gas.handEdit({ row: 4, col: 1, value: 'Tea' });
    assert.deepEqual([h.item('Tea').updatedAt, h.item('Tea').addedAt], [NOW + MINUTE + 2 * DAY, NOW + MINUTE + 2 * DAY]);
    // A rename (the Item cell had a name) is the same row: Rice's Added At stays.
    h.gas.handEdit({ row: 2, col: 1, value: 'Basmati rice' });
    assert.deepEqual([h.item('Basmati rice').updatedAt, h.item('Basmati rice').addedAt], [NOW + MINUTE + 2 * DAY, NOW - HOUR]);
  });

  it('an edit of a row that exists leaves its Added At alone, known or not', () => {
    const { h } = v11Sheet();
    h.apply([qty('Salt', 1, 'kg')]); // adds the column; old rows keep it blank
    h.gas.advance(MINUTE);
    h.gas.handEdit({ row: 2, col: 2, value: 5 });
    h.gas.handEdit({ row: 5, col: 2, value: 3 });
    assert.deepEqual([h.item('Rice').updatedAt, h.item('Rice').addedAt], [NOW + 2 * MINUTE, undefined]);
    assert.deepEqual([h.item('Salt').updatedAt, h.item('Salt').addedAt], [NOW + 2 * MINUTE, NOW]);
    assert.equal(h.cell(2, ADDED_AT), '');
  });

  it('a pasted block: only the named rows without Updated At and Added At get Added At', () => {
    const h = currentSheet();
    h.gas.handEdit({ row: 3, col: 1, values: [['Milk', false], ['Tea', 3], ['Eggs', 12], ['', 3], ['Jam', true]] });
    const rows = h.rows();
    assert.deepEqual(rows.map((r) => [r[0], ms(r[4]), r[5], ms(r[6])]), [
      ['Rice', NOW - HOUR, 'Home', NOW - HOUR],
      ['Milk', NOW + MINUTE, 'Sheet', NOW - HOUR],
      ['Tea', NOW + MINUTE, 'Sheet', NOW - HOUR],
      ['Eggs', NOW + MINUTE, 'Sheet', NOW + MINUTE],
      ['', '', '', ''],
      ['Jam', NOW + MINUTE, 'Sheet', NOW + MINUTE],
    ]);
  });

  it('an Item cell renamed by hand is the same row: it keeps its Added At (the old name is remembered as deleted)', () => {
    const h = currentSheet();
    h.gas.handEdit({ row: 4, col: 1, value: 'Green tea' });
    assert.deepEqual([h.item('Green tea').updatedAt, h.item('Green tea').addedAt], [NOW + MINUTE, NOW - HOUR]);
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['tea']);
  });

  it('a row typed with an Added At of its own keeps it; an edit of Added At itself is not stamped', () => {
    const h = currentSheet();
    h.gas.handEdit({ row: 5, col: ADDED_AT, value: new Date(NOW - DAY) });
    h.gas.handEdit({ row: 5, col: 1, value: 'Bread' });
    assert.deepEqual([h.item('Bread').updatedAt, h.item('Bread').addedAt], [NOW + MINUTE, NOW - DAY]);
    h.gas.handEdit({ row: 2, col: ADDED_AT, value: new Date(NOW - DAY) });
    assert.deepEqual([h.item('Rice').updatedAt, h.item('Rice').updatedBy, h.item('Rice').addedAt], [NOW - HOUR, 'Home', NOW - DAY]);
  });

  it('a row whose Updated At was typed too is not a new row', () => {
    const h = currentSheet();
    h.gas.handEdit({ row: 5, col: 1, values: [['Bread', true, '', 'boolean', new Date(NOW - DAY)]] });
    assert.deepEqual([h.item('Bread').updatedAt, h.item('Bread').addedAt], [NOW + MINUTE, undefined]);
  });
});

describe('lists', () => {
  it('version 3 sends addedAt (last) only for an Added At holding a date after 1970; version 2 never', () => {
    const h = harness();
    h.apply([qty('Rice', 2, 'kg', NOW - HOUR), flag('Milk', true, NOW - HOUR), qty('Tea', 1, 'pack', NOW - HOUR), flag('Jam', true, NOW - HOUR),
      qty('Oil', 1, 'L', NOW - HOUR), flag('Salt', true, NOW - HOUR)]);
    const items = h.sheet();
    items.getRange(3, ADDED_AT).setValue('');
    items.getRange(4, ADDED_AT).setValue("'yesterday");
    items.getRange(5, ADDED_AT).setValue(45000);
    items.getRange(6, ADDED_AT).setValue(new Date(-DAY));
    items.getRange(7, ADDED_AT).setValue(true);
    const listed = h.list();
    assert.deepEqual(listed.items.map((i) => [i.name, i.addedAt]),
      [['Rice', NOW - HOUR], ['Milk', undefined], ['Tea', undefined], ['Jam', undefined], ['Oil', undefined], ['Salt', undefined]]);
    assert.deepEqual(Object.keys(listed.items[0]), ['name', 'type', 'value', 'unit', 'updatedAt', 'updatedBy', 'addedAt']);
    assert.deepEqual(Object.keys(listed.items[1]), ['name', 'type', 'value', 'updatedAt', 'updatedBy']);
    assert.equal(listed.revision, md5(JSON.stringify(listed.items)));
    const v2 = h.listV2();
    assert.ok(v2.items.every((i) => Object.keys(i).join() === 'name,type,value,updatedAt,updatedBy'));
    assert.equal(v2.revision, md5(JSON.stringify(v2.items)));
  });

  it('a change of Added At alone changes what a version 3 app sees, not what an app from before units sees', () => {
    const h = currentSheet();
    const v3 = h.list();
    const v2 = h.listV2();
    h.gas.handEdit({ row: 2, col: ADDED_AT, value: new Date(NOW - DAY) });
    assert.notEqual(h.list().revision, v3.revision);
    assert.equal(h.listV2().revision, v2.revision);
    assert.deepEqual(h.listV2(), v2);
  });
});

describe('the script of app 1.1 still answering the phones (setupSheets run before the new version was deployed)', () => {
  it('writes its six columns by title, leaves Added At alone, and its rows are read right afterwards', () => {
    const { h } = v11Sheet();
    assert.deepEqual(statuses(h.apply([qty('Jam', 1, 'pack', NOW - 30 * SECOND)])), ['applied']); // adds the column, Jam knows its Added At
    h.gas.setupSheets();
    h.gas.reloadCode(V3_CODE); // /exec still runs the deployed 1.1 version
    h.gas.advance(MINUTE);
    const res = h.apply([qty('Salt', 1, 'kg', NOW + MINUTE), qty('Jam', 2, 'pack', NOW + MINUTE), qty('Rice', 4, 'kg', NOW + MINUTE)]);
    assert.deepEqual(statuses(res), ['applied', 'applied', 'applied']);
    assert.ok(res.items.every((i) => !('addedAt' in i)), 'the 1.1 script sends no addedAt');
    assert.deepEqual(h.header(), [...HEADER, 'Notes']);
    const row = (name) => h.rows().find((r) => r[0] === name);
    assert.deepEqual([row('Salt')[6], row('Salt')[7]], ['', ''], 'its new row: Added At left blank');
    assert.deepEqual([ms(row('Jam')[6]), row('Rice')[6], row('Rice')[7]], [NOW - 30 * SECOND, '', 'basmati'], 'and the others left alone');

    h.gas.reloadCode(); // deployed at last
    const listed = h.list();
    assert.deepEqual(['Salt', 'Jam', 'Rice'].map((name) => h.item(name, listed).addedAt), [undefined, NOW - 30 * SECOND, undefined]);
    assert.deepEqual(statuses(h.apply([qty('Oil', 1, 'L', NOW + MINUTE)])), ['applied']);
    assert.equal(h.item('Oil').addedAt, NOW + MINUTE);
  });
});
