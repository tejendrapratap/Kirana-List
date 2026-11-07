'use strict';
/**
 * Local stand-in for the deployed Apps Script web app, backed by the real Code.gs running in the
 * fake Apps Script environment. It reproduces Google's redirect dance: /exec answers HTTP 302 to a
 * one-off "echo" URL on the same host (Google uses script.googleusercontent.com), and that URL
 * returns the script output with HTTP 200.
 *
 *   node apps-script/test/mock-server.js
 *   Web app URL: http://127.0.0.1:8787/macros/s/TEST/exec   (Android emulator: http://10.0.2.2:8787/...)
 *
 * Environment:
 *   PORT         default 8787
 *   TOKEN        default "test-token" (the SECRET_TOKEN the app must send)
 *   FAIL_MODE    html = every exec call answers 200 with a Google sign-in page (access not "Anyone");
 *                404  = deployment not found;  500 = Google server error;
 *                apply_internal = imitates an old Code.gs on a sheet whose data validation refuses its
 *                writes: every apply (with the right token) answers {"ok":false,"error":"internal: <detail>"}
 *                and changes nothing, while ping and list keep working
 *   FAIL_DETAIL  the <detail> of apply_internal, default "Choose Boolean or Integer."
 *   CODE_GS      the script to serve: a path to a Code.gs, "current" (default: apps-script/Code.gs),
 *                "v3" (test/fixtures/Code.v3.gs, the script of app 1.1: version 3 with units, but no
 *                Added At column and no "addedAt" in lists; the sheet gets the layout Item | Need | Unit |
 *                Type | Updated At | Updated By) or "v2" (test/fixtures/Code.v2.gs, the script from before
 *                units: ping answers version 2, the sheet gets the old layout Item | Need | Type |
 *                Updated At | Updated By)
 *                e.g. CODE_GS=v3 node apps-script/test/mock-server.js
 *
 * Debug routes:
 *   GET  /debug/sheets                         JSON dump of every sheet (Dates as ISO strings)
 *   POST /debug/hand-edit {"row":2,"col":2,"value":true}   edits the Items sheet like a person and fires onEdit
 *        (optional "sheet", or "values": [[...], ...] for a multi-cell edit)
 *   POST /debug/fail-mode {"mode":"html"}      switches FAIL_MODE at runtime ("" = normal);
 *        {"mode":"apply_internal","detail":"Choose Boolean or Integer."} (detail optional)
 *   POST /debug/validation {"sheet":"Items","column":"Type","values":["Boolean","Integer"],"reject":true,
 *        "helpText":"Choose Boolean or Integer."}
 *        adds a dropdown rule like Data > Data validation on that column, rows 2..last row of the sheet
 *        ("column" is a number or a title in row 1 of that sheet; "sheet" defaults to Items, "reject" to
 *        true = "Reject the input", "helpText" is optional). With the rule above every upsert from the
 *        app gets "sheet_rejected: Choose Boolean or Integer. (data validation on the Type column of the
 *        Items sheet)". (Type is column D once the Unit column exists, C in the old layout.)
 *   POST /debug/validation {"clear":true}      removes every rule added this way
 *   POST /debug/seed-v2 {"items":[["Milk",true,"boolean"],["Rice",2,"integer",1762516800000,"Home"]],
 *        "history":[[1762516800000,"Rice","added",2,"integer","Home","id-1"]]}
 *        replaces the Items and History sheets with the layout of the script from before units, holding
 *        these rows (items: [name, need, type, updatedAt ms (default now), updatedBy (default "Home")] or
 *        {"name","need","type","updatedAt","updatedBy"}; history optional): the user's sheet before the
 *        new Code.gs runs. _Tombstones and the change-id cache are kept.
 *   GET  /debug/script                         which script is served and the version its ping reports
 *   POST /debug/script {"script":"current"}    serves another script from now on, keeping the sheets,
 *        cache and lock (like pasting a new Code.gs and deploying a new version): "current"
 *        (apps-script/Code.gs), "v3" (the script of app 1.1), "v2" (the script from before units) or
 *        "startup" (CODE_GS). The upgrade to the Added At column, as on the user's sheet: start with
 *        CODE_GS=v3, add items from the app, then {"script":"current"}: the next change from a phone adds
 *        the Added At column (old items keep it empty, new ones get it); {"script":"v3"} goes back.
 */
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { loadCodeGs, getEvent, postEvent, TextOutput, CODE_GS_PATH } = require('./fake-gas');

const HOST = '127.0.0.1';
const EXEC_PATH = '/macros/s/TEST/exec';
const ECHO_PATH = '/macros/echo';
const ECHO_TTL_MS = 60 * 1000;
const MAX_BODY_BYTES = 1024 * 1024;
const FAIL_MODES = new Set(['', 'html', '404', '500', 'apply_internal']);
const DEFAULT_FAIL_DETAIL = 'Choose Boolean or Integer.';
const MAX_FAIL_DETAIL = 200;
const V2_CODE_GS_PATH = path.join(__dirname, 'fixtures', 'Code.v2.gs');
const V3_CODE_GS_PATH = path.join(__dirname, 'fixtures', 'Code.v3.gs');
const MAX_SEED_ROWS = 500;

/**
 * CODE_GS: "current" or "" = apps-script/Code.gs, "v3" = the script of app 1.1 (before Added At), "v2" =
 * the script from before units, else a path.
 */
function resolveCodePath(codeGs) {
  const name = String(codeGs || '').trim();
  if (name === '' || name === 'current') return CODE_GS_PATH;
  if (name === 'v3') return V3_CODE_GS_PATH;
  if (name === 'v2') return V2_CODE_GS_PATH;
  const resolved = path.resolve(name);
  if (!fs.existsSync(resolved) || !fs.statSync(resolved).isFile()) throw new Error(`CODE_GS: no file at ${resolved}`);
  return resolved;
}

const HTML = 'text/html; charset=utf-8';
const JSON_TYPE = 'application/json; charset=utf-8';

const SIGN_IN_PAGE = `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Sign in - Google Accounts</title></head>
<body><div id="initialView"><h1>Sign in</h1><p>to continue to Google Drive</p>
<form method="post" action="https://accounts.google.com/v3/signin/identifier"><input type="email" name="identifier"></form>
</div></body></html>`;

const NOT_FOUND_PAGE = `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Google Drive - Page Not Found</title></head>
<body><p>Sorry, unable to open the file at this time.</p><p>Please check the address and try again.</p></body></html>`;

const SERVER_ERROR_PAGE = `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Error</title></head>
<body><p>We're sorry, a server error occurred. Please wait a bit and try again.</p></body></html>`;

/** Keeps the secret token out of the request log. */
function redact(url) {
  return url.replace(/([?&]token=)[^&]*/gi, '$1***');
}

/** First value wins, like Apps Script's e.parameter. */
function firstValues(searchParams) {
  const out = {};
  for (const [key, value] of searchParams) if (!(key in out)) out[key] = value;
  return out;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('body too large'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function send(res, status, contentType, body, headers = {}) {
  res.writeHead(status, { 'Content-Type': contentType, 'Cache-Control': 'no-cache, no-store', ...headers });
  res.end(body);
}

function sendJson(res, status, payload) {
  send(res, status, JSON_TYPE, JSON.stringify(payload, null, 2));
}

/**
 * @param {object} [options]
 * @param {string} [options.token='test-token']
 * @param {string} [options.failMode='']
 * @param {string} [options.failDetail] detail of the apply_internal fail mode
 * @param {(line: string) => void} [options.log=console.log]
 * @param {string} [options.codeGs] the script to serve: "current" (default), "v3", "v2" or a path (see CODE_GS)
 */
function createMockServer({
  token = 'test-token',
  failMode = '',
  failDetail = DEFAULT_FAIL_DETAIL,
  log = console.log,
  spreadsheetName = 'Grocery Sync (mock)',
  codeGs = '',
} = {}) {
  if (!FAIL_MODES.has(failMode)) throw new Error(`Unknown FAIL_MODE "${failMode}" (use html, 404, 500 or apply_internal)`);
  const startupCodePath = resolveCodePath(codeGs);
  const gas = loadCodeGs({ token, now: 'real', spreadsheetName, codePath: startupCodePath });
  gas.setupSheets();
  const state = { failMode, failDetail };
  const outputs = new Map();
  const addedRules = new Set(); // rules added through /debug/validation

  /**
   * apply_internal: what an old Code.gs answers when the sheet's data validation refuses its write
   * (it turned every unexpected exception into "internal: <message>"). Other requests, and an apply
   * with a wrong token (refused before any write), get the real script's answer.
   */
  function oldDeploymentApply(contents) {
    let body;
    try {
      body = JSON.parse(contents);
    } catch {
      return null;
    }
    if (!body || body.action !== 'apply' || typeof body.token !== 'string' || body.token.trim() !== token) return null;
    return new TextOutput(JSON.stringify({ ok: false, error: `internal: ${state.failDetail}` })).setMimeType('JSON');
  }

  function storeOutput(output) {
    const now = Date.now();
    for (const [key, entry] of outputs) if (entry.expiresAt < now) outputs.delete(key);
    const key = crypto.randomBytes(24).toString('base64url');
    outputs.set(key, { body: output.getContent(), contentType: output.contentType(), expiresAt: now + ECHO_TTL_MS });
    return key;
  }

  async function handleExec(req, res, url) {
    if (url.pathname !== EXEC_PATH) return send(res, 404, HTML, NOT_FOUND_PAGE);
    if (req.method !== 'GET' && req.method !== 'POST') return send(res, 405, HTML, NOT_FOUND_PAGE);
    if (state.failMode === 'html') return send(res, 200, HTML, SIGN_IN_PAGE);
    if (state.failMode === '404') return send(res, 404, HTML, NOT_FOUND_PAGE);
    if (state.failMode === '500') return send(res, 500, HTML, SERVER_ERROR_PAGE);

    const parameter = firstValues(url.searchParams);
    let output;
    if (req.method === 'GET') {
      output = gas.doGet(getEvent(parameter));
    } else {
      const contents = await readBody(req);
      const type = (req.headers['content-type'] || 'application/octet-stream').split(';')[0].trim();
      if (state.failMode === 'apply_internal') output = oldDeploymentApply(contents);
      if (!output) output = gas.doPost(postEvent(contents, type, parameter));
    }

    const host = req.headers.host || `${HOST}:${server.address().port}`;
    const location = `http://${host}${ECHO_PATH}?user_content_key=${storeOutput(output)}`;
    send(res, 302, HTML, `<HTML><HEAD><TITLE>Moved Temporarily</TITLE></HEAD><BODY>The document has moved <A HREF="${location}">here</A>.</BODY></HTML>`, { Location: location });
  }

  function handleEcho(req, res, url) {
    const entry = outputs.get(url.searchParams.get('user_content_key') || '');
    if (req.method !== 'GET' || !entry || entry.expiresAt < Date.now()) return send(res, 404, HTML, NOT_FOUND_PAGE);
    send(res, 200, entry.contentType, entry.body);
  }

  function dumpSheets() {
    return {
      spreadsheet: gas.spreadsheet.getName(),
      sheets: gas.spreadsheet.getSheets().map((sheet) => {
        const values = sheet.dump();
        const validations = values.length
          ? sheet.getRange(1, 1, values.length, values[0].length).getDataValidations()
            .map((row) => row.map((rule) => (rule ? rule.getCriteriaType() : null)))
          : [];
        return { name: sheet.getName(), hidden: sheet.isSheetHidden(), frozenRows: sheet.getFrozenRows(), values, validations };
      }),
    };
  }

  async function handleHandEdit(req, res) {
    let edit;
    try {
      edit = JSON.parse(await readBody(req));
    } catch {
      return sendJson(res, 400, { ok: false, error: 'body must be JSON like {"row":2,"col":2,"value":true}' });
    }
    const { row, col, value, values, sheet = 'Items' } = edit || {};
    if (!Number.isInteger(row) || !Number.isInteger(col) || row < 1 || col < 1) {
      return sendJson(res, 400, { ok: false, error: 'row and col must be integers >= 1' });
    }
    if (values !== undefined && !(Array.isArray(values) && values.length && values.every((r) => Array.isArray(r) && r.length === values[0].length))) {
      return sendJson(res, 400, { ok: false, error: 'values must be a non-empty rectangular 2D array' });
    }
    if (values === undefined && value === undefined) return sendJson(res, 400, { ok: false, error: 'value or values is required' });
    try {
      const event = gas.handEdit({ sheet, row, col, value, values });
      sendJson(res, 200, { ok: true, sheet, range: event.range.getA1Notation() });
    } catch (err) {
      sendJson(res, 400, { ok: false, error: err.message });
    }
  }

  async function handleFailMode(req, res) {
    let mode;
    let detail;
    try {
      const body = JSON.parse(await readBody(req));
      mode = String(body.mode ?? '');
      detail = body.detail;
    } catch {
      return sendJson(res, 400, { ok: false, error: 'body must be JSON like {"mode":"html"}' });
    }
    if (!FAIL_MODES.has(mode)) {
      return sendJson(res, 400, { ok: false, error: 'mode must be "", "html", "404", "500" or "apply_internal"' });
    }
    if (detail !== undefined && (typeof detail !== 'string' || detail.trim() === '' || detail.length > MAX_FAIL_DETAIL)) {
      return sendJson(res, 400, { ok: false, error: `detail must be a non-empty string of at most ${MAX_FAIL_DETAIL} characters` });
    }
    state.failMode = mode;
    if (mode === 'apply_internal') state.failDetail = detail === undefined ? DEFAULT_FAIL_DETAIL : detail.trim();
    sendJson(res, 200, mode === 'apply_internal' ? { ok: true, mode, detail: state.failDetail } : { ok: true, mode });
  }

  async function handleValidation(req, res) {
    let body;
    try {
      body = JSON.parse(await readBody(req));
    } catch {
      return sendJson(res, 400, { ok: false, error: 'body must be JSON like {"column":3,"values":["Boolean","Integer"]} or {"clear":true}' });
    }
    if (body && body.clear === true) {
      const cleared = gas.spreadsheet.getSheets().reduce((n, sheet) => n + sheet.removeDataValidationsForTest(addedRules), 0);
      addedRules.clear();
      return sendJson(res, 200, { ok: true, cleared });
    }
    const { sheet = 'Items', values, reject = true, helpText } = body || {};
    const target = gas.spreadsheet.getSheetByName(sheet);
    if (!target) return sendJson(res, 400, { ok: false, error: `no sheet named ${JSON.stringify(sheet)}` });
    let column = body && body.column;
    if (typeof column === 'string') {
      const title = (v) => String(v).replace(/\s+/g, ' ').trim().toLowerCase();
      const index = target.getRange(1, 1, 1, target.getMaxColumns()).getValues()[0].map(title).indexOf(title(column));
      if (index < 0) return sendJson(res, 400, { ok: false, error: `no column titled ${JSON.stringify(column)} in row 1 of ${sheet}` });
      column = index + 1;
    }
    if (!Number.isInteger(column) || column < 1 || column > target.getMaxColumns()) {
      return sendJson(res, 400, { ok: false, error: `column must be a title in row 1 or an integer from 1 to ${target.getMaxColumns()}` });
    }
    if (!Array.isArray(values) || !values.length || !values.every((v) => typeof v === 'string' || typeof v === 'number')) {
      return sendJson(res, 400, { ok: false, error: 'values must be a non-empty array of strings' });
    }
    if (typeof reject !== 'boolean') return sendJson(res, 400, { ok: false, error: 'reject must be true or false' });
    if (helpText !== undefined && typeof helpText !== 'string') return sendJson(res, 400, { ok: false, error: 'helpText must be a string' });
    if (target.getMaxRows() < 2) return sendJson(res, 400, { ok: false, error: 'the sheet has no data rows' });
    const rule = gas.addListValidation({ sheet, column, values: values.map(String), reject, helpText });
    addedRules.add(rule);
    const range = target.getRange(2, column, target.getMaxRows() - 1, 1).getA1Notation();
    sendJson(res, 200, { ok: true, sheet, range, values: values.map(String), reject, helpText: helpText || null });
  }

  async function handleSeedV2(req, res) {
    let body;
    try {
      body = JSON.parse(await readBody(req));
    } catch {
      return sendJson(res, 400, { ok: false, error: 'body must be JSON like {"items":[["Milk",true,"boolean"],["Rice",2,"integer"]]}' });
    }
    const { items = [], history = [] } = body || {};
    const isRow = (r) => Array.isArray(r) || (r !== null && typeof r === 'object');
    if (!Array.isArray(items) || items.length > MAX_SEED_ROWS || !items.every(isRow)) {
      return sendJson(res, 400, { ok: false, error: `items must be an array of at most ${MAX_SEED_ROWS} rows (arrays or objects)` });
    }
    if (!Array.isArray(history) || history.length > MAX_SEED_ROWS || !history.every(Array.isArray)) {
      return sendJson(res, 400, { ok: false, error: `history must be an array of at most ${MAX_SEED_ROWS} rows (arrays)` });
    }
    try {
      const seeded = gas.seedV2Layout({ items, history });
      sendJson(res, 200, { ok: true, layout: 'v2', ...seeded });
    } catch (err) {
      sendJson(res, 400, { ok: false, error: err.message });
    }
  }

  /** The served script and the protocol version its ping reports. */
  function scriptInfo() {
    const ping = JSON.parse(gas.doGet(getEvent({ action: 'ping', token })).getContent());
    return { ok: true, path: gas.codePath, version: ping.version ?? null };
  }

  async function handleScript(req, res) {
    if (req.method === 'GET') return sendJson(res, 200, scriptInfo());
    let script;
    try {
      script = String(JSON.parse(await readBody(req)).script ?? '');
    } catch {
      return sendJson(res, 400, { ok: false, error: 'body must be JSON like {"script":"current"}' });
    }
    const paths = { current: CODE_GS_PATH, v3: V3_CODE_GS_PATH, v2: V2_CODE_GS_PATH, startup: startupCodePath };
    if (!Object.prototype.hasOwnProperty.call(paths, script)) {
      return sendJson(res, 400, { ok: false, error: 'script must be "current", "v3", "v2" or "startup"' });
    }
    gas.reloadCode(paths[script]);
    sendJson(res, 200, scriptInfo());
  }

  async function route(req, res) {
    const url = new URL(req.url, `http://${HOST}`);
    if (url.pathname.startsWith('/macros/s/')) return handleExec(req, res, url);
    if (url.pathname === ECHO_PATH) return handleEcho(req, res, url);
    if (url.pathname === '/debug/sheets' && req.method === 'GET') return sendJson(res, 200, dumpSheets());
    if (url.pathname === '/debug/hand-edit' && req.method === 'POST') return handleHandEdit(req, res);
    if (url.pathname === '/debug/fail-mode' && req.method === 'POST') return handleFailMode(req, res);
    if (url.pathname === '/debug/validation' && req.method === 'POST') return handleValidation(req, res);
    if (url.pathname === '/debug/seed-v2' && req.method === 'POST') return handleSeedV2(req, res);
    if (url.pathname === '/debug/script' && (req.method === 'GET' || req.method === 'POST')) return handleScript(req, res);
    return sendJson(res, 404, { ok: false, error: `no route for ${req.method} ${url.pathname}` });
  }

  const server = http.createServer((req, res) => {
    const started = Date.now();
    res.on('finish', () => {
      log(`${new Date().toISOString()} ${req.method} ${redact(req.url)} -> ${res.statusCode} (${Date.now() - started} ms)`);
    });
    route(req, res).catch((err) => {
      if (res.headersSent) return res.destroy();
      send(res, err.status || 500, 'text/plain; charset=utf-8', `mock server error: ${err.message}`);
    });
  });

  return {
    server,
    gas,
    state,
    /** Resolves with the bound port and the web app URL to configure in the app. */
    listen(port = 8787, host = HOST) {
      return new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(port, host, () => {
          server.off('error', reject);
          const bound = server.address().port;
          resolve({ port: bound, url: `http://${host}:${bound}${EXEC_PATH}` });
        });
      });
    },
    close() {
      return new Promise((resolve) => {
        server.close(() => resolve());
        server.closeAllConnections();
      });
    },
  };
}

// Started directly (not when loaded by tests or by the node --test runner).
if (require.main === module && !process.env.NODE_TEST_CONTEXT) {
  const port = Number(process.env.PORT || 8787);
  const token = process.env.TOKEN || 'test-token';
  const failMode = process.env.FAIL_MODE || '';
  const failDetail = process.env.FAIL_DETAIL || DEFAULT_FAIL_DETAIL;
  const codeGs = process.env.CODE_GS || '';
  let mock;
  try {
    mock = createMockServer({ token, failMode, failDetail, codeGs });
  } catch (err) {
    console.error(err.message);
    process.exit(2);
  }
  mock.listen(port).then(({ url }) => {
    const failInfo = failMode ? `, FAIL_MODE=${failMode}${failMode === 'apply_internal' ? ` "${failDetail}"` : ''}` : '';
    const scriptNote = codeGs ? `, CODE_GS=${mock.gas.codePath}` : '';
    console.log(`Grocery Sync mock web app: ${url}  (token "${token}"${failInfo}${scriptNote})`);
    console.log(`Android emulator URL:      http://10.0.2.2:${port}${EXEC_PATH}`);
  }, (err) => {
    console.error(`Could not listen on ${HOST}:${port}: ${err.message}`);
    process.exit(1);
  });
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => mock.close().then(() => process.exit(0)));
}

module.exports = { createMockServer, resolveCodePath, EXEC_PATH, ECHO_PATH, V2_CODE_GS_PATH, V3_CODE_GS_PATH };
