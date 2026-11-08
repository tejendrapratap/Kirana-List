'use strict';
/**
 * mockjson.js - JSON helpers for mock.sh (reads stdin / argv, writes stdout). Not meant to be run by hand.
 *
 *   node mockjson.js apply-body <device> <protocol> <changes-json>
 *        the POST body of an apply from another phone; the token comes from $GS_MOCK_TOKEN (never argv).
 *        Each change gets an "id" and a "time" (now) unless it has them. "é" escapes work in names.
 *   node mockjson.js upsert-change <name> <value> [unit]   value true/false = Yes/no, a number = quantity;
 *        unit "-" sends "" (clears it), no unit keeps the row's unit
 *   node mockjson.js delete-change <name>
 *   node mockjson.js rows <sheet>          stdin: GET /debug/sheets; one JSON row per line (row 1 = titles)
 *   node mockjson.js sheet-names           stdin: GET /debug/sheets; the sheet names
 *   node mockjson.js cell <sheet> <row|item name> <column number|title> [json value]
 *        stdin: GET /debug/sheets; prints the hand-edit body {"sheet","row","col","value"}
 *   node mockjson.js results               stdin: an apply answer; prints ok/error, each change's status
 *   node mockjson.js compact               stdin: any JSON; prints it on one line (or the raw text if not JSON)
 */
const fs = require('fs');

const readStdin = () => fs.readFileSync(0, 'utf8');

function parseJson(text, what) {
  try {
    return JSON.parse(text);
  } catch (err) {
    throw new Error(`${what} is not JSON: ${String(text).slice(0, 200)}`);
  }
}

function sheet(dump, name) {
  const s = dump.sheets.find((x) => x.name === name);
  if (!s) throw new Error(`no sheet named ${JSON.stringify(name)} (have: ${dump.sheets.map((x) => x.name).join(', ')})`);
  return s;
}

/** A JSON value when the text is JSON (true, 2, "x", null), else the text itself. */
function looseValue(text) {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

const commands = {
  'apply-body'([device, protocol, changesJson]) {
    const token = process.env.GS_MOCK_TOKEN;
    if (!token) throw new Error('GS_MOCK_TOKEN is not set');
    let changes = parseJson(changesJson, 'changes');
    if (!Array.isArray(changes)) changes = [changes];
    const now = Date.now();
    const tag = require('crypto').randomBytes(4).toString('hex');
    changes = changes.map((c, i) => ({ id: `${device}-${now}-${tag}-${i}`, time: now, ...c }));
    const body = { action: 'apply', token, device, changes };
    if (protocol && protocol !== '0') body.protocol = Number(protocol);
    return JSON.stringify(body);
  },
  'upsert-change'([name, value, unit]) {
    if (!name) throw new Error('upsert needs a name');
    let change;
    if (value === 'true' || value === 'false') {
      change = { op: 'upsert', name, type: 'boolean', value: value === 'true' };
    } else if (value !== undefined && value !== '' && Number.isFinite(Number(value))) {
      change = { op: 'upsert', name, type: 'quantity', value: Number(value) };
      if (unit !== undefined) change.unit = unit === '-' ? '' : unit;
    } else {
      throw new Error(`value must be true, false or a number (got ${JSON.stringify(value)})`);
    }
    return JSON.stringify([change]);
  },
  'delete-change'([name]) {
    if (!name) throw new Error('delete needs a name');
    return JSON.stringify([{ op: 'delete', name }]);
  },
  rows([name = 'Items']) {
    return sheet(parseJson(readStdin(), '/debug/sheets'), name).values.map((r) => JSON.stringify(r)).join('\n');
  },
  'sheet-names'() {
    return parseJson(readStdin(), '/debug/sheets').sheets.map((s) => `${s.name}${s.hidden ? ' (hidden)' : ''}: ${s.values.length} rows`).join('\n');
  },
  cell([name, rowRef, colRef, value]) {
    const s = sheet(parseJson(readStdin(), '/debug/sheets'), name);
    let row = Number(rowRef);
    if (!Number.isInteger(row) || row < 1) {
      const at = s.values.findIndex((r, i) => i > 0 && String(r[0]).trim().toLowerCase() === String(rowRef).trim().toLowerCase());
      if (at < 0) throw new Error(`no row whose first cell is ${JSON.stringify(rowRef)} in ${name}`);
      row = at + 1;
    }
    let col = Number(colRef);
    if (!Number.isInteger(col) || col < 1) {
      const titles = (s.values[0] || []).map((t) => String(t).replace(/\s+/g, ' ').trim().toLowerCase());
      const at = titles.indexOf(String(colRef).replace(/\s+/g, ' ').trim().toLowerCase());
      if (at < 0) throw new Error(`no column titled ${JSON.stringify(colRef)} in row 1 of ${name}`);
      col = at + 1;
    }
    const body = { sheet: name, row, col };
    if (value !== undefined) body.value = looseValue(value);
    return JSON.stringify(body);
  },
  results() {
    const text = readStdin();
    const answer = parseJson(text, 'the answer');
    if (!answer.ok) return `not ok: ${answer.error || text.slice(0, 300)}`;
    const results = (answer.results || []).map((r) => `${r.id}: ${r.status}${r.error ? ` (${r.error})` : ''}`);
    return [`ok, revision ${answer.revision}${answer.version ? `, protocol ${answer.version}` : ''}`, ...results].join('\n');
  },
  compact() {
    const text = readStdin();
    try {
      return JSON.stringify(JSON.parse(text));
    } catch {
      return text.trimEnd();
    }
  },
};

const [cmd, ...args] = process.argv.slice(2);
if (!commands[cmd]) {
  console.error(`usage: node mockjson.js ${Object.keys(commands).join('|')} ...`);
  process.exitCode = 2;
} else {
  try {
    const out = commands[cmd](args);
    if (out !== '') process.stdout.write(`${out}\n`);
  } catch (err) {
    console.error(`mockjson: ${err.message}`);
    process.exitCode = 1;
  }
}
