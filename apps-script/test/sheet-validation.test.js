'use strict';
/**
 * Writes the sheet itself refuses, above all data validation set to "Reject the input" (Data > Data
 * validation), which Sheets enforces on script writes too: such a change must fail alone with a
 * per-change "sheet_rejected: <the sheet's message> (where)" while the rest of the batch is saved, and
 * Google-side trouble must still fail the whole request so the app retries it.
 */
const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const { loadCodeGs, getEvent, postEvent, DEFAULT_NOW } = require('./fake-gas');

const TOKEN = 'test-secret-token';
const NOW = DEFAULT_NOW;
const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;

const CHOOSE = 'Choose Boolean or Integer.';
const ON_TYPE = '(data validation on the Type column of the Items sheet)';
const TYPE_REJECTED = `sheet_rejected: ${CHOOSE} ${ON_TYPE}`;
const TIMEOUT = 'Service Spreadsheets timed out while accessing document with id fake-spreadsheet-id.';
const genericMessage = (cell) => `The data you entered in cell ${cell} violates the data validation rules set on this cell.`;

function harness() {
  const gas = loadCodeGs({ token: TOKEN });
  const parse = (output) => JSON.parse(output.getContent());
  const h = {
    gas,
    list: () => parse(gas.doGet(getEvent({ token: TOKEN, action: 'list' }))),
    ping: () => parse(gas.doGet(getEvent({ token: TOKEN, action: 'ping' }))),
    apply: (changes, device = 'Home') =>
      parse(gas.doPost(postEvent(JSON.stringify({ token: TOKEN, action: 'apply', device, changes })))),
    sheet: (name) => gas.spreadsheet.getSheetByName(name),
    rows: (name = 'Items') => {
      const sheet = gas.spreadsheet.getSheetByName(name);
      return sheet ? sheet.dump().slice(1) : [];
    },
    needRule: (row) => {
      const rule = gas.spreadsheet.getSheetByName('Items').getRange(row, 2).getDataValidation();
      return rule ? rule.getCriteriaType() : null;
    },
    /** The user's rule: a Boolean / Integer dropdown on Type that rejects anything else. */
    typeDropdown: (options = {}) => gas.addListValidation({ column: 4, values: ['Boolean', 'Integer'], helpText: CHOOSE, ...options }),
    remembered: (change) => gas.cache.get(`grocery-sync:change:${change.id}`) !== null,
    warnings: () => gas.logs.filter((line) => line.startsWith('[warn]')),
  };
  return h;
}

let idCounter = 0;
const nextId = () => `00000000-0000-4000-a000-${String(++idCounter).padStart(12, '0')}`;
const upsert = (name, type, value, time = NOW) => ({ id: nextId(), op: 'upsert', name, type, value, time });
const remove = (name, time = NOW) => ({ id: nextId(), op: 'delete', name, time });
const statuses = (response) => response.results.map((r) => r.status);
const summary = (h, name) => h.rows(name).map((r) => [r[0], r[1], r[3]]); // Item, Need, Type

/** Items: Milk (integer 2), Tea (checkbox, ticked), added an hour ago by Home. */
function milkAndTea() {
  const h = harness();
  h.apply([upsert('Milk', 'integer', 2, NOW - HOUR), upsert('Tea', 'boolean', true, NOW - HOUR)]);
  return h;
}

/** Items: Apples, Bread, Cheese, Dates (rows 2-5, integer 1 / Bread a ticked checkbox). */
function fourItems() {
  const h = harness();
  h.apply([
    upsert('Apples', 'integer', 1, NOW - HOUR),
    upsert('Bread', 'boolean', true, NOW - HOUR),
    upsert('Cheese', 'integer', 1, NOW - HOUR),
    upsert('Dates', 'integer', 1, NOW - HOUR),
  ]);
  return h;
}

describe('a Type dropdown that rejects input (the reported bug)', () => {
  it('answers each refused change with sheet_rejected naming the column, and writes nothing', () => {
    const h = milkAndTea();
    h.typeDropdown();
    const itemsBefore = h.rows();
    const historyBefore = h.rows('History');
    const changes = [
      upsert('Milk', 'boolean', true), // its Need cell would get a checkbox before the refused write
      upsert('Tea', 'integer', 3), // its checkbox would be removed before the refused write
      upsert('Bread', 'integer', 1), // a new item, refused on the row it would be written to
    ];
    const res = h.apply(changes);

    assert.equal(res.ok, true, 'not a top-level internal error');
    assert.deepEqual(res.results, changes.map((c) => ({ id: c.id, status: 'error', error: TYPE_REJECTED })));
    assert.deepEqual(h.rows(), itemsBefore, 'Items unchanged');
    assert.equal(h.needRule(2), null, 'Milk keeps a plain quantity cell');
    assert.equal(h.needRule(3), 'CHECKBOX', 'Tea keeps its checkbox');
    assert.equal(h.needRule(4), null);
    assert.deepEqual(h.rows('History'), historyBefore, 'no History for refused changes');
    assert.deepEqual(h.rows('_Tombstones'), []);
    assert.ok(changes.every((c) => !h.remembered(c)), 'refused change ids are not remembered');

    const listed = h.list();
    assert.deepEqual(res.items, listed.items);
    assert.equal(res.revision, listed.revision);
    assert.deepEqual(listed.items.map((i) => [i.name, i.value]), [['Milk', 2], ['Tea', true]]);
    assert.equal(h.ping().ok, true, 'ping is unaffected');
    assert.ok(h.warnings().some((line) => line.includes('refused the change to "Bread"') && line.includes(CHOOSE)));
    assert.equal(h.gas.lock.held, false);
  });

  it('without help text it reports the generic Sheets message for the cell, plus the column', () => {
    const h = milkAndTea();
    h.typeDropdown({ helpText: undefined });
    const update = upsert('Milk', 'integer', 3);
    const add = upsert('Bread', 'boolean', true);
    const res = h.apply([update, add]);
    assert.deepEqual(res.results.map((r) => r.error), [
      `sheet_rejected: ${genericMessage('D2')} ${ON_TYPE}`,
      `sheet_rejected: ${genericMessage('D4')} ${ON_TYPE}`,
    ]);
    assert.deepEqual(summary(h), [['Milk', 2, 'quantity'], ['Tea', true, 'boolean']]);
  });

  it('applies the same change once the rule is removed', () => {
    const h = milkAndTea();
    h.typeDropdown();
    const change = upsert('Milk', 'boolean', true);
    assert.deepEqual(statuses(h.apply([change])), ['error']);

    const items = h.sheet('Items');
    items.getRange(2, 4, items.getMaxRows() - 1, 1).clearDataValidations(); // Type
    const res = h.apply([change]);
    assert.deepEqual(res.results, [{ id: change.id, status: 'applied' }], 'the same id is not a duplicate');
    assert.deepEqual(summary(h), [['Milk', true, 'boolean'], ['Tea', true, 'boolean']]);
    assert.equal(h.needRule(2), 'CHECKBOX');
    assert.deepEqual(h.rows('History').at(-1).slice(1), ['Milk', 'updated', true, '', 'boolean', 'Home', change.id]);
  });

  it('still applies deletes, which do not write the Type column', () => {
    const h = milkAndTea();
    h.typeDropdown();
    const del = remove('Tea');
    const res = h.apply([upsert('Milk', 'integer', 5), del]);
    assert.deepEqual(statuses(res), ['error', 'applied']);
    assert.deepEqual(summary(h), [['Milk', 2, 'quantity']]);
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['tea']);
    assert.deepEqual(h.rows('History').at(-1).slice(1), ['Tea', 'deleted', true, '', 'boolean', 'Home', del.id]);
  });

  it('keeps the detail short however long the help text is', () => {
    const h = milkAndTea();
    h.typeDropdown({ helpText: `Please pick one of the values from the dropdown list. ${'Really. '.repeat(40)}` });
    const [result] = h.apply([upsert('Milk', 'integer', 3)]).results;
    assert.ok(result.error.length <= 200, `${result.error.length} characters`);
    assert.match(result.error, /^sheet_rejected: Please pick one of the values from the dropdown list\. Really\..*\.\.\. /);
    assert.ok(result.error.endsWith(ON_TYPE));
  });
});

describe('a batch the sheet refuses only in part', () => {
  it('saves the accepted changes and fails only the refused ones, with History for applied ones only', () => {
    const h = fourItems();
    // The dropdown covers only the first two data rows (Apples, Bread).
    h.typeDropdown({ row: 2, rows: 2 });
    const changes = [
      upsert('Apples', 'integer', 5), // row 2: refused
      upsert('Dates', 'integer', 7), // row 5: accepted
      remove('Cheese'), // row 4: accepted
      upsert('Eggs', 'boolean', true), // new: accepted
      upsert('Bread', 'boolean', false), // row 3: refused
    ];
    const historyBefore = h.rows('History').length;
    const res = h.apply(changes);

    assert.equal(res.ok, true);
    assert.deepEqual(res.results, [
      { id: changes[0].id, status: 'error', error: TYPE_REJECTED },
      { id: changes[1].id, status: 'applied' },
      { id: changes[2].id, status: 'applied' },
      { id: changes[3].id, status: 'applied' },
      { id: changes[4].id, status: 'error', error: TYPE_REJECTED },
    ]);
    assert.deepEqual(summary(h), [
      ['Apples', 1, 'quantity'],
      ['Bread', true, 'boolean'],
      ['Dates', 7, 'quantity'],
      ['Eggs', true, 'boolean'],
    ]);
    assert.deepEqual([2, 3, 4, 5].map(h.needRule), [null, 'CHECKBOX', null, 'CHECKBOX']);
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['cheese']);

    const history = h.rows('History').slice(historyBefore);
    assert.deepEqual(history.map((r) => r.slice(1)), [
      ['Dates', 'updated', 7, '', 'quantity', 'Home', changes[1].id],
      ['Cheese', 'deleted', 1, '', 'quantity', 'Home', changes[2].id],
      ['Eggs', 'added', true, '', 'boolean', 'Home', changes[3].id],
    ]);
    assert.deepEqual(changes.map(h.remembered), [false, true, true, true, false]);

    const listed = h.list();
    assert.deepEqual(res.items, listed.items, 'the response shows what the sheet holds');
    assert.equal(res.revision, listed.revision);
  });

  it('a re-sent batch reports the applied changes as duplicates and the refused ones again', () => {
    const h = fourItems();
    h.typeDropdown({ row: 2, rows: 2 });
    const changes = [upsert('Apples', 'integer', 5), upsert('Dates', 'integer', 7)];
    h.apply(changes);
    const again = h.apply(changes);
    assert.deepEqual(again.results, [
      { id: changes[0].id, status: 'error', error: TYPE_REJECTED },
      { id: changes[1].id, status: 'duplicate' },
    ]);
    assert.equal(h.rows('History').filter((r) => r[7] === changes[1].id).length, 1);
  });

  it('one at a time still starts again when a person edits Items meanwhile', () => {
    const h = fourItems();
    h.typeDropdown({ row: 2, rows: 1 });
    let reads = 0;
    h.gas.afterNextRead('Items', (sheet) => {
      reads += 1;
      // Third read = the first one-at-a-time read: a person inserts a row above Bread and types Figs.
      if (reads === 3) {
        sheet.insertRowBefore(3);
        h.gas.handEdit({ row: 3, col: 1, value: 'Figs' });
      }
    }, { times: 100, when: (range) => range.getRow() === 2 && range.getNumColumns() === 7 });
    const changes = [upsert('Apples', 'integer', 5), upsert('Bread', 'boolean', false), remove('Dates')];
    const res = h.apply(changes);
    assert.deepEqual(statuses(res), ['error', 'applied', 'applied']);
    assert.deepEqual(summary(h), [['Apples', 1, 'quantity'], ['Figs', '', ''], ['Bread', false, 'boolean'], ['Cheese', 1, 'quantity']]);
    assert.deepEqual(res.items.map((i) => i.name), ['Apples', 'Figs', 'Bread', 'Cheese']);
  });
});

describe('deletes whose tombstone the sheet refuses', () => {
  it('fails the delete alone and keeps its row, so nothing is half done', () => {
    const h = harness();
    h.apply([upsert('Milk', 'boolean', true, NOW - HOUR), upsert('Bread', 'boolean', true, NOW - HOUR), upsert('Tea', 'integer', 1, NOW - HOUR)]);
    h.gas.addListValidation({ sheet: '_Tombstones', column: 1, values: ['milk'] });
    const changes = [remove('Bread'), remove('Milk'), upsert('Tea', 'integer', 3)];
    const historyBefore = h.rows('History').length;
    const res = h.apply(changes);

    assert.deepEqual(res.results, [
      {
        id: changes[0].id,
        status: 'error',
        error: `sheet_rejected: ${genericMessage('A2')} (data validation on the Key column of the _Tombstones sheet)`,
      },
      { id: changes[1].id, status: 'applied' },
      { id: changes[2].id, status: 'applied' },
    ]);
    assert.deepEqual(summary(h), [['Bread', true, 'boolean'], ['Tea', 3, 'quantity']]);
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['milk']);
    assert.deepEqual(h.rows('History').slice(historyBefore).map((r) => [r[1], r[2]]), [['Milk', 'deleted'], ['Tea', 'updated']]);
    assert.deepEqual(res.items, h.list().items);
  });

  it('an upsert that only drops an old tombstone is applied even when that write is refused', () => {
    const h = harness();
    h.apply([remove('Milk', NOW - HOUR), remove('Bread', NOW - HOUR)]);
    // Dropping Milk's tombstone rewrites Bread's into row 2, which the rule refuses.
    h.gas.addListValidation({ sheet: '_Tombstones', column: 1, values: ['nothing'] });
    const add = upsert('Milk', 'integer', 2);
    const res = h.apply([add]);
    assert.deepEqual(statuses(res), ['applied']);
    assert.deepEqual(summary(h), [['Milk', 2, 'quantity']]);
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['milk', 'bread'], 'the old tombstone is older than the row: harmless');
    assert.ok(h.warnings().some((line) => line.includes('could not update the _Tombstones sheet')));
    assert.deepEqual(h.rows('History').at(-1).slice(1, 3), ['Milk', 'added'],
      'added, although the refused batch had already written the row');
    assert.equal(h.rows('History').length, 3);
    assert.deepEqual(statuses(h.apply([upsert('Milk', 'integer', 9, NOW - 2 * HOUR)])), ['stale']);
  });
});

describe('History is best effort', () => {
  it('a rule on History refuses its rows, but the changes are applied and remembered', () => {
    const h = harness();
    h.apply([upsert('Tea', 'boolean', true, NOW - HOUR)]);
    h.gas.addListValidation({ sheet: 'History', column: 3, values: ['added'] });
    const changes = [upsert('Milk', 'integer', 2), remove('Tea')];
    const res = h.apply(changes);
    assert.deepEqual(statuses(res), ['applied', 'applied']);
    assert.deepEqual(summary(h), [['Milk', 2, 'quantity']]);
    assert.deepEqual(h.rows('_Tombstones').map((r) => r[0]), ['tea']);
    assert.equal(h.rows('History').length, 1, 'only the earlier row; the refused block is left out');
    assert.ok(changes.every(h.remembered));
    assert.ok(h.warnings().some((line) => line.includes('could not add 2 row(s) to the History sheet') &&
      line.includes('(data validation on the Action column of the History sheet)')));
  });
});

describe('Google-side trouble still fails the whole request', () => {
  const transient = [
    TIMEOUT,
    'Service Spreadsheets failed while accessing document with id fake-spreadsheet-id.',
    "We're sorry, a server error occurred. Please wait a bit and try again.",
    'Service invoked too many times in a short time: spreadsheets. Try Utilities.sleep(1000) between calls.',
    'Unexpected error while getting the method or property setValues on object SpreadsheetApp.Range.',
  ];
  for (const message of transient) {
    it(`answers internal for "${message.slice(0, 50)}...", even with a strict rule on the cells`, () => {
      const h = milkAndTea();
      h.typeDropdown({ values: ['boolean', 'quantity'], helpText: undefined }); // strict, but allows what the app writes
      h.gas.failNextWrite('Items', message);
      const change = upsert('Milk', 'integer', 3);
      assert.deepEqual(h.apply([change]), { ok: false, error: `internal: ${message}` });
      assert.deepEqual(summary(h), [['Milk', 2, 'quantity'], ['Tea', true, 'boolean']]);
      assert.equal(h.rows('History').length, 2);
      assert.deepEqual(statuses(h.apply([change])), ['applied'], 'the retry goes through');
    });
  }

  it('answers internal for an unknown error when no rule rejects input', () => {
    const h = milkAndTea();
    h.gas.failNextWrite('Items', 'Something odd happened');
    assert.deepEqual(h.apply([upsert('Milk', 'integer', 3)]), { ok: false, error: 'internal: Something odd happened' });
  });

  it('keeps what was applied one at a time when Google fails part way through', () => {
    const h = fourItems();
    h.typeDropdown({ row: 2, rows: 1 });
    h.gas.failNextWrite('Items', TIMEOUT, { when: (range) => range.getRow() === 4 && range.getNumColumns() === 7 });
    const changes = [upsert('Apples', 'integer', 5), upsert('Bread', 'boolean', false), upsert('Cheese', 'integer', 9)];
    assert.deepEqual(h.apply(changes), { ok: false, error: `internal: ${TIMEOUT}` });
    assert.deepEqual(summary(h).map((r) => r[1]), [1, false, 1, 1], 'Bread was saved before the time-out');
    assert.ok(h.remembered(changes[1]));
    assert.deepEqual(h.rows('History').at(-1).slice(1, 3), ['Bread', 'updated']);

    const again = h.apply(changes);
    assert.deepEqual(statuses(again), ['error', 'duplicate', 'applied']);
    assert.deepEqual(summary(h).map((r) => r[1]), [1, false, 9, 1]);
    assert.equal(h.rows('History').filter((r) => r[1] === 'Bread').length, 2, 'added once, updated once');
  });
});

describe('telling refusals from trouble', () => {
  it('treats an unexplained error as the sheet refusing when a strict rule refuses the value written', () => {
    const h = milkAndTea();
    h.typeDropdown({ row: 2, rows: 1, helpText: undefined }); // Milk's row only; refuses 'integer'
    h.gas.failNextWrite('Items', 'Oops', { times: 2 }); // the batch write and the first change alone
    const res = h.apply([upsert('Milk', 'integer', 3), upsert('Tea', 'boolean', false)]);
    assert.deepEqual(res.results.map((r) => [r.status, r.error]), [
      ['error', `sheet_rejected: Oops ${ON_TYPE}`],
      ['applied', undefined],
    ]);
    assert.deepEqual(summary(h), [['Milk', 2, 'quantity'], ['Tea', false, 'boolean']]);
  });

  it('names the strict columns that refuse the values when the message does not say which one refused', () => {
    const h = milkAndTea();
    h.gas.addListValidation({ column: 1, values: ['Milk', 'Tea'] }); // refuses Rice
    h.typeDropdown({ helpText: undefined }); // refuses 'boolean'
    h.gas.addListValidation({ column: 6, values: ['Home', 'Shop'] }); // strict, but accepts Home
    h.gas.failNextWrite('Items', 'Oops', { times: 2 });
    const [result] = h.apply([upsert('Rice', 'boolean', true)]).results;
    assert.equal(result.error, 'sheet_rejected: Oops (data validation on the Item and Type columns of the Items sheet)');
  });

  // A strict rule that accepts what the app writes (e.g. relaxed as the app suggests) cannot be why a
  // write failed: an unknown message (another language, a race) is then trouble, not a refusal.
  for (const message of [
    "Le service Spreadsheets a expir\u00e9 lors de l'acc\u00e8s au document portant l'ID fake-spreadsheet-id.",
    'The coordinates of the range are outside the dimensions of the sheet.',
  ]) {
    it(`answers internal for "${message.slice(0, 40)}..." when the strict rules accept the values`, () => {
      const h = milkAndTea();
      h.typeDropdown({ values: ['Boolean', 'Integer', 'boolean', 'quantity'] });
      h.gas.failNextWrite('Items', message);
      const change = upsert('Milk', 'integer', 5);
      assert.deepEqual(h.apply([change]), { ok: false, error: `internal: ${message}` });
      assert.deepEqual(summary(h), [['Milk', 2, 'quantity'], ['Tea', true, 'boolean']]);
      assert.deepEqual(statuses(h.apply([change])), ['applied'], 'the retry goes through');
    });
  }

  it('names the column whose help text the sheet showed', () => {
    const h = milkAndTea();
    h.gas.addListValidation({ column: 1, values: ['Milk', 'Tea'], helpText: 'Pick an item from the list.' });
    h.typeDropdown({ values: ['boolean', 'quantity'], helpText: 'Lowercase types only.' });
    const res = h.apply([upsert('Rice', 'boolean', true), upsert('Milk', 'integer', 4)]);
    assert.deepEqual(res.results.map((r) => r.error || r.status), [
      'sheet_rejected: Pick an item from the list. (data validation on the Item column of the Items sheet)',
      'applied',
    ]);
  });

  it('a help text that sounds like trouble is still the sheet refusing', () => {
    const h = milkAndTea();
    const helpText = 'Server says: please try again with Boolean or Integer (this column is protected range-wise).';
    h.typeDropdown({ helpText });
    const [result] = h.apply([upsert('Milk', 'integer', 3)]).results;
    assert.equal(result.error, `sheet_rejected: ${helpText} ${ON_TYPE}`);
  });

  it('reports a protected range as refused too, without any rule', () => {
    const h = milkAndTea();
    const message = 'You are trying to edit a protected cell or object. Please contact the spreadsheet owner to remove protection if you need to edit.';
    h.gas.failNextWrite('Items', message, { times: 2 });
    const res = h.apply([upsert('Milk', 'integer', 3), upsert('Tea', 'boolean', false)]);
    assert.deepEqual(res.results.map((r) => r.error || r.status), [
      `sheet_rejected: ${message} (protected range on the Items sheet)`,
      'applied',
    ]);
  });
});

describe('time budget for one change at a time', () => {
  it('answers "deferred" for changes that would start after the budget, trying at least one', () => {
    const h = milkAndTea();
    h.typeDropdown();
    let slow = true;
    h.gas.afterNextRead('Items', () => {
      if (slow) h.gas.advance(30 * SECOND);
    }, { times: 1000 });
    const changes = [upsert('Milk', 'integer', 3), upsert('Tea', 'integer', 1), upsert('Bread', 'integer', 1)];
    const res = h.apply(changes);
    assert.deepEqual(res.results, [
      { id: changes[0].id, status: 'error', error: TYPE_REJECTED },
      { id: changes[1].id, status: 'deferred' },
      { id: changes[2].id, status: 'deferred' },
    ]);
    assert.deepEqual(res.items, h.list().items);

    slow = false;
    assert.deepEqual(statuses(h.apply(changes.slice(1))), ['error', 'error'], 'the deferred changes are tried next time');
  });
});

describe('refusals Apps Script reports only at the next flush', () => {
  it('a delete whose tombstone is refused fails alone, naming the _Tombstones column', () => {
    const h = milkAndTea();
    h.gas.addListValidation({ sheet: '_Tombstones', column: 1, values: ['tea'], helpText: 'Only tea may be deleted.' });
    h.gas.reportWriteErrorsLate();
    const changes = [remove('Milk'), upsert('Tea', 'boolean', false)];
    const historyBefore = h.rows('History').length;
    const res = h.apply(changes);
    assert.deepEqual(res.results, [
      {
        id: changes[0].id,
        status: 'error',
        error: 'sheet_rejected: Only tea may be deleted. (data validation on the Key column of the _Tombstones sheet)',
      },
      { id: changes[1].id, status: 'applied' },
    ]);
    assert.deepEqual(summary(h), [['Milk', 2, 'quantity'], ['Tea', false, 'boolean']], 'Milk keeps its row');
    assert.deepEqual(h.rows('_Tombstones'), []);
    assert.deepEqual(h.rows('History').slice(historyBefore).map((r) => [r[1], r[2]]), [['Tea', 'updated']]);
    assert.deepEqual(res.items, h.list().items);
  });

  it('the Type dropdown still fails each change alone, naming the column, and writes nothing', () => {
    const h = milkAndTea();
    h.typeDropdown();
    h.gas.reportWriteErrorsLate();
    const itemsBefore = h.rows();
    const changes = [upsert('Milk', 'integer', 3), upsert('Bread', 'boolean', true)];
    const res = h.apply(changes);
    assert.deepEqual(res.results, changes.map((c) => ({ id: c.id, status: 'error', error: TYPE_REJECTED })));
    assert.deepEqual(h.rows(), itemsBefore);
    assert.equal(h.needRule(2), null);
  });

  it('a History rule does not undo the applied changes, and the log names its column', () => {
    const h = harness();
    h.apply([upsert('Tea', 'boolean', true, NOW - HOUR)]);
    h.gas.addListValidation({ sheet: 'History', column: 3, values: ['deleted'] });
    h.gas.reportWriteErrorsLate();
    const change = upsert('Milk', 'integer', 2);
    assert.deepEqual(statuses(h.apply([change])), ['applied']);
    assert.deepEqual(summary(h), [['Tea', true, 'boolean'], ['Milk', 2, 'quantity']]);
    assert.equal(h.rows('History').length, 1, 'only the earlier row');
    assert.ok(h.remembered(change));
    assert.ok(h.warnings().some((line) => line.includes('could not add 1 row(s) to the History sheet') &&
      line.includes('(data validation on the Action column of the History sheet)')));
  });

  it('a late time-out is still trouble for the whole request', () => {
    const h = milkAndTea();
    h.gas.reportWriteErrorsLate();
    h.gas.failNextWrite('Items', TIMEOUT);
    const change = upsert('Milk', 'integer', 3);
    assert.deepEqual(h.apply([change]), { ok: false, error: `internal: ${TIMEOUT}` });
    assert.deepEqual(statuses(h.apply([change])), ['applied']);
  });
});

describe('the script lock while changes are applied one at a time', () => {
  const constant = (h, name) => vm.runInContext(name, h.gas.context);

  /** Records how long each execution held the script lock (fake clock). */
  function lockHolds(h) {
    const holds = [];
    const lock = h.gas.lock;
    const tryLock = lock.tryLock.bind(lock);
    const releaseLock = lock.releaseLock.bind(lock);
    let since = null;
    lock.tryLock = (ms) => {
      const ok = tryLock(ms);
      if (ok) since = h.gas.now();
      return ok;
    };
    lock.releaseLock = () => {
      if (since !== null) holds.push(h.gas.now() - since);
      since = null;
      releaseLock();
    };
    return holds;
  }

  it('is held well under the time onEdit waits for it, however slow the sheet is', () => {
    const h = milkAndTea();
    h.typeDropdown();
    h.gas.afterNextRead('Items', () => h.gas.advance(SECOND), { times: 1000 }); // every Items read takes 1 s
    const holds = lockHolds(h);
    const changes = ['A', 'B', 'C', 'D', 'E', 'F', 'G'].map((name) => upsert(name, 'integer', 1));
    const res = h.apply(changes);
    const tried = statuses(res).filter((s) => s !== 'deferred');
    assert.ok(tried.length >= 1 && tried.every((s) => s === 'error'), statuses(res).join());
    assert.ok(statuses(res).includes('deferred'));
    assert.equal(holds.length, 1);
    assert.ok(holds[0] < constant(h, 'EDIT_LOCK_TIMEOUT_MS_'), `held ${holds[0]} ms`);
  });

  it('counts the time budget from taking the lock, not from the request', () => {
    const h = milkAndTea();
    h.typeDropdown();
    const lock = h.gas.lock;
    const tryLock = lock.tryLock.bind(lock);
    lock.tryLock = (ms) => {
      h.gas.advance(25 * SECOND); // another apply held the lock meanwhile
      return tryLock(ms);
    };
    const changes = [upsert('Milk', 'integer', 3), upsert('Tea', 'integer', 1), upsert('Bread', 'integer', 1)];
    assert.deepEqual(h.apply(changes).results, changes.map((c) => ({ id: c.id, status: 'error', error: TYPE_REJECTED })));
  });
});

describe('setupSheets warns about rules that would refuse the app', () => {
  it('lists the Items columns that reject input, but not the Need checkboxes or warning-only rules', () => {
    const h = milkAndTea();
    h.gas.setupSheets();
    assert.deepEqual(h.warnings(), [], 'no warning for a clean sheet');

    const items = h.sheet('Items');
    const strictCheckbox = h.gas.spreadsheetApp.newDataValidation().requireCheckbox().setAllowInvalid(false).build();
    items.getRange(3, 2).setDataValidation(strictCheckbox);
    h.gas.addListValidation({ column: 1, values: ['Milk'], reject: false });
    h.typeDropdown();
    h.gas.addListValidation({ sheet: '_Tombstones', column: 1, values: ['x'] });
    h.gas.setupSheets();

    const warnings = h.warnings();
    assert.equal(warnings.length, 2);
    assert.match(warnings[0], /data validation on the Type column of the Items sheet is set to "Reject the input"/);
    assert.match(warnings[0], /Data > Data validation/);
    assert.doesNotMatch(warnings[0], /Need|Item column/);
    assert.match(warnings[1], /Key column of the _Tombstones sheet/);
  });
});
