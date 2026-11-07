'use strict';
/**
 * Protocol version 3: quantities with an exact decimal amount and a unit (pcs, kg, g, L, ml, dozen, pack
 * or the person's own), the Unit column of the Items sheet, and what apps from before units (protocol 2)
 * keep getting: whole numbers rounded up, no units.
 */
const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { loadCodeGs, getEvent, postEvent, DEFAULT_NOW } = require('./fake-gas');

const TOKEN = 'test-secret-token';
const NOW = DEFAULT_NOW;
const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const md5 = (text) => crypto.createHash('md5').update(text, 'utf8').digest('hex');
const STANDARD_UNITS = ['pcs', 'kg', 'g', 'L', 'ml', 'dozen', 'pack'];

function harness(options = {}) {
  const gas = loadCodeGs({ token: TOKEN, ...options });
  const parse = (output) => JSON.parse(output.getContent());
  const h = {
    gas,
    get: (params) => parse(gas.doGet(getEvent({ token: TOKEN, ...params }))),
    /** list as a version 3 app asks for it (v=3). */
    list: (since) => h.get({ action: 'list', v: '3', ...(since ? { since } : {}) }),
    /** list as an app from before units asks for it (no v). */
    listV2: (since) => h.get({ action: 'list', ...(since ? { since } : {}) }),
    post: (body) => parse(gas.doPost(postEvent(JSON.stringify({ token: TOKEN, action: 'apply', device: 'Home', ...body })))),
    apply: (changes, device = 'Home') => h.post({ protocol: 3, device, changes }),
    applyV2: (changes, device = 'Home') => h.post({ device, changes }),
    sheet: (name) => gas.spreadsheet.getSheetByName(name),
    rows: (name = 'Items') => {
      const sheet = gas.spreadsheet.getSheetByName(name);
      return sheet ? sheet.dump().slice(1) : [];
    },
    rule: (row, col) => h.sheet('Items').getRange(row, col).getDataValidation(),
    format: (row, col) => h.sheet('Items').getRange(row, col).getNumberFormat(),
    fn: (name) => gas.context[name],
  };
  return h;
}

let idCounter = 0;
const nextId = () => `00000000-0000-4000-b000-${String(++idCounter).padStart(12, '0')}`;
/** A version 3 quantity upsert; unit left out when undefined. */
const qty = (name, value, unit, time = NOW) => {
  const change = { id: nextId(), op: 'upsert', name, type: 'quantity', value, time };
  if (unit !== undefined) change.unit = unit;
  return change;
};
const upsert = (name, type, value, time = NOW) => ({ id: nextId(), op: 'upsert', name, type, value, time });
const remove = (name, time = NOW) => ({ id: nextId(), op: 'delete', name, time });
const statuses = (response) => response.results.map((r) => r.status);
/** [name, value, unit] of every item a version 3 list returns. */
const summary = (h) => h.list().items.map((i) => (i.type === 'quantity' ? [i.name, i.value, i.unit] : [i.name, i.value]));

describe('protocol version 3', () => {
  it('ping answers version 3', () => {
    const h = harness();
    assert.deepEqual(h.get({ action: 'ping', v: '3' }), { ok: true, app: 'grocery-sync', version: 3, spreadsheet: 'Groceries' });
  });

  it('list answers a version 3 app with "version":3, also when unchanged', () => {
    const h = harness();
    const empty = h.list();
    assert.deepEqual(empty, { ok: true, version: 3, revision: md5('[]'), serverTime: NOW, spreadsheet: 'Groceries', items: [] });
    assert.deepEqual(Object.keys(empty), ['ok', 'version', 'revision', 'serverTime', 'spreadsheet', 'items']);
    h.apply([qty('Rice', 2, 'kg')]);
    const full = h.list();
    const unchanged = h.list(full.revision);
    assert.deepEqual(unchanged, { ok: true, version: 3, revision: full.revision, serverTime: NOW, unchanged: true });
    assert.deepEqual(Object.keys(unchanged), ['ok', 'version', 'revision', 'serverTime', 'unchanged']);
  });

  it('apply answers "version":3 when the body says "protocol":3 (a number or digits)', () => {
    const h = harness();
    const res = h.apply([qty('Rice', 2, 'kg')]);
    assert.deepEqual(Object.keys(res), ['ok', 'version', 'results', 'revision', 'serverTime', 'spreadsheet', 'items']);
    assert.equal(res.version, 3);
    assert.equal(h.post({ protocol: '3', changes: [qty('Tea', 1)] }).version, 3);
    assert.equal(h.post({ protocol: 4, changes: [qty('Jam', 1)] }).version, 3, 'a newer app gets what this script speaks');
  });

  it('anything else is an app from before units: no version, no units', () => {
    const h = harness();
    h.apply([qty('Rice', 2, 'kg')]);
    for (const v of [undefined, '2', '', 'three', '3.5x']) {
      const res = h.get(v === undefined ? { action: 'list' } : { action: 'list', v });
      assert.equal('version' in res, false, `v=${v}`);
      assert.deepEqual(res.items.map((i) => [i.type, i.value, 'unit' in i]), [['integer', 2, false]]);
    }
    for (const protocol of [undefined, 2, '2', null, 'v3', true]) {
      const res = h.post({ protocol, changes: [qty('Tea', 1)] });
      assert.equal('version' in res, false, `protocol=${protocol}`);
    }
  });

  it('sends items in the exact version 3 shape', () => {
    const h = harness();
    h.apply([qty('Rice', 2.5, 'kg', NOW - MINUTE), upsert('Bread', 'boolean', true, NOW - MINUTE), qty('Salt', 1)], 'Anna');
    const { items, revision } = h.list();
    // Rows the script created carry "addedAt" (their Added At, the same as Updated At until a later change).
    assert.deepEqual(items, [
      { name: 'Rice', type: 'quantity', value: 2.5, unit: 'kg', updatedAt: NOW - MINUTE, updatedBy: 'Anna', addedAt: NOW - MINUTE },
      { name: 'Bread', type: 'boolean', value: true, updatedAt: NOW - MINUTE, updatedBy: 'Anna', addedAt: NOW - MINUTE },
      { name: 'Salt', type: 'quantity', value: 1, unit: '', updatedAt: NOW, updatedBy: 'Anna', addedAt: NOW },
    ]);
    assert.deepEqual(Object.keys(items[0]), ['name', 'type', 'value', 'unit', 'updatedAt', 'updatedBy', 'addedAt']);
    assert.deepEqual(Object.keys(items[1]), ['name', 'type', 'value', 'updatedAt', 'updatedBy', 'addedAt']);
    assert.equal(revision, md5(JSON.stringify(items)));
  });
});

describe('quantities with units', () => {
  it('writes "Rice 2 kg" as Need 2, Unit kg, Type quantity, with the unit dropdown and Automatic number format', () => {
    const h = harness();
    const changes = [qty('Rice', 2, 'kg', NOW - MINUTE), qty('Milk', 1.5, 'L', NOW - MINUTE), upsert('Bread', 'boolean', true, NOW - MINUTE)];
    const res = h.apply(changes);
    assert.deepEqual(statuses(res), ['applied', 'applied', 'applied']);
    assert.deepEqual(h.sheet('Items').dump()[0], ['Item', 'Need', 'Unit', 'Type', 'Updated At', 'Updated By', 'Added At']);
    assert.deepEqual(h.rows().map((r) => [r[0], r[1], r[2], r[3], r[4].getTime(), r[5]]), [
      ['Rice', 2, 'kg', 'quantity', NOW - MINUTE, 'Home'],
      ['Milk', 1.5, 'L', 'quantity', NOW - MINUTE, 'Home'],
      ['Bread', true, '', 'boolean', NOW - MINUTE, 'Home'],
    ]);

    const dropdown = h.rule(2, 3);
    assert.equal(dropdown.getCriteriaType(), 'VALUE_IN_LIST');
    assert.deepEqual(Array.from(dropdown.getCriteriaValues()[0]), STANDARD_UNITS);
    assert.equal(dropdown.getAllowInvalid(), true, '"Show a warning": never refuses a unit of the person\'s own');
    assert.match(dropdown.getHelpText(), /type your own/);
    assert.equal(h.rule(3, 3).getCriteriaType(), 'VALUE_IN_LIST');
    assert.equal(h.rule(4, 3), null, 'no unit dropdown on a yes/no row');
    assert.equal(h.rule(4, 2).getCriteriaType(), 'CHECKBOX');
    assert.equal(h.rule(2, 2), null);
    // Automatic (General) shows 2 and 1.5 as such; a pattern like 0.### would show whole amounts as "2.".
    assert.deepEqual([h.format(2, 2), h.format(3, 2)], ['General', 'General']);
    assert.equal(h.format(2, 5), 'yyyy-mm-dd hh:mm:ss');

    assert.deepEqual(h.rows('History').map((r) => r.slice(1)), [
      ['Rice', 'added', 2, 'kg', 'quantity', 'Home', changes[0].id],
      ['Milk', 'added', 1.5, 'L', 'quantity', 'Home', changes[1].id],
      ['Bread', 'added', true, '', 'boolean', 'Home', changes[2].id],
    ]);
    assert.deepEqual(res.items, h.list().items);
  });

  it('keeps amounts exact: half up to 3 decimals, no binary artefacts', () => {
    const h = harness();
    const cases = [
      ['Sum', 0.1 + 0.2, 0.3],
      ['Quarter', 2.25, 2.25],
      ['Eighth', 0.125, 0.125],
      ['HalfUp', 1.0005, 1.001], // the double is a hair below 1.0005; people round the digits they typed
      ['Long', 1.23456, 1.235],
      ['Tiny', 0.0004, 0],
      ['SmallUp', 0.0005, 0.001],
      ['Almost', 999998.9996, 999999],
      ['Max', 999999, 999999],
      ['Zero', 0, 0],
    ];
    assert.deepEqual(statuses(h.apply(cases.map(([name, value]) => qty(name, value, 'kg')))), cases.map(() => 'applied'));
    assert.deepEqual(h.list().items.map((i) => [i.name, i.value]), cases.map(([name, , stored]) => [name, stored]));
    assert.deepEqual(h.rows().map((r) => r[1]), cases.map(([, , stored]) => stored), 'the sheet holds the same numbers');
    const text = JSON.stringify(h.list().items);
    assert.ok(text.includes('"value":0.3,') && !text.includes('0.30000000000000004'), text);
    const round = h.fn('roundAmount_');
    assert.deepEqual([round(-1), round(NaN), round('2'), round(1e-7), round(5e6)], [0, 0, 0, 0, 999999]);
  });

  it('answers per-change errors for bad amounts and units, without blocking the rest', () => {
    const h = harness();
    const bad = [
      qty('Negative', -0.5, 'kg'),
      qty('TooMany', 1000000, 'kg'),
      qty('Text', '1.5', 'kg'),
      qty('Null', null, 'kg'),
      qty('Flag', true, 'kg'),
      { ...qty('Missing', 1, 'kg'), value: undefined },
      qty('UnitNumber', 1, 42),
      qty('UnitObject', 1, { u: 'kg' }),
      qty('UnitLong', 1, 'x'.repeat(17)),
    ];
    const good = qty('Fine', 1, 'y'.repeat(16));
    const res = h.apply([...bad, good]);
    assert.deepEqual(res.results.slice(0, -1).map((r) => r.error), [
      ...Array(6).fill('value must be a number from 0 to 999999 for type quantity'),
      'unit must be text',
      'unit must be text',
      'unit is longer than 16 characters',
    ]);
    assert.ok(res.results.every((r) => !r.error || r.error.length < 80));
    assert.deepEqual(res.results.at(-1), { id: good.id, status: 'applied' });
    assert.deepEqual(summary(h), [['Fine', 1, 'y'.repeat(16)]]);
  });

  it('an absent or null unit keeps the item\'s unit, "" clears it, a new item starts without one', () => {
    const h = harness();
    h.apply([qty('Rice', 2, 'kg', NOW - 5 * MINUTE), qty('Salt', 1, undefined, NOW - 5 * MINUTE)]);
    assert.deepEqual(summary(h), [['Rice', 2, 'kg'], ['Salt', 1, '']]);
    h.apply([qty('Rice', 3, undefined, NOW - 4 * MINUTE)]);
    assert.deepEqual(summary(h)[0], ['Rice', 3, 'kg'], 'absent keeps');
    h.apply([qty('Rice', 0, null, NOW - 3 * MINUTE)]);
    assert.deepEqual(summary(h)[0], ['Rice', 0, 'kg'], '"Got it": 0, unit kept');
    h.apply([qty('Rice', 1, '', NOW - 2 * MINUTE)]);
    assert.deepEqual(summary(h)[0], ['Rice', 1, ''], '"" clears');
    assert.equal(h.rows()[0][2], '');
    assert.equal(h.rule(2, 3).getCriteriaType(), 'VALUE_IN_LIST', 'still a quantity row: the dropdown stays');
  });

  it('cleans and normalises units the way the app does', () => {
    const h = harness();
    const normalize = h.fn('normalizeUnit_');
    const aliases = {
      pcs: ['pc', 'pcs', 'piece', 'pieces', 'nos', 'no'],
      kg: ['kg', 'kgs', 'kilo', 'kilos', 'kilogram', 'kilograms'],
      g: ['g', 'gm', 'gms', 'gram', 'grams', 'gr'],
      L: ['l', 'ltr', 'ltrs', 'litre', 'litres', 'liter', 'liters'],
      ml: ['ml', 'millilitre', 'millilitres', 'milliliter', 'milliliters'],
      dozen: ['dozen', 'doz', 'dz'],
      pack: ['pack', 'packs', 'pkt', 'packet', 'packets'],
    };
    Object.entries(aliases).forEach(([unit, spellings]) => {
      spellings.forEach((spelling) => {
        for (const variant of [spelling, spelling.toUpperCase(), ` ${spelling[0].toUpperCase()}${spelling.slice(1)} `]) {
          assert.equal(normalize(variant), unit, JSON.stringify(variant));
        }
      });
    });
    assert.deepEqual(['Bunch', '  big \n  box ', 'kg.', 'Kilogramme', 'bottle', '', '\t'].map(normalize),
      ['Bunch', 'big box', 'kg.', 'Kilogramme', 'bottle', '', '']);
    assert.equal(normalize('a very long unit name here'), 'a very long unit', 'at most 16 characters (from the sheet)');

    h.apply([qty('Rice', 1, 'Kilos'), qty('Milk', 1, ' LITRES '), qty('Coriander', 2, 'Big\n  bunch'), qty('Eggs', 1, 'DZ')]);
    assert.deepEqual(summary(h), [['Rice', 1, 'kg'], ['Milk', 1, 'L'], ['Coriander', 2, 'Big bunch'], ['Eggs', 1, 'dozen']]);
  });

  it('normalises every case of the table the app is tested with too (fixtures/unit-normalization.json)', () => {
    // The app's UnitsParityTest reads the same file, so both sides store the same text for a unit.
    const cases = JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures', 'unit-normalization.json'), 'utf8'));
    assert.ok(cases.length > 50);
    const normalize = harness().fn('normalizeUnit_');
    cases.forEach(([input, expected]) => assert.equal(normalize(input), expected, JSON.stringify(input)));
  });

  it('switching to yes/no drops the unit and its dropdown; back to a quantity starts without one', () => {
    const h = harness();
    h.apply([qty('Eggs', 12, 'pcs', NOW - 3 * MINUTE)]);
    h.apply([upsert('Eggs', 'boolean', true, NOW - 2 * MINUTE)]);
    assert.deepEqual(h.rows()[0].slice(0, 4), ['Eggs', true, '', 'boolean']);
    assert.equal(h.rule(2, 3), null);
    assert.equal(h.rule(2, 2).getCriteriaType(), 'CHECKBOX');
    h.apply([qty('Eggs', 1, undefined, NOW - MINUTE)]);
    assert.deepEqual(h.rows()[0].slice(0, 4), ['Eggs', 1, '', 'quantity']);
    h.apply([qty('Eggs', 1, 'dozen')]);
    assert.deepEqual(summary(h), [['Eggs', 1, 'dozen']]);
    assert.equal(h.rule(2, 2), null);
    assert.equal(h.rule(2, 3).getCriteriaType(), 'VALUE_IN_LIST');
  });

  it('a unit-only change changes the revision (an old app sees no change)', () => {
    const h = harness();
    h.apply([qty('Rice', 2, 'kg')]);
    const before = h.list();
    const beforeV2 = h.listV2();
    h.apply([qty('Rice', 2, 'g')]); // same time, same amount: only the unit differs
    const after = h.list();
    assert.deepEqual(after.items.map((i) => [i.value, i.unit, i.updatedAt]), [[2, 'g', NOW]]);
    assert.notEqual(after.revision, before.revision);
    assert.equal(h.list(before.revision).unchanged, undefined, 'a version 3 app gets the new list');
    assert.equal(h.listV2().revision, beforeV2.revision, 'what an old app sees did not change');
    assert.equal(h.listV2(beforeV2.revision).unchanged, true);

    h.apply([qty('Rice', 2.5, 'g')]);
    assert.equal(h.listV2().items[0].value, 3);
    assert.notEqual(h.listV2().revision, beforeV2.revision, 'an amount that rounds up differently is a change for an old app');
  });

  it('deletes and tombstones work for quantities as before, History keeps the unit', () => {
    const h = harness();
    h.apply([qty('Rice', 1.5, 'kg', NOW - HOUR)]);
    const del = remove('rice', NOW - MINUTE);
    assert.deepEqual(statuses(h.apply([del])), ['applied']);
    assert.deepEqual(h.rows('History').at(-1).slice(1), ['Rice', 'deleted', 1.5, 'kg', 'quantity', 'Home', del.id]);
    assert.deepEqual(statuses(h.apply([qty('Rice', 3, 'kg', NOW - 2 * MINUTE)])), ['stale']);
    assert.deepEqual(h.list().items, []);
  });
});

describe('apps from before units (protocol 2)', () => {
  it('get each quantity as a whole number rounded up, without unit, so 0.5 kg stays needed', () => {
    const h = harness();
    h.apply([qty('Rice', 0.5, 'kg'), qty('Milk', 1.001, 'L'), qty('Oil', 0, 'L'), qty('Eggs', 12, 'pcs'), upsert('Bread', 'boolean', true), qty('Max', 999999, 'g')]);
    const res = h.listV2();
    assert.deepEqual(Object.keys(res), ['ok', 'revision', 'serverTime', 'spreadsheet', 'items']);
    assert.deepEqual(res.items.map((i) => [i.name, i.type, i.value]), [
      ['Rice', 'integer', 1], ['Milk', 'integer', 2], ['Oil', 'integer', 0], ['Eggs', 'integer', 12], ['Bread', 'boolean', true], ['Max', 'integer', 999999],
    ]);
    assert.ok(res.items.every((i) => !('unit' in i)));
    assert.deepEqual(Object.keys(res.items[0]), ['name', 'type', 'value', 'updatedAt', 'updatedBy']);
    assert.equal(res.revision, md5(JSON.stringify(res.items)), 'the revision of what they get');
    assert.equal(h.listV2(res.revision).unchanged, true);
  });

  it('their whole-number changes keep the unit ("Got it" included)', () => {
    const h = harness();
    h.apply([qty('Rice', 1.5, 'kg', NOW - 3 * MINUTE)]);
    const plus = upsert('Rice', 'integer', 3, NOW - 2 * MINUTE);
    const res = h.applyV2([plus], 'Old phone');
    assert.deepEqual(res.results, [{ id: plus.id, status: 'applied' }]);
    assert.equal('version' in res, false);
    assert.deepEqual(res.items, [{ name: 'Rice', type: 'integer', value: 3, updatedAt: NOW - 2 * MINUTE, updatedBy: 'Old phone' }]);
    assert.deepEqual(h.rows()[0].slice(0, 4), ['Rice', 3, 'kg', 'quantity']);
    h.applyV2([upsert('Rice', 'integer', 0, NOW - MINUTE)]);
    assert.deepEqual(summary(h), [['Rice', 0, 'kg']]);
    assert.equal(h.rule(2, 3).getCriteriaType(), 'VALUE_IN_LIST');
  });

  it('their yes/no changes and deletes work as before', () => {
    const h = harness();
    h.apply([qty('Rice', 2, 'kg', NOW - 3 * MINUTE), qty('Tea', 1, 'pack', NOW - 3 * MINUTE)]);
    assert.deepEqual(statuses(h.applyV2([upsert('Rice', 'boolean', true, NOW - 2 * MINUTE), remove('Tea', NOW - 2 * MINUTE)])), ['applied', 'applied']);
    assert.deepEqual(h.rows().map((r) => r.slice(0, 4)), [['Rice', true, '', 'boolean']]);
    assert.deepEqual(h.listV2().items.map((i) => [i.name, i.type, i.value]), [['Rice', 'boolean', true]]);
  });

  it('an app speaking version 3 may still send "integer" (a change queued before its update)', () => {
    const h = harness();
    h.apply([qty('Rice', 1.5, 'kg', NOW - MINUTE)]);
    const legacy = { ...upsert('Rice', 'integer', 4), unit: 'g' }; // the unit of an "integer" change is not used
    assert.deepEqual(statuses(h.apply([legacy])), ['applied']);
    assert.deepEqual(summary(h), [['Rice', 4, 'kg']]);
    assert.deepEqual(statuses(h.apply([upsert('Fraction', 'integer', 1.5)])), ['error'], 'still whole numbers only');
  });
});

describe('Need and Unit edited by hand', () => {
  /** Rice (2 kg) and Tea (yes/no) added an hour ago, then a minute passes. */
  function riceAndTea() {
    const h = harness();
    h.apply([qty('Rice', 2, 'kg', NOW - HOUR), upsert('Tea', 'boolean', true, NOW - HOUR)]);
    h.gas.advance(MINUTE);
    return h;
  }

  it('a typed unit is normalised ("Kgs" reads as kg) and stamps Updated At / Updated By', () => {
    const h = riceAndTea();
    h.gas.handEdit({ row: 2, col: 3, value: 'Kgs' });
    assert.deepEqual(h.list().items[0], { name: 'Rice', type: 'quantity', value: 2, unit: 'kg', updatedAt: NOW + MINUTE, updatedBy: 'Sheet', addedAt: NOW - HOUR });
    assert.equal(h.rows()[0][2], 'Kgs', 'the cell keeps what the person typed');
    h.gas.handEdit({ row: 2, col: 3, value: '  Big   bag ' });
    assert.equal(h.list().items[0].unit, 'Big bag');
  });

  it('reads Need typed as text: comma decimals, a unit after the amount, an "x"', () => {
    const h = riceAndTea();
    const read = (need, unit = '') => {
      h.gas.handEdit({ row: 2, col: 3, value: unit });
      h.gas.handEdit({ row: 2, col: 2, value: need });
      const item = h.list().items[0];
      return [item.type, item.value, item.unit];
    };
    assert.deepEqual(read('1,5'), ['quantity', 1.5, '']);
    assert.deepEqual(read('2 kg'), ['quantity', 2, 'kg'], 'the unit word counts while Unit is empty');
    assert.deepEqual(read('2kg'), ['quantity', 2, 'kg']);
    assert.deepEqual(read('1.5 Litres'), ['quantity', 1.5, 'L']);
    assert.deepEqual(read('0,25 bunch'), ['quantity', 0.25, 'bunch']);
    assert.deepEqual(read('2 kg', 'g'), ['quantity', 2, 'g'], 'Unit wins over a word in Need');
    assert.deepEqual(read('x3'), ['quantity', 3, '']);
    assert.deepEqual(read('3 x'), ['quantity', 3, '']);
    assert.deepEqual(read(1.23456), ['quantity', 1.235, '']);
    assert.deepEqual(read('1.2345'), ['quantity', 1.235, ''], 'typed numbers are numbers in Sheets');
    for (const unreadable of ['lots', '1-2', '2 big bags', '1,5,0', 'kg 2', '2 kg 3']) {
      assert.deepEqual(read(unreadable), ['quantity', 0, ''], unreadable);
    }
  });

  it('reads a comma before three digits as a thousands separator, never as a decimal mark ("1,000 ml" is not 1 ml)', () => {
    const h = riceAndTea();
    const read = (need) => {
      h.gas.handEdit({ row: 2, col: 3, value: '' });
      h.gas.handEdit({ row: 2, col: 2, value: need });
      const item = h.list().items[0];
      return [item.value, item.unit];
    };
    assert.deepEqual(read('1,000 ml'), [1000, 'ml']);
    assert.deepEqual(read('2,500 g'), [2500, 'g']);
    assert.deepEqual(read('1,00,000 g'), [100000, 'g'], 'Indian grouping');
    assert.deepEqual(read('12,345.5 g'), [12345.5, 'g']);
    assert.deepEqual(read('1,000,000 g'), [999999, 'g'], 'at most 999999');
    assert.deepEqual(read('1,5 kg'), [1.5, 'kg'], 'one or two digits after a comma: the decimal mark');
    assert.deepEqual(read('12,25 kg'), [12.25, 'kg']);
    assert.deepEqual(read(',5 L'), [0.5, 'L']);
    for (const unreadable of ['0,500 g', '1,2345 g', '12,34 kg 5', '1,50,00 g', '1.000,5 g', '1,000,5 g', '2. kg']) {
      assert.deepEqual(read(unreadable), [0, ''], unreadable);
    }
    const parse = h.fn('parseAmountText_');
    assert.deepEqual({ ...parse('1,500') }, { amount: 1500, unit: '', marked: false }, 'in a cell formatted as Plain text');
    assert.deepEqual({ ...parse('1,50') }, { amount: 1.5, unit: '', marked: false });
    assert.equal(parse('0,500'), null);
  });

  it('the Need number format is only how amounts look: when Sheets refuses it, the change is still saved', () => {
    const h = harness();
    const rangeProto = Object.getPrototypeOf(h.gas.spreadsheet.getSheets()[0].getRange(1, 1));
    const setNumberFormat = rangeProto.setNumberFormat;
    rangeProto.setNumberFormat = function (format) {
      if (format === 'General') throw new Error('Invalid number format pattern General.');
      return setNumberFormat.call(this, format);
    };
    try {
      assert.deepEqual(statuses(h.apply([qty('Rice', 2, 'kg')])), ['applied']);
    } finally {
      rangeProto.setNumberFormat = setNumberFormat;
    }
    assert.deepEqual(summary(h), [['Rice', 2, 'kg']]);
    assert.equal(h.format(2, 5), 'yyyy-mm-dd hh:mm:ss');
    assert.equal(h.gas.logs.filter((line) => line.includes('could not set the number format of the Need cells')).length, 1);
  });

  it('a row with Type left blank is a quantity when Need says so, conservatively', () => {
    const h = harness();
    h.gas.setupSheets();
    const rows = [
      ['Rice', '2 kg', '', ''],
      ['Coriander', '2 bunch', '', ''],
      ['Beans', 'x2', '', ''],
      ['Oil', 1.5, 'L', ''],
      ['Bread', 'yes', '', ''],
      ['Salt', "'1", '', ''],
      ['Comma', '1,5', '', ''],
      ['Flag', true, 'kg', ''],
      ['Qty', 2, 'Kilo', ' Qty '],
      ['Number', '3 pcs', '', 'NUMBER'],
      ['Odd', 4, 'kg', 'Boolean'],
    ];
    h.sheet('Items').getRange(2, 1, rows.length, 4).setValues(rows);
    assert.deepEqual(summary(h), [
      ['Rice', 2, 'kg'],
      ['Coriander', 2, 'bunch'],
      ['Beans', 2, ''],
      ['Oil', 1.5, 'L'],
      ['Bread', true],
      ['Salt', true],
      ['Comma', true],
      ['Flag', true],
      ['Qty', 2, 'kg'],
      ['Number', 3, 'pcs'],
      ['Odd', true],
    ]);
    assert.deepEqual(h.listV2().items.map((i) => [i.name, i.type, i.value]).slice(0, 4),
      [['Rice', 'integer', 2], ['Coriander', 'integer', 2], ['Beans', 'integer', 2], ['Oil', 'integer', 2]]);
  });

  it('the app\'s next change writes clean cells, keeping a unit typed into Need', () => {
    const h = riceAndTea();
    h.gas.handEdit({ row: 2, col: 3, value: '' });
    h.gas.handEdit({ row: 2, col: 2, value: '3 Kilos' });
    assert.deepEqual(summary(h)[0], ['Rice', 3, 'kg']);
    h.gas.advance(MINUTE);
    assert.deepEqual(statuses(h.apply([qty('Rice', 4, undefined, NOW + 2 * MINUTE)])), ['applied']);
    assert.deepEqual(h.rows()[0].slice(0, 4), ['Rice', 4, 'kg', 'quantity']);
  });

  it('a unit typed into a yes/no row is ignored', () => {
    const h = riceAndTea();
    h.gas.handEdit({ row: 3, col: 3, value: 'kg' });
    assert.deepEqual(h.list().items[1], { name: 'Tea', type: 'boolean', value: true, updatedAt: NOW + MINUTE, updatedBy: 'Sheet', addedAt: NOW - HOUR });
  });
});

describe('columns found by their titles', () => {
  it('a column inserted before Item: reads, writes and stamps the right cells', () => {
    const h = harness();
    h.apply([qty('Rice', 2, 'kg', NOW - HOUR), upsert('Tea', 'boolean', true, NOW - HOUR)]);
    const items = h.sheet('Items');
    items.insertColumnBefore(1);
    items.getRange(1, 1, 3, 1).setValues([['Aisle'], ['Grains'], ['Drinks']]);
    const before = h.list().items;
    assert.deepEqual(before.map((i) => i.name), ['Rice', 'Tea']);

    h.gas.advance(MINUTE);
    assert.deepEqual(statuses(h.apply([qty('Rice', 1.5, 'g', NOW + MINUTE), qty('Salt', 1, 'pack', NOW + MINUTE)])), ['applied', 'applied']);
    assert.deepEqual(h.rows().map((r) => r.slice(0, 5)), [
      ['Grains', 'Rice', 1.5, 'g', 'quantity'],
      ['Drinks', 'Tea', true, '', 'boolean'],
      ['', 'Salt', 1, 'pack', 'quantity'],
    ]);
    assert.equal(h.rule(2, 4).getCriteriaType(), 'VALUE_IN_LIST', 'the dropdown sits in the Unit column (D)');
    assert.equal(h.rule(3, 3).getCriteriaType(), 'CHECKBOX', 'the checkbox in the Need column (C)');
    assert.equal(h.rule(2, 1), null);

    h.gas.advance(MINUTE);
    h.gas.handEdit({ row: 3, col: 3, value: false });
    assert.deepEqual([h.rows()[1][5].getTime(), h.rows()[1][6]], [NOW + 2 * MINUTE, 'Sheet']);
    h.gas.advance(MINUTE);
    h.gas.handEdit({ row: 3, col: 1, value: 'Tea aisle' });
    assert.equal(h.rows()[1][5].getTime(), NOW + 2 * MINUTE, 'an edit of a column of one\'s own is not stamped');
    assert.deepEqual(h.list().items.map((i) => [i.name, i.value]), [['Rice', 1.5], ['Tea', false], ['Salt', 1]]);
  });

  it('a column between the Items columns: lists work, changes answer what to fix and write nothing', () => {
    const h = harness();
    h.apply([qty('Rice', 2, 'kg', NOW - HOUR), upsert('Tea', 'boolean', true, NOW - HOUR)]);
    const items = h.sheet('Items');
    items.insertColumnAfter(2);
    items.getRange(1, 3).setValue('Notes');
    const listed = h.list();
    assert.deepEqual(listed.items.map((i) => [i.name, i.value, i.unit]), [['Rice', 2, 'kg'], ['Tea', true, undefined]]);
    const before = items.dump();

    const res = h.apply([qty('Rice', 3, 'kg')]);
    assert.deepEqual(res, {
      ok: false,
      error: 'internal: keep the columns Item, Need, Unit, Type, Updated At, Updated By and Added At of the Items sheet next to' +
        ' each other: column C is between them (move it to the right of them).',
    });
    assert.deepEqual(items.dump(), before);
    assert.deepEqual(statuses(h.apply([remove('Tea')])), ['applied'], 'a delete writes no cells');

    items.deleteColumn(3);
    assert.deepEqual(statuses(h.apply([qty('Rice', 3, 'kg')])), ['applied']);
    assert.deepEqual(summary(h), [['Rice', 3, 'kg']]);
  });

  it('Updated At and Updated By apart: a hand edit stamps both', () => {
    const h = harness();
    h.apply([qty('Rice', 2, 'kg', NOW - HOUR)]);
    const items = h.sheet('Items');
    items.insertColumnAfter(5);
    items.getRange(1, 6).setValue('Checked');
    h.gas.handEdit({ row: 2, col: 2, value: 5 });
    const row = h.rows()[0];
    assert.deepEqual([row[1], row[4].getTime(), row[5], row[6]], [5, NOW, '', 'Sheet']);
  });
});

describe('setupSheets and the Unit column', () => {
  it('does not warn about rules on Unit (the script sets them) and still writes units of one\'s own', () => {
    const h = harness();
    h.apply([qty('Rice', 2, 'kg', NOW - HOUR)]);
    h.gas.addListValidation({ column: 3, values: STANDARD_UNITS, helpText: 'Pick a unit.' }); // made strict by hand
    h.gas.addListValidation({ column: 4, values: ['Boolean', 'Integer'] });
    h.gas.setupSheets();
    const warnings = h.gas.logs.filter((line) => line.startsWith('[warn]'));
    assert.equal(warnings.length, 1);
    assert.match(warnings[0], /on the Type column of the Items sheet .*"boolean" or "quantity"/);
    assert.doesNotMatch(warnings[0], /Unit/);

    const items = h.sheet('Items');
    items.getRange(2, 4, items.getMaxRows() - 1, 1).clearDataValidations();
    assert.deepEqual(statuses(h.apply([qty('Rice', 1, 'bottle')])), ['applied']);
    assert.deepEqual(summary(h), [['Rice', 1, 'bottle']]);
    assert.equal(h.rule(2, 3).getAllowInvalid(), true, 'the strict rule was replaced by the script\'s dropdown');
  });
});
