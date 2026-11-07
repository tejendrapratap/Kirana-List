'use strict';
/**
 * Updating a sheet set up by the script from before units (protocol 2): Items "Item | Need | Type |
 * Updated At | Updated By" and History "Time | Item | Action | Need | Type | By | Change ID". The sheet
 * is built by that very script (test/fixtures/Code.v2.gs), then the current Code.gs replaces it, as
 * when the user pastes the new code. The new script reads the old layout as it is and adds the Unit
 * column right after Need (from setupSheets or the first apply), under the script lock, keeping every
 * value, rule and note of the person's own. The same update adds the Added At column right after
 * Updated By (see added-at.test.js), so a column of one's own after Updated By moves one more column.
 */
const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { loadCodeGs, getEvent, postEvent, DEFAULT_NOW } = require('./fake-gas');

const TOKEN = 'test-secret-token';
const NOW = DEFAULT_NOW;
const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const V2_CODE = path.join(__dirname, 'fixtures', 'Code.v2.gs');
const OLD_ITEMS_HEADER = ['Item', 'Need', 'Type', 'Updated At', 'Updated By', 'Notes'];
const NEW_ITEMS_HEADER = ['Item', 'Need', 'Unit', 'Type', 'Updated At', 'Updated By', 'Added At', 'Notes'];
const NEW_HISTORY_HEADER = ['Time', 'Item', 'Action', 'Need', 'Unit', 'Type', 'By', 'Change ID'];
const OLD_HISTORY_HEADER = ['Time', 'Item', 'Action', 'Need', 'Type', 'By', 'Change ID'];
const TIMEOUT = 'Service Spreadsheets timed out while accessing document with id fake-spreadsheet-id.';
const PROTECTED = 'You are trying to edit a protected cell or object. Please contact the spreadsheet owner to remove protection if you need to edit.';

function harness() {
  const gas = loadCodeGs({ token: TOKEN, codePath: V2_CODE });
  const parse = (output) => JSON.parse(output.getContent());
  const h = {
    gas,
    get: (params) => parse(gas.doGet(getEvent({ token: TOKEN, ...params }))),
    list: () => h.get({ action: 'list', v: '3' }),
    listV2: () => h.get({ action: 'list' }),
    post: (body) => parse(gas.doPost(postEvent(JSON.stringify({ token: TOKEN, action: 'apply', device: 'Home', ...body })))),
    apply: (changes) => h.post({ protocol: 3, changes }),
    applyV2: (changes) => h.post({ changes }),
    sheet: (name) => gas.spreadsheet.getSheetByName(name),
    rows: (name = 'Items') => h.sheet(name).dump().slice(1),
    header: (name = 'Items') => h.sheet(name).dump()[0],
    rule: (row, col) => {
      const rule = h.sheet('Items').getRange(row, col).getDataValidation();
      return rule ? rule.getCriteriaType() : null;
    },
    logged: (text) => gas.logs.filter((line) => line.includes(text)).length,
  };
  return h;
}

let idCounter = 0;
const nextId = () => `00000000-0000-4000-c000-${String(++idCounter).padStart(12, '0')}`;
const upsert = (name, type, value, time = NOW) => ({ id: nextId(), op: 'upsert', name, type, value, time });
const qty = (name, value, unit, time = NOW) => ({ id: nextId(), op: 'upsert', name, type: 'quantity', value, unit, time });
const remove = (name, time = NOW) => ({ id: nextId(), op: 'delete', name, time });
const statuses = (response) => response.results.map((r) => r.status);
/** An old Items or History row with an empty Unit cell inserted after Need (index 1 or 3). */
const withUnit = (row, needIndex) => [...row.slice(0, needIndex + 1), '', ...row.slice(needIndex + 1)];
/** An old Items row (Item | Need | Type | Updated At | Updated By | Notes) in the new layout: Unit and Added At empty. */
const upgraded = (row) => {
  const unit = withUnit(row, 1);
  return [...unit.slice(0, 6), '', ...unit.slice(6)];
};

/**
 * The user's sheet today, written by the old script itself: Milk (ticked), Rice 2, Tea (unticked),
 * Eggs 0, and Soap deleted (a tombstone); plus what a person added by hand: a Notes column F, a
 * warning-only dropdown of their own on Type and checkboxes on the empty Need cells below the list.
 * Then the new Code.gs replaces the old one; nothing has run since.
 */
function legacySheet() {
  const h = harness();
  h.applyV2([
    upsert('Milk', 'boolean', true, NOW - 3 * HOUR),
    upsert('Rice', 'integer', 2, NOW - 3 * HOUR),
    upsert('Tea', 'boolean', false, NOW - 3 * HOUR),
    upsert('Eggs', 'integer', 0, NOW - 3 * HOUR),
    upsert('Soap', 'boolean', true, NOW - 3 * HOUR),
  ]);
  h.applyV2([remove('Soap', NOW - 2 * HOUR)]);
  const items = h.sheet('Items');
  items.getRange(1, 6).setValue('Notes');
  items.getRange(3, 6).setValue('basmati');
  h.gas.addListValidation({ column: 3, values: ['boolean', 'integer'], reject: false, helpText: 'boolean or integer' });
  items.getRange(6, 2, 20, 1).setDataValidation(h.gas.spreadsheetApp.newDataValidation().requireCheckbox().build());
  h.gas.advance(MINUTE);
  assert.deepEqual(h.header(), OLD_ITEMS_HEADER);
  assert.deepEqual(h.header('History'), OLD_HISTORY_HEADER);
  assert.equal(h.get({ action: 'ping' }).version, 2);
  const old = { list: h.listV2(), items: items.dump(), history: h.rows('History'), tombstones: h.rows('_Tombstones') };
  h.gas.reloadCode(); // the new Code.gs, pasted and deployed
  return { h, old };
}

describe('a sheet from before units, before anything runs', () => {
  it('is read as it is, for old and new apps alike, and left unchanged', () => {
    const { h, old } = legacySheet();
    assert.equal(h.get({ action: 'ping' }).version, 3);
    assert.deepEqual(h.listV2(), old.list, 'an old app sees exactly what it saw, revision included');
    const listed = h.list();
    assert.equal(listed.version, 3);
    assert.deepEqual(listed.items.map((i) => [i.name, i.type, i.value, i.unit]), [
      ['Milk', 'boolean', true, undefined],
      ['Rice', 'quantity', 2, ''],
      ['Tea', 'boolean', false, undefined],
      ['Eggs', 'quantity', 0, ''],
    ]);
    assert.deepEqual(h.sheet('Items').dump(), old.items, 'listing changes nothing');
  });

  it('a hand edit stamps the old layout\'s Updated At / Updated By', () => {
    const { h, old } = legacySheet();
    h.gas.handEdit({ row: 3, col: 2, value: 7 });
    const rice = h.rows()[1];
    assert.deepEqual([rice[1], rice[2], rice[3].getTime(), rice[4], rice[5]], [7, 'integer', NOW + MINUTE, 'Sheet', 'basmati']);
    assert.deepEqual(h.header(), OLD_ITEMS_HEADER, 'onEdit does not add the column');
    assert.deepEqual(h.rows()[0], old.items[1]);
  });
});

describe('adding the Unit column', () => {
  it('the first change adds it right after Need, keeping every value, rule, note and deletion', () => {
    const { h, old } = legacySheet();
    const add = upsert('Salt', 'integer', 1);
    const res = h.applyV2([add]);
    assert.deepEqual(res.results, [{ id: add.id, status: 'applied' }]);

    assert.deepEqual(h.header(), NEW_ITEMS_HEADER);
    const rows = h.rows();
    assert.deepEqual(rows.slice(0, 4), old.items.slice(1).map(upgraded), 'old rows: values moved along, Added At not known');
    assert.deepEqual([rows[4][0], rows[4][1], rows[4][2], rows[4][3], rows[4][5], rows[4][7]], ['Salt', 1, '', 'quantity', 'Home', '']);
    assert.deepEqual([rows[4][4].getTime(), rows[4][6].getTime()], [NOW, NOW], 'the new row: Added At = Updated At');

    // Need checkboxes stay on the yes/no rows and below the list; the person's Type dropdown moved to D.
    assert.deepEqual([2, 3, 4, 5, 10].map((row) => h.rule(row, 2)), ['CHECKBOX', null, 'CHECKBOX', null, 'CHECKBOX']);
    const typeRule = h.sheet('Items').getRange(2, 4).getDataValidation();
    assert.deepEqual([typeRule.getCriteriaType(), typeRule.getAllowInvalid(), typeRule.getHelpText()], ['VALUE_IN_LIST', true, 'boolean or integer']);
    assert.equal(h.rule(30, 4), 'VALUE_IN_LIST');
    // Unit: the units dropdown on quantity rows only, no copied checkboxes anywhere.
    assert.deepEqual([1, 2, 3, 4, 5, 6, 10, 30].map((row) => h.rule(row, 3)),
      [null, null, 'VALUE_IN_LIST', null, 'VALUE_IN_LIST', 'VALUE_IN_LIST', null, null]);

    assert.deepEqual(h.header('History'), NEW_HISTORY_HEADER);
    const history = h.rows('History');
    assert.deepEqual(history.slice(0, old.history.length), old.history.map((row) => withUnit(row, 3)));
    assert.deepEqual(history.at(-1).slice(1), ['Salt', 'added', 1, '', 'quantity', 'Home', add.id]);
    assert.deepEqual(h.rows('_Tombstones'), old.tombstones);
    assert.deepEqual(statuses(h.applyV2([upsert('Soap', 'boolean', true, NOW - 3 * HOUR)])), ['stale'], 'the deletion still holds');

    assert.equal(h.logged('added the Unit column (column C) to the Items sheet'), 1);
    assert.equal(h.logged('added the Added At column (column G) to the Items sheet'), 1);
    assert.equal(h.logged('added the Unit column to the History sheet'), 1);
    assert.deepEqual(res.items, h.listV2().items);
  });

  it('a change from an app with units is written with its unit right away', () => {
    const { h } = legacySheet();
    const res = h.apply([qty('Rice', 1.5, 'Kgs')]);
    assert.equal(res.version, 3);
    assert.deepEqual(statuses(res), ['applied']);
    assert.deepEqual(h.rows()[1].slice(0, 4), ['Rice', 1.5, 'kg', 'quantity']);
    assert.equal(h.rows()[1][6], '', 'an existing row: Added At stays unknown');
    assert.equal(h.rows()[1][7], 'basmati');
    assert.equal(h.sheet('Items').getRange(3, 2).getNumberFormat(), 'General');
    assert.deepEqual(res.items.find((i) => i.name === 'Rice').unit, 'kg');
  });

  it('setupSheets adds it once; both kinds of app see the same list before and after', () => {
    const { h, old } = legacySheet();
    const v3Before = h.list();
    assert.equal(h.gas.setupSheets(), 'Groceries');
    assert.deepEqual(h.header(), NEW_ITEMS_HEADER);
    assert.deepEqual(h.header('History'), NEW_HISTORY_HEADER);
    assert.equal(h.logged('Added the Unit column (right after Need) to the Items sheet.'), 1);
    assert.equal(h.logged('Added the Unit column (right after Need) to the History sheet.'), 1);
    assert.equal(h.logged('Added the Added At column (right after Updated By) to the Items sheet.'), 1);
    assert.deepEqual(h.listV2(), old.list, 'same items, same revision for an old app');
    assert.deepEqual(h.list(), v3Before, 'and for a new one');

    h.gas.setupSheets();
    assert.deepEqual(statuses(h.applyV2([upsert('Salt', 'integer', 1)])), ['applied']);
    assert.deepEqual(h.header(), NEW_ITEMS_HEADER, 'idempotent');
    assert.deepEqual(h.header('History'), NEW_HISTORY_HEADER);
    assert.equal(h.logged('Added the Unit column (right after Need) to the Items sheet.'), 1);
    assert.equal(h.logged('Added the Added At column (right after Updated By) to the Items sheet.'), 1);
    assert.equal(h.gas.lock.held, false);
  });

  it('waits for the script lock: while another execution holds it nothing changes', () => {
    const { h, old } = legacySheet();
    h.gas.lock.forceFail();
    assert.deepEqual(h.applyV2([upsert('Salt', 'integer', 1)]), { ok: false, error: 'busy' });
    assert.deepEqual(h.sheet('Items').dump(), old.items);
    assert.deepEqual(h.listV2(), old.list, 'lists keep working meanwhile');
    h.gas.lock.forceFail(false);
    assert.deepEqual(statuses(h.applyV2([upsert('Salt', 'integer', 1)])), ['applied']);
    assert.deepEqual(h.header(), NEW_ITEMS_HEADER);
  });

  it('an apply that waited for the one adding the column does not add another', () => {
    const { h } = legacySheet();
    let second = null;
    h.gas.afterNextRead('Items', () => {
      h.gas.lock.whenFree(() => {
        second = h.apply([qty('Rice', 3, 'kg')]);
      });
    }, { when: (range) => range.getRow() === 1 });
    const first = h.applyV2([upsert('Salt', 'integer', 1)]);
    assert.deepEqual(statuses(first), ['applied']);
    assert.ok(second, 'the second apply ran after the first released the lock');
    assert.deepEqual(statuses(second), ['applied']);
    assert.deepEqual(h.header(), NEW_ITEMS_HEADER);
    assert.deepEqual(h.rows()[1].slice(0, 4), ['Rice', 3, 'kg', 'quantity']);
    assert.equal(h.logged('added the Unit column'), 2, 'once for Items, once for History');
  });

  it('a list while the column is being added still reads every item', () => {
    const { h, old } = legacySheet();
    const v3Before = h.list();
    const items = h.sheet('Items');
    const insert = items.insertColumnAfter.bind(items);
    const during = [];
    items.insertColumnAfter = (position) => {
      const result = insert(position);
      // The new column has no title yet: Item | Need | (blank) | Type | ..., then ... | Updated By | (blank) | Notes
      during.push({ header: items.dump()[0], v2: h.listV2(), v3: h.list() });
      return result;
    };
    h.applyV2([upsert('Salt', 'integer', 1)]);
    assert.deepEqual(during.map((d) => d.header), [
      ['Item', 'Need', '', 'Type', 'Updated At', 'Updated By', 'Notes'],
      ['Item', 'Need', 'Unit', 'Type', 'Updated At', 'Updated By', '', 'Notes'],
    ]);
    during.forEach((d) => {
      assert.deepEqual(d.v2, old.list);
      assert.deepEqual(d.v3, v3Before);
    });
  });

  it('a hand edit made meanwhile is kept, and its onEdit (which waited) stamps only what did not move', () => {
    const { h } = legacySheet();
    h.gas.afterNextRead('Items', () => {
      h.gas.handEdit({ row: 3, col: 2, value: 5 }); // Rice's Need: still B3 afterwards
      h.gas.handEdit({ row: 5, col: 3, value: 'boolean' }); // Eggs' Type: C5 becomes D5
    }, { when: (range) => range.getRow() === 1 });
    assert.deepEqual(statuses(h.applyV2([upsert('Salt', 'integer', 1)])), ['applied']);
    const [, rice, , eggs] = h.rows();
    assert.deepEqual([rice[1], rice[2], rice[3], rice[4].getTime(), rice[5], rice[6], rice[7]], [5, '', 'integer', NOW + MINUTE, 'Sheet', '', 'basmati']);
    assert.deepEqual([eggs[1], eggs[2], eggs[3], eggs[5]], [0, '', 'boolean', 'Home'], 'kept, but not stamped: the cell moved');
    assert.deepEqual(h.list().items.map((i) => [i.name, i.value]).slice(1, 4), [['Rice', 5], ['Tea', false], ['Eggs', false]]);
    assert.equal(h.gas.lock.held, false);
  });

  it('an earlier attempt that stopped after inserting the column: that empty column is used', () => {
    const { h, old } = legacySheet();
    h.gas.failNextWrite('Items', TIMEOUT, { when: (range) => range.getRow() === 1 && range.getColumn() === 3 });
    assert.deepEqual(h.applyV2([upsert('Salt', 'integer', 1)]), { ok: false, error: `internal: ${TIMEOUT}` });
    assert.deepEqual(h.header(), ['Item', 'Need', '', 'Type', 'Updated At', 'Updated By', 'Notes']);
    assert.deepEqual(h.listV2(), old.list, 'lists still read every item');

    assert.deepEqual(statuses(h.applyV2([upsert('Salt', 'integer', 1)])), ['applied']);
    assert.deepEqual(h.header(), NEW_ITEMS_HEADER, 'no second column');
    assert.deepEqual(h.rows().slice(0, 4), old.items.slice(1).map(upgraded));
    assert.equal(h.rule(3, 3), 'VALUE_IN_LIST');
    assert.equal(h.rule(2, 3), null);
  });

  it('when Items cannot get its Unit column (protected), changes fail with the sheet\'s message and nothing is written', () => {
    const { h, old } = legacySheet();
    h.sheet('Items').insertColumnAfter = () => {
      throw new Error(PROTECTED);
    };
    assert.deepEqual(h.applyV2([upsert('Salt', 'integer', 1)]), { ok: false, error: `internal: ${PROTECTED}` });
    assert.deepEqual(h.sheet('Items').dump(), old.items);
    assert.deepEqual(h.rows('History'), old.history);
    assert.deepEqual(h.listV2(), old.list);
    assert.equal(h.gas.lock.held, false);
  });

  it('History is best effort: without its Unit column its rows go on in the old layout until it can be added', () => {
    const { h, old } = legacySheet();
    const history = h.sheet('History');
    history.insertColumnAfter = () => {
      throw new Error(PROTECTED);
    };
    const add = upsert('Salt', 'integer', 1);
    assert.deepEqual(statuses(h.applyV2([add])), ['applied']);
    assert.deepEqual(h.header(), NEW_ITEMS_HEADER);
    assert.deepEqual(h.header('History'), OLD_HISTORY_HEADER);
    assert.deepEqual(h.rows('History').at(-1).slice(1), ['Salt', 'added', 1, 'quantity', 'Home', add.id]);
    assert.equal(h.logged('could not add the Unit column to the History sheet'), 1);

    delete history.insertColumnAfter;
    const later = qty('Rice', 2.5, 'kg');
    assert.deepEqual(statuses(h.apply([later])), ['applied']);
    assert.deepEqual(h.header('History'), NEW_HISTORY_HEADER);
    assert.deepEqual(h.rows('History').slice(0, old.history.length), old.history.map((row) => withUnit(row, 3)));
    assert.deepEqual(h.rows('History').at(-1).slice(1), ['Rice', 'updated', 2.5, 'kg', 'quantity', 'Home', later.id]);
  });

  it('with KEEP_HISTORY = false an old History sheet is left as it is', () => {
    const { h } = legacySheet();
    h.gas.config.KEEP_HISTORY = false;
    assert.deepEqual(statuses(h.applyV2([upsert('Salt', 'integer', 1)])), ['applied']);
    assert.deepEqual(h.header(), NEW_ITEMS_HEADER);
    assert.deepEqual(h.header('History'), OLD_HISTORY_HEADER);
  });
});

describe('a sheet seeded in the old layout (seedV2Layout, as the mock server does)', () => {
  it('reads and migrates like one the old script wrote', () => {
    const h = harness();
    h.gas.reloadCode();
    h.gas.seedV2Layout({
      items: [['Milk', true, 'boolean', NOW - HOUR], ['Rice', 2, 'integer', NOW - HOUR, 'Anna'], { name: '1/2 Lemon', need: 1, type: 'integer' }],
      history: [[NOW - HOUR, 'Rice', 'added', 2, 'integer', 'Anna', 'id-1']],
    });
    assert.deepEqual(h.header().slice(0, 5), OLD_ITEMS_HEADER.slice(0, 5));
    assert.deepEqual(h.list().items.map((i) => [i.name, i.type, i.value, i.unit, i.updatedBy]), [
      ['Milk', 'boolean', true, undefined, 'Home'],
      ['Rice', 'quantity', 2, '', 'Anna'],
      ['1/2 Lemon', 'quantity', 1, '', 'Home'],
    ]);
    h.gas.setupSheets();
    assert.deepEqual(h.header().slice(0, 6), NEW_ITEMS_HEADER.slice(0, 6));
    assert.deepEqual([2, 3, 4].map((row) => [h.rule(row, 2), h.rule(row, 3)]),
      [['CHECKBOX', null], [null, 'VALUE_IN_LIST'], [null, 'VALUE_IN_LIST']]);
    assert.deepEqual(h.rows('History')[0].slice(1), ['Rice', 'added', 2, '', 'integer', 'Anna', 'id-1']);
  });
});

describe('the update done in another order, and the Unit column changed by hand', () => {
  it('setupSheets before the deploy: rows the still deployed old web app writes are read as it meant them', () => {
    const { h } = legacySheet();
    h.gas.setupSheets(); // saved, run from the editor ...
    assert.equal(h.logged('Deploy this code now if you have not yet'), 1, 'setupSheets says to deploy now');
    h.gas.reloadCode(V2_CODE); // ... but /exec still runs the deployed old version
    h.gas.advance(MINUTE);
    const rice = upsert('Rice', 'integer', 3, NOW + MINUTE);
    const salt = upsert('Salt', 'boolean', true, NOW + MINUTE);
    assert.deepEqual(statuses(h.applyV2([rice, salt])), ['applied', 'applied']);
    const shifted = h.rows()[1];
    assert.deepEqual([shifted[0], shifted[1], shifted[2], shifted[3].getTime(), shifted[4]], ['Rice', 3, 'integer', NOW + MINUTE, 'Home'],
      'the old code wrote A-E in its own order');

    h.gas.reloadCode(); // deployed at last
    h.gas.advance(MINUTE);
    const listed = h.list().items;
    assert.deepEqual(listed.find((i) => i.name === 'Rice'),
      { name: 'Rice', type: 'quantity', value: 3, unit: '', updatedAt: NOW + MINUTE, updatedBy: 'Home' });
    assert.deepEqual(listed.find((i) => i.name === 'Salt'),
      { name: 'Salt', type: 'boolean', value: true, updatedAt: NOW + MINUTE, updatedBy: 'Home' });
    assert.deepEqual(h.listV2().items.find((i) => i.name === 'Rice'),
      { name: 'Rice', type: 'integer', value: 3, updatedAt: NOW + MINUTE, updatedBy: 'Home' });
    assert.deepEqual(listed.find((i) => i.name === 'Tea').updatedAt, NOW - 3 * HOUR, 'rows it did not write read as before');

    assert.deepEqual(statuses(h.apply([qty('Rice', 1, 'kg', NOW)])), ['stale'], 'an older change still loses');
    assert.deepEqual(statuses(h.apply([qty('Rice', 2.5, 'kg', NOW + 2 * MINUTE)])), ['applied']);
    const fixed = h.rows()[1];
    assert.deepEqual([fixed[0], fixed[1], fixed[2], fixed[3], fixed[4].getTime(), fixed[5], fixed[6], fixed[7]],
      ['Rice', 2.5, 'kg', 'quantity', NOW + 2 * MINUTE, 'Home', '', 'basmati'], 'its next change writes the new layout');
  });

  it('a renamed Unit title: still the Unit column, no second one is added', () => {
    const { h } = legacySheet();
    assert.deepEqual(statuses(h.apply([qty('Rice', 2.5, 'kg')])), ['applied']);
    h.sheet('Items').getRange(1, 3).setValue('Units');
    h.gas.advance(MINUTE);
    assert.equal(h.list().items.find((i) => i.name === 'Rice').unit, 'kg');
    assert.deepEqual(statuses(h.apply([upsert('Milk', 'boolean', false, NOW + MINUTE)])), ['applied']);
    assert.deepEqual(statuses(h.applyV2([upsert('Rice', 'integer', 4, NOW + MINUTE)])), ['applied']);
    assert.deepEqual(h.header(), ['Item', 'Need', 'Units', 'Type', 'Updated At', 'Updated By', 'Added At', 'Notes'], 'left as the person named it');
    assert.deepEqual(h.rows()[1].slice(0, 4), ['Rice', 4, 'kg', 'quantity']);
    h.gas.advance(MINUTE);
    h.gas.handEdit({ row: 3, col: 3, value: 'g' });
    assert.deepEqual(h.list().items.find((i) => i.name === 'Rice').updatedBy, 'Sheet', 'a Unit edit is stamped');
    h.gas.setupSheets();
    assert.deepEqual(h.header()[2], 'Units');
  });

  it('a cleared Unit title: the next change puts the title back, keeping the units', () => {
    const { h } = legacySheet();
    assert.deepEqual(statuses(h.apply([qty('Rice', 2.5, 'kg')])), ['applied']);
    h.sheet('Items').getRange(1, 3).setValue('');
    assert.equal(h.list().items.find((i) => i.name === 'Rice').unit, 'kg');
    assert.deepEqual(statuses(h.apply([upsert('Milk', 'boolean', false, NOW + MINUTE)])), ['applied']);
    assert.deepEqual(h.header(), NEW_ITEMS_HEADER);
    assert.deepEqual(h.rows()[1].slice(0, 4), ['Rice', 2.5, 'kg', 'quantity']);
    assert.equal(h.rule(3, 3), 'VALUE_IN_LIST');
  });

  it('a Unit column of the person\'s own from before units is moved next to Need and used', () => {
    const { h } = legacySheet();
    const items = h.sheet('Items');
    items.getRange(1, 7).setValue('Unit');
    items.getRange(3, 7).setValue('Kgs');
    items.getRange(3, 8).setValue('aisle 4');
    assert.equal(h.list().items.find((i) => i.name === 'Rice').unit, 'kg', 'read where it is before anything runs');
    h.gas.setupSheets();
    assert.deepEqual(h.header().slice(0, 8), NEW_ITEMS_HEADER);
    assert.deepEqual(h.rows()[1].slice(0, 4), ['Rice', 2, 'Kgs', 'integer']);
    assert.deepEqual([h.rows()[1][7], h.rows()[1][8]], ['basmati', 'aisle 4'], 'the columns to the right keep their cells');
    assert.equal(h.logged('moved the Unit column of the Items sheet from column G to column C, right after Need'), 1);
    assert.equal(h.logged('the phones cannot save changes'), 0);
    assert.deepEqual(statuses(h.applyV2([upsert('Rice', 'integer', 3, NOW + MINUTE)])), ['applied']);
    assert.deepEqual(statuses(h.apply([qty('Tea', 1, 'pack', NOW + MINUTE)])), ['applied']);
    assert.deepEqual(h.list().items.filter((i) => i.type === 'quantity').map((i) => [i.name, i.value, i.unit]),
      [['Rice', 3, 'kg'], ['Tea', 1, 'pack'], ['Eggs', 0, '']]);
  });

  it('setupSheets warns when the Items columns are apart, and the phones are told what to move', () => {
    const { h } = legacySheet();
    assert.deepEqual(statuses(h.apply([qty('Rice', 2.5, 'kg')])), ['applied']);
    const items = h.sheet('Items');
    items.insertColumnAfter(3);
    items.getRange(1, 4).setValue('Brand');
    h.gas.setupSheets();
    assert.equal(h.logged('Warning: the phones cannot save changes until you fix the Items sheet: keep the columns Item, Need, Unit,' +
      ' Type, Updated At, Updated By and Added At of the Items sheet next to each other: column D is between them'), 1);
    assert.match(h.apply([qty('Rice', 3, 'kg')]).error, /column D is between them/);
  });

  it('a Type dropdown that allows "integer" gets "quantity" when the column is added; others are left alone', () => {
    const { h } = legacySheet();
    const items = h.sheet('Items');
    const strict = h.gas.addListValidation({ column: 3, row: 2, rows: 3, values: ['boolean', 'integer'], reject: true, helpText: 'Choose boolean or integer.' });
    h.gas.addListValidation({ column: 3, row: 5, rows: 1, values: ['Quantity', 'Integer', 'Boolean'], reject: true });
    h.gas.addListValidation({ column: 3, row: 6, rows: 1, values: ['yes', 'no'], reject: false });
    const res = h.apply([qty('Rice', 1.5, 'kg'), qty('Milk', 2, 'L')]);
    assert.deepEqual(statuses(res), ['applied', 'applied'], 'not refused by the rule of the person\'s own');
    const rule = (row) => items.getRange(row, 4).getDataValidation();
    assert.deepEqual([rule(2), rule(3), rule(4)].map((r) => [Array.from(r.getCriteriaValues()[0]), r.getAllowInvalid(), r.getHelpText()]),
      Array(3).fill([['boolean', 'integer', 'quantity'], false, 'Choose boolean or integer.']));
    assert.notEqual(rule(2), strict);
    assert.deepEqual(Array.from(rule(5).getCriteriaValues()[0]), ['Quantity', 'Integer', 'Boolean'], 'already allows a quantity');
    assert.deepEqual(Array.from(rule(6).getCriteriaValues()[0]), ['yes', 'no']);
    assert.deepEqual(Array.from(rule(30).getCriteriaValues()[0]), ['boolean', 'integer', 'quantity'], 'the person\'s warning-only rule too');
    assert.equal(rule(30).getHelpText(), 'boolean or integer');
    assert.equal(h.logged('added "quantity" to the dropdown on the Type column of the Items sheet'), 1);
    h.gas.setupSheets();
    assert.equal(h.logged('added "quantity" to the dropdown'), 1, 'once');
  });

  it('a list that overlaps the apply adding the column reads one consistent snapshot', () => {
    const { h, old } = legacySheet();
    const v3Before = h.list();
    h.gas.afterNextRead('Items', () => {
      assert.deepEqual(statuses(h.apply([qty('Tea', 1, 'kg')])), ['applied']);
    }, { when: (range) => range.getRow() === 1 });
    const during = h.list();
    assert.deepEqual(h.header(), NEW_ITEMS_HEADER, 'the apply ran meanwhile');
    assert.deepEqual(during, v3Before, 'what the sheet held when the list read it');
    assert.deepEqual(h.list().items.find((i) => i.name === 'Tea').unit, 'kg');
    assert.deepEqual(h.listV2().items.find((i) => i.name === 'Rice'), old.list.items.find((i) => i.name === 'Rice'));
  });
});
