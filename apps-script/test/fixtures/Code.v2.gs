// TEST FIXTURE - do not paste this file into Apps Script; the current script is apps-script/Code.gs.
// The Code.gs of protocol version 2 (before units), unchanged below this note: the tests and the mock
// server (CODE_GS=v2) run it to build sheets the way the older script did and to imitate a sheet whose
// script has not been updated yet.

/**
 * Grocery Sync - Google Apps Script backend
 * =========================================
 * Turns this Google Sheet into the shared grocery list behind the Grocery Sync Android app.
 * The sheet is the single source of truth and stays editable by hand.
 *
 * SET UP (once)
 *   1. Open your Google Sheet and choose Extensions > Apps Script. Replace everything in Code.gs
 *      with this file and click Save.
 *   2. Change SECRET_TOKEN below to your own long, random secret. Tip: pick "generateToken" in the
 *      function list at the top of the editor, click Run and copy the token from the Execution log.
 *      Save again.
 *   3. Pick "setupSheets" in the function list and click Run once. Approve the authorization prompt
 *      (for your own script, "Google hasn't verified this app" > Advanced > Go to ... is expected).
 *      This creates the Items and History sheets and a hidden _Tombstones sheet. The Execution log
 *      also warns about data validation rules that would make the sheet refuse the app's changes.
 *   4. Deploy > New deployment > Select type (gear icon) > Web app.
 *      Execute as: Me.  Who has access: Anyone.  Click Deploy.
 *   5. Copy the Web app URL (it ends in /exec). In the app open Settings, paste the URL and the
 *      secret token, tap Save. The second phone can join with "Share setup with another phone".
 *
 * AFTER EVERY CHANGE TO THIS CODE (including a new SECRET_TOKEN)
 *   Deploy > Manage deployments > Edit (pencil icon) > Version: New version > Deploy.
 *   The URL stays the same. Saving alone does not update the running web app.
 *
 * EDITING THE SHEET BY HAND
 *   "Items" has one row per item. Need is a checkbox when Type is "boolean", or a quantity when Type
 *   is "integer" (0 = not needed). Editing Item, Need or Type stamps Updated At / Updated By.
 *   To remove an item, delete it in the app, or select its Item cell and press Delete (rows without an
 *   Item are ignored; tidy them up whenever you like). Clearing or renaming an Item cell is remembered
 *   as a deletion, so a phone that was offline cannot bring the old item back. Deleting a whole row is
 *   not remembered that way, so prefer clearing the Item cell.
 *   Inserting, deleting or sorting rows is safest while nobody is changing the list in the app.
 *   Do not rename or reorder the header columns, and keep the spreadsheet's time zone (File > Settings)
 *   as it is once items exist (stored times would shift).
 *   Do not add data validation set to "Reject the input" (Data > Data validation) to these sheets:
 *   the script writes Type as "boolean" or "integer" and the other cells as the app sends them, and
 *   Sheets refuses any value such a rule does not allow. The app then shows the refused change with
 *   the sheet's message (for example "Choose Boolean or Integer.") and the column, the rest of the
 *   list keeps syncing; remove or relax the rule and tap Retry in the app.
 */

const CONFIG = {
  SECRET_TOKEN: 'CHANGE_ME',   // must be changed; 'CHANGE_ME' or '' -> every request answers server_not_configured
  SPREADSHEET_URL: '',         // leave empty when the script is opened from the sheet (Extensions > Apps Script); else the sheet URL (openByUrl). onEdit only works for the bound case.
  ITEMS_SHEET: 'Items',
  HISTORY_SHEET: 'History',
  TOMBSTONE_SHEET: '_Tombstones',
  KEEP_HISTORY: true,
};

const APP_ID_ = 'grocery-sync';
const PROTOCOL_VERSION_ = 2;
const UNSET_TOKEN_ = 'CHANGE_ME';

const MAX_CHANGES_ = 100;
const MAX_CHANGE_ID_LENGTH_ = 64;
const MAX_NAME_LENGTH_ = 100;         // for items the app adds; names typed into the sheet may be longer
const MAX_CELL_TEXT_LENGTH_ = 50000;  // what a Sheets cell can hold
const MAX_QUANTITY_ = 999999;
const MAX_DEVICE_LENGTH_ = 40;
const DEFAULT_DEVICE_ = 'Phone';
const SHEET_EDITOR_ = 'Sheet';

const LOCK_TIMEOUT_MS_ = 30000;
const EDIT_LOCK_TIMEOUT_MS_ = 15000;  // onEdit's wait for an apply (simple triggers may run for 30 s at most)
const APPLY_ATTEMPTS_ = 3;            // re-reads of Items when a person changes it while an apply runs
// Counted from taking the script lock: an apply starts no further change one at a time after this, so it
// holds the lock well under onEdit's wait (one more change and the History append may follow) and,
// lock wait included, answers well within the app's 60 s wait. Changes not tried are answered "deferred".
const APPLY_TIME_BUDGET_MS_ = 5000;
const DEDUPE_TTL_SECONDS_ = 21600;
const TOMBSTONE_TTL_MS_ = 30 * 24 * 60 * 60 * 1000;
const DATE_FORMAT_ = 'yyyy-mm-dd hh:mm:ss';

const ITEMS_HEADER_ = ['Item', 'Need', 'Type', 'Updated At', 'Updated By'];
const HISTORY_HEADER_ = ['Time', 'Item', 'Action', 'Need', 'Type', 'By', 'Change ID'];
const TOMBSTONE_HEADER_ = ['Key', 'Deleted At', 'By'];
const COL_ = { ITEM: 1, NEED: 2, TYPE: 3, UPDATED_AT: 4, UPDATED_BY: 5 };

const TRUE_WORDS_ = ['true', 'yes', 'y', 'x', '1'];
const FALSE_WORDS_ = ['false', 'no', 'n', '0', ''];

// Columns (0-based) holding user-supplied text, written through guardTextCells_.
const ITEM_TEXT_COLUMNS_ = [COL_.ITEM - 1, COL_.UPDATED_BY - 1];
const HISTORY_TEXT_COLUMNS_ = [1, 5, 6];
const TOMBSTONE_TEXT_COLUMNS_ = [0, 2];
const PLAIN_TEXT_FORMAT_ = '@';

// A change the sheet itself refused: per-change error "sheet_rejected: <the sheet's message> (where)".
const SHEET_REJECTED_ = 'sheet_rejected: ';
const MAX_REJECTION_DETAIL_ = 180;
// Apps Script messages of write failures. err.message has no "Exception: " prefix (the log adds it);
// the sheet's own refusals carry a rule's help text, which can say anything, so they are matched first.
const PROTECTED_MESSAGE_RE_ = /protected (?:cell|range|sheet|object)/i; // "You are trying to edit a protected cell or object. ..."
const REFUSAL_MESSAGE_RE_ = new RegExp([
  'violates the data validation rules', // "The data you entered in cell C2 violates the data validation rules set on this cell."
  'data validation',
  PROTECTED_MESSAGE_RE_.source,
  'above the limit of [\\d,.]+ cells', // "This action would increase the number of cells in the workbook above the limit of 10000000 cells."
].join('|'), 'i');
// Google-side trouble worth retrying: "Service Spreadsheets timed out / failed while accessing document
// with id ...", "Service unavailable: Spreadsheets", "Service invoked too many times in a short time",
// "Too many simultaneous invocations: Spreadsheets", "We're sorry, a server error occurred. Please wait
// a bit and try again.", "Unexpected error while getting the method or property ...", "Exceeded maximum
// execution time", "Lock timeout ...", quota and rate limits.
const TRANSIENT_MESSAGE_RE_ = new RegExp([
  'timed out', 'time-?out', 'unavailable', 'too many', 'rate limit', 'quota', 'try again', 'please wait',
  'server error', 'internal error', 'service error', 'backend error', 'failed while accessing', 'temporar',
  'unexpected error', 'unknown error', 'maximum execution time', 'simultaneous invocations', '\\block\\b',
].join('|'), 'i');

// ---------------------------------------------------------------------------------------------
// Public entry points
// ---------------------------------------------------------------------------------------------

/** GET ?action=ping|list&token=T[&since=REV] */
function doGet(e) {
  return respond_(() => {
    const params = (e && e.parameter) || {};
    authorize_(params.token);
    switch (params.action) {
      case 'ping':
        return handlePing_();
      case 'list':
        return handleList_(params.since);
      default:
        throw badRequest_('unknown action ' + describeValue_(params.action));
    }
  });
}

/** POST {"token","action":"apply","device","changes":[...]} */
function doPost(e) {
  return respond_(() => {
    requireConfigured_();
    const body = parseJsonBody_(e);
    authorize_(body.token);
    if (body.action !== 'apply') throw badRequest_('unknown action ' + describeValue_(body.action));
    return handleApply_(body);
  });
}

/**
 * Simple trigger: stamps Updated At / Updated By when a person edits Item, Need or Type by hand, and
 * remembers an Item cell that was cleared or renamed as a deletion of the old name.
 */
function onEdit(e) {
  let lock = null;
  try {
    const range = e && e.range;
    if (!range) return;
    const sheet = range.getSheet();
    if (sheet.getName() !== CONFIG.ITEMS_SHEET || range.getColumn() > COL_.TYPE) return;
    const firstRow = Math.max(range.getRow(), 2);
    const lastRow = range.getRow() + range.getNumRows() - 1;
    if (lastRow < firstRow) return;

    // Hand edits never wait for the app's changes, but this trigger does, so an apply cannot move rows
    // between the reads and the writes below.
    const edit = acquireEditLock_();
    lock = edit.lock;
    // An apply that ran first may have deleted rows above the edited one: the address then no longer
    // shows what the person typed, and stamping it would mark a different item as changed.
    if (edit.waited && editMoved_(e, range)) return;

    const names = sheet.getRange(firstRow, COL_.ITEM, lastRow - firstRow + 1, 1).getValues();
    const now = new Date();
    // Rows whose Item is now blank were cleared, not edited: leave them alone.
    forEachRun_(names, (cells) => cellToString_(cells[0]) !== '', (start, run) => {
      const target = sheet.getRange(firstRow + start, COL_.UPDATED_AT, run.length, 2);
      target.setValues(run.map(() => [now, SHEET_EDITOR_]));
      sheet.getRange(firstRow + start, COL_.UPDATED_AT, run.length, 1).setNumberFormat(DATE_FORMAT_);
    });
    rememberHandRemoval_(e, range, now.getTime());
  } catch (err) {
    console.error('onEdit failed: ' + ((err && err.stack) || err));
  } finally {
    releaseEditLock_(lock);
  }
}

/** Run once from the editor: creates the sheets and triggers the authorization prompt. */
function setupSheets() {
  const ss = openSpreadsheet_();
  const warnings = withScriptLock_(() => strictValidationWarnings_(ensureSheets_(ss)));
  Logger.log('Grocery Sync sheets are ready in the spreadsheet "' + ss.getName() + '".');
  warnings.forEach((warning) => console.warn(warning));
  Logger.log(isConfigured_()
      ? 'Next: Deploy > New deployment > Web app (Execute as: Me, Who has access: Anyone) and copy the /exec URL into the app.'
      : 'Next: set CONFIG.SECRET_TOKEN (run generateToken for a random one), save, then Deploy > New deployment > Web app.');
  return ss.getName();
}

/** Logs a random secret to paste into CONFIG.SECRET_TOKEN and into the app. */
function generateToken() {
  const token = (Utilities.getUuid() + Utilities.getUuid()).replace(/-/g, '');
  Logger.log('New secret token (put it in CONFIG.SECRET_TOKEN and in the app): ' + token);
  return token;
}

// ---------------------------------------------------------------------------------------------
// Request handlers
// ---------------------------------------------------------------------------------------------

function handlePing_() {
  const ss = openSpreadsheet_();
  return { ok: true, app: APP_ID_, version: PROTOCOL_VERSION_, spreadsheet: ss.getName() };
}

function handleList_(since) {
  const ss = openSpreadsheet_();
  let sheet = ss.getSheetByName(CONFIG.ITEMS_SHEET);
  if (!sheet) sheet = withScriptLock_(() => ensureSheets_(ss)).items;
  const items = tableItems_(loadItemTable_(sheet));
  const revision = revisionOf_(items);
  const serverTime = Date.now();
  if (since && since === revision) return { ok: true, revision, serverTime, unchanged: true };
  return { ok: true, revision, serverTime, spreadsheet: ss.getName(), items };
}

/**
 * Applies a batch of changes. Every change gets one result, in order: "applied"; "stale" (the sheet
 * already holds a newer edit or deletion); "duplicate" (this change id was applied before); "error"
 * with a short reason, which starts with "sheet_rejected: " when the sheet itself refused the write
 * (e.g. data validation set to "Reject the input"): "sheet_rejected: <the sheet's message> (where)";
 * or "deferred": not tried because the request ran out of time, nothing written, send it again.
 *
 * The batch is read and written in one go. When the sheet refuses a write, the changes are applied
 * again one at a time, each from a fresh read, so the changes the sheet accepts are saved and only the
 * refused ones fail. Google-side trouble (time-outs, quotas, ...) still fails the whole request with
 * "internal: ..." (or "busy"), so the app retries it later.
 */
function handleApply_(body) {
  const changes = body.changes;
  if (!Array.isArray(changes)) throw badRequest_('changes must be an array');
  if (changes.length === 0) throw badRequest_('changes must not be empty');
  if (changes.length > MAX_CHANGES_) throw badRequest_('too many changes (max ' + MAX_CHANGES_ + ')');
  const device = cleanDeviceName_(body.device);
  const ss = openSpreadsheet_();

  return withScriptLock_(() => {
    const deadline = Date.now() + APPLY_TIME_BUDGET_MS_;
    const sheets = ensureSheets_(ss);
    const alreadyApplied = recallAppliedChangeIds_(changes);
    let outcome = applyBatch_(sheets, device, changes, alreadyApplied);
    if (outcome.refused) outcome = applyOneByOne_(sheets, device, changes, alreadyApplied, deadline, outcome.addedIds);
    finishApply_(sheets, outcome);
    // One at a time, the last in-memory state may hold a refused change: answer with what the sheet holds.
    const items = tableItems_(outcome.table || loadItemTable_(sheets.items));
    return { ok: true, results: outcome.results, revision: revisionOf_(items), serverTime: outcome.now, spreadsheet: ss.getName(), items };
  });
}

/**
 * Reads the sheets, applies the whole batch in memory and writes it. When the sheet refused a write,
 * returns { refused: true, addedIds } after logging why: the caller then applies the changes one at a
 * time. addedIds = the changes that add a new item, whose rows the batch may already have written.
 */
function applyBatch_(sheets, device, changes, alreadyApplied) {
  for (let attempt = 1; ; attempt++) {
    const state = readState_(sheets, device);
    const outcome = applyChanges_(state, changes, alreadyApplied);
    if (!rowsStillValid_(sheets, state)) {
      if (attempt < APPLY_ATTEMPTS_) continue;
      throw apiError_('busy');
    }
    try {
      saveState_(sheets, state, false);
    } catch (err) {
      const failure = classifyWriteFailure_(err);
      if (!failure.refused) throw err;
      console.warn('Grocery Sync: the sheet refused a write (' + failure.detail + '); applying the ' +
          changes.length + ' change(s) one at a time.');
      const addedIds = new Set(state.history.filter((row) => row[2] === 'added').map((row) => row[6]));
      return { refused: true, addedIds };
    }
    return { refused: false, results: outcome.results, appliedIds: outcome.appliedIds, history: state.history, table: state.table, now: state.now };
  }
}

/**
 * Applies the changes one at a time, each from a fresh read, so a change the sheet refuses fails alone.
 * A refused write may have been partly done; the fresh reads see what the sheet really holds (see
 * saveState_ for why that is always a consistent state). Changes that would start after the time
 * budget are answered "deferred" (at least one is always tried). A transient error or "busy" still
 * fails the request, after saving the history and change ids of what was applied so far.
 */
function applyOneByOne_(sheets, device, changes, alreadyApplied, deadline, addedIds) {
  const progress = { results: [], appliedIds: [], history: [], table: null, now: 0, addedIds };
  const seen = new Set();
  let tried = false;
  try {
    changes.forEach((change) => {
      const settled = settleWithoutSheet_(change, seen, alreadyApplied);
      if (settled) {
        progress.results.push(settled);
      } else if (tried && Date.now() > deadline) {
        progress.results.push({ id: change.id, status: 'deferred' });
      } else {
        seen.add(change.id);
        tried = true;
        progress.results.push(applyAlone_(sheets, device, change, progress));
      }
    });
  } catch (err) {
    finishApply_(sheets, progress);
    throw err;
  }
  progress.now = Date.now();
  return progress;
}

/** Applies and writes one (valid, new) change; returns its result. */
function applyAlone_(sheets, device, change, progress) {
  for (let attempt = 1; ; attempt++) {
    const state = readState_(sheets, device);
    const result = applyChange_(state, change);
    if (result.status !== 'applied') return changeResult_(change.id, result); // nothing to write
    if (!rowsStillValid_(sheets, state)) {
      if (attempt < APPLY_ATTEMPTS_) continue;
      throw apiError_('busy');
    }
    try {
      // An upsert only drops an old tombstone (housekeeping); a delete's tombstone is part of it.
      saveState_(sheets, state, change.op === 'upsert');
    } catch (err) {
      const failure = classifyWriteFailure_(err);
      if (!failure.refused) throw err;
      console.warn('Grocery Sync: the sheet refused the change to "' + clip_(change.name.trim(), 60) + '": ' + failure.detail);
      return { id: change.id, status: 'error', error: SHEET_REJECTED_ + failure.detail };
    }
    progress.appliedIds.push(change.id);
    state.history.forEach((row) => {
      // The refused batch may have written this new item's row already: it was still added now.
      if (row[2] === 'updated' && progress.addedIds.has(change.id)) row[2] = 'added';
      progress.history.push(row);
    });
    return { id: change.id, status: 'applied' };
  }
}

/** History (best effort) and change-id dedupe for the changes that were applied. */
function finishApply_(sheets, outcome) {
  appendHistoryQuietly_(sheets.history, outcome.history);
  rememberAppliedChangeIds_(outcome.appliedIds);
}

function readState_(sheets, device) {
  const now = Date.now();
  return {
    now,
    device,
    table: loadItemTable_(sheets.items),
    tombstones: loadTombstones_(sheets.tombstones, now),
    history: [],
  };
}

/**
 * Rows are written by position. A person may have inserted, deleted, sorted or edited rows since they
 * were read (hand edits never wait for the script lock): then the caller starts again from a fresh read.
 */
function rowsStillValid_(sheets, state) {
  return !tableHasWrites_(state.table) || itemRowsUnchanged_(sheets.items, state.table);
}

/**
 * Persists the in-memory state in an order that keeps a refused write harmless:
 *  1. Items values (setValues is all or nothing): a refusal leaves no row deleted and no tombstone
 *     written; at most earlier blocks of changed existing rows of the same batch are written, which a
 *     replay writes again unchanged, and the Need rules of the refused block are put back.
 *  2. Tombstones: a delete whose tombstone is refused keeps its row (no resurrection risk).
 *  3. Row deletions, which data validation cannot refuse.
 * Apps Script may report a write's failure only at the next flush (or read), so each part flushes
 * before the next one starts: a failure surfaces in the part that caused it (tagged with its cells,
 * see tagWrite_), never inside another part or after rows are gone.
 * tombstonesBestEffort: a failed tombstone write is only logged (an upsert merely drops an old one).
 */
function saveState_(sheets, state, tombstonesBestEffort) {
  const plan = planItemWrites_(state.table);
  writeItemRows_(sheets.items, plan);
  // A late Items failure must surface before the tombstones are written and before rows are deleted.
  if (plan.writes.length && (state.tombstones.dirty || plan.doomedRows.length)) SpreadsheetApp.flush();
  if (state.tombstones.dirty) {
    if (tombstonesBestEffort) saveTombstonesQuietly_(sheets.tombstones, state.tombstones);
    else saveTombstones_(sheets.tombstones, state.tombstones); // flushes inside, before any row is deleted
  }
  deleteItemRows_(sheets.items, plan);
  SpreadsheetApp.flush();
}

// ---------------------------------------------------------------------------------------------
// Applying changes (in memory; persisted by saveState_ and appendHistory_)
// ---------------------------------------------------------------------------------------------

/** Applies the batch in order to the in-memory state; one result per change. */
function applyChanges_(state, changes, alreadyApplied) {
  const seen = new Set();
  const appliedIds = [];
  const results = changes.map((change) => {
    const settled = settleWithoutSheet_(change, seen, alreadyApplied);
    if (settled) return settled;
    seen.add(change.id);
    const result = applyChange_(state, change);
    if (result.status === 'applied') appliedIds.push(change.id);
    return changeResult_(change.id, result);
  });
  return { results, appliedIds };
}

/** The result of a malformed or duplicate change (decided without the sheet), else null. */
function settleWithoutSheet_(change, seen, alreadyApplied) {
  const id = isPlainObject_(change) && typeof change.id === 'string' ? change.id : '';
  const error = validateChange_(change);
  if (error) return { id, status: 'error', error };
  if (seen.has(id) || alreadyApplied.has(id)) return { id, status: 'duplicate' };
  return null;
}

function changeResult_(id, result) {
  return result.error ? { id, status: result.status, error: result.error } : { id, status: result.status };
}

function applyChange_(state, change) {
  const name = change.name.trim();
  const key = normalizeKey_(name);
  const time = effectiveTime_(change.time, state.now);
  return change.op === 'upsert'
      ? applyUpsert_(state, change, name, key, time)
      : applyDelete_(state, change, name, key, time);
}

function applyUpsert_(state, change, name, key, time) {
  const record = findRecord_(state.table, key);
  if (!record) {
    // Only new names must follow the app's rules: a name typed into the sheet stays changeable.
    const nameError = validateNewName_(name);
    if (nameError) return { status: 'error', error: nameError };
  }
  const tombstone = state.tombstones.entries.get(key);
  if (tombstone && sheetTime_(tombstone.deletedAt, state.now) > time) return { status: 'stale' };
  if (record && time < sheetTime_(record.item.updatedAt, state.now)) return { status: 'stale' };

  const item = { name, type: change.type, value: change.value, updatedAt: time, updatedBy: state.device };
  let action;
  if (record) {
    record.item = item;
    record.dirty = true;
    action = 'updated';
  } else {
    state.table.records.push({ row: 0, key, item, dirty: true, deleted: false });
    action = 'added';
  }
  if (tombstone) removeTombstone_(state.tombstones, key);
  state.history.push(historyRow_(time, name, action, item.value, item.type, state.device, change.id));
  return { status: 'applied' };
}

function applyDelete_(state, change, name, key, time) {
  const record = findRecord_(state.table, key);
  if (record && time < sheetTime_(record.item.updatedAt, state.now)) return { status: 'stale' };
  if (record) {
    // Hand-entered duplicates of the same item are removed too, so they cannot surface afterwards.
    state.table.records.forEach((r) => {
      if (r.key === key) r.deleted = true;
    });
  }
  putTombstone_(state.tombstones, key, time, state.device);
  state.history.push(record
      ? historyRow_(time, record.item.name, 'deleted', record.item.value, record.item.type, state.device, change.id)
      : historyRow_(time, name, 'deleted', '', '', state.device, change.id));
  return { status: 'applied' };
}

function effectiveTime_(time, now) {
  if (typeof time !== 'number' || !isFinite(time) || time < 0) return now;
  return Math.min(Math.floor(time), now);
}

/**
 * A time read from the sheet, capped at the server clock. Stored times can lie in the future (the
 * spreadsheet's time zone was changed, the hour repeated when daylight saving ends, a date typed by
 * hand); uncapped, they would make every change from the app "stale" until that time has passed.
 */
function sheetTime_(ms, now) {
  return Math.min(ms, now);
}

/** Returns a short reason when the change is malformed, else null. */
function validateChange_(change) {
  if (!isPlainObject_(change)) return 'change must be an object';
  if (!isValidChangeId_(change.id)) return 'invalid id';
  if (change.op !== 'upsert' && change.op !== 'delete') return 'invalid op';
  if (typeof change.name !== 'string' || change.name.trim() === '') return 'name is required';
  if (change.name.length > MAX_CELL_TEXT_LENGTH_) return 'name is too long';
  if (change.op === 'delete') return null;
  if (change.type === 'boolean') {
    return typeof change.value === 'boolean' ? null : 'value must be true or false for type boolean';
  }
  if (change.type === 'integer') {
    const v = change.value;
    return Number.isSafeInteger(v) && v >= 0 && v <= MAX_QUANTITY_
        ? null : 'value must be a whole number from 0 to ' + MAX_QUANTITY_ + ' for type integer';
  }
  return 'invalid type';
}

/** Rules for the (trimmed) name of an item the app adds. */
function validateNewName_(name) {
  if (name.length > MAX_NAME_LENGTH_) return 'name is longer than ' + MAX_NAME_LENGTH_ + ' characters';
  if (/[\r\n\u2028\u2029]/.test(name)) return 'name contains a line break';
  return null;
}

function isValidChangeId_(id) {
  return typeof id === 'string' && id.length > 0 && id.length <= MAX_CHANGE_ID_LENGTH_;
}

function cleanDeviceName_(device) {
  const name = typeof device === 'string' ? device.replace(/\s+/g, ' ').trim() : '';
  return (name.slice(0, MAX_DEVICE_LENGTH_).trim()) || DEFAULT_DEVICE_;
}

// ---------------------------------------------------------------------------------------------
// Items sheet
// ---------------------------------------------------------------------------------------------

/** The data rows of Items (columns A:E) exactly as Sheets returns them. */
function readItemRows_(sheet) {
  const lastRow = sheet.getLastRow();
  return lastRow > 1 ? sheet.getRange(2, 1, lastRow - 1, ITEMS_HEADER_.length).getValues() : [];
}

/**
 * Reads every data row of Items. Each record keeps its sheet row so that writes touch only the rows
 * that changed; blank and malformed rows are kept (untouched) to preserve the sheet layout.
 * Rows at the end with nothing in A:E but unticked checkboxes (a whole column of checkboxes keeps
 * getLastRow() at the bottom of the sheet) are free: new items are written into them, right below the
 * list, instead of after the last checkbox.
 */
function loadItemTable_(sheet) {
  const values = readItemRows_(sheet);
  let used = values.length;
  while (used > 0 && values[used - 1].every(isFreeCell_)) used--;
  const records = values.slice(0, used).map((cells, i) => {
    const item = parseItemRow_(cells);
    return { row: i + 2, key: item ? normalizeKey_(item.name) : null, item, dirty: false, deleted: false };
  });
  return { records, fingerprint: JSON.stringify(values) };
}

/** True when Items still holds exactly what loadItemTable_ read, so the stored row numbers are valid. */
function itemRowsUnchanged_(sheet, table) {
  return JSON.stringify(readItemRows_(sheet)) === table.fingerprint;
}

function tableHasWrites_(table) {
  return table.records.some((r) => r.dirty || (r.deleted && r.row > 0));
}

function isFreeCell_(value) {
  return value === '' || value === null || value === undefined || value === false;
}

/** First live record for the key (later duplicates are ignored, as for reads). */
function findRecord_(table, key) {
  for (let i = 0; i < table.records.length; i++) {
    const r = table.records[i];
    if (!r.deleted && r.item && r.key === key) return r;
  }
  return null;
}

/** The protocol's item list, in sheet row order, first row winning for duplicate keys. */
function tableItems_(table) {
  const seen = new Set();
  const items = [];
  table.records.forEach((r) => {
    if (r.deleted || !r.item || seen.has(r.key)) return;
    seen.add(r.key);
    items.push({
      name: r.item.name,
      type: r.item.type,
      value: r.item.value,
      updatedAt: r.item.updatedAt,
      updatedBy: r.item.updatedBy,
    });
  });
  return items;
}

/**
 * Where the in-memory table goes in Items, before any row is deleted: changed rows stay on their row,
 * new items go right below the last read row, removed rows are deleted afterwards (deleteItemRows_).
 * The result is the same as deleting first and then writing kept[i] to row i + 2.
 */
function planItemWrites_(table) {
  const doomedRows = [];
  const writes = [];
  let kept = 0;
  let nextRow = table.records.filter((r) => r.row > 0).length + 2;
  table.records.forEach((r) => {
    if (r.deleted) {
      if (r.row > 0) doomedRows.push(r.row);
      return;
    }
    kept += 1;
    const row = r.row > 0 ? r.row : nextRow++;
    if (r.dirty) writes.push({ row, item: r.item });
  });
  // Sheets refuses to delete every non-frozen row and to write past the last row.
  return { writes, doomedRows, rowsNeeded: doomedRows.length + Math.max(kept + 1, 2) };
}

/** Writes each block of adjacent changed/new rows with one setValues (see writeItemBlock_). */
function writeItemRows_(sheet, plan) {
  if (!plan.writes.length && !plan.doomedRows.length) return;
  ensureRowCapacity_(sheet, plan.rowsNeeded);
  if (!plan.writes.length) return;
  const first = plan.writes[0].row;
  const last = plan.writes[plan.writes.length - 1].row;
  // The rules on the written cells: put back if a block is refused, and they name what refused it.
  const rules = sheet.getRange(first, 1, last - first + 1, ITEMS_HEADER_.length).getDataValidations();
  const checkbox = SpreadsheetApp.newDataValidation().requireCheckbox().build();
  adjacentRuns_(plan.writes).forEach((run) => {
    const offset = run[0].row - first;
    writeItemBlock_(sheet, run[0].row, run.map((w) => w.item), rules.slice(offset, offset + run.length), checkbox);
  });
}

/**
 * Need validation is set before the values so a strict checkbox rule can never reject a quantity
 * (the script owns the Need rule, so it never names Need as the refusing column). When the sheet
 * refuses the values, the Need rules are put back, so nothing of the block stays changed.
 */
function writeItemBlock_(sheet, firstRow, items, rules, checkbox) {
  const values = items.map(itemCells_);
  const target = { sheet, sheetName: CONFIG.ITEMS_SHEET, header: ITEMS_HEADER_, firstRow, numRows: items.length, rules, values, ownColumns: [COL_.NEED] };
  const need = sheet.getRange(firstRow, COL_.NEED, items.length, 1);
  const range = sheet.getRange(firstRow, 1, items.length, ITEMS_HEADER_.length);
  let needChanged = false;
  try {
    need.setDataValidations(items.map((item) => [item.type === 'boolean' ? checkbox : null]));
    needChanged = true;
    range.setValues(guardTextCells_(range, values, ITEM_TEXT_COLUMNS_));
    // A rule here may refuse the values: make the refusal surface now, while the cells are known.
    if (rejectingRules_(target).length) SpreadsheetApp.flush();
  } catch (err) {
    if (needChanged) restoreValidations_(need, rules.map((line) => [line[COL_.NEED - 1]]));
    throw tagWrite_(err, target);
  }
  sheet.getRange(firstRow, COL_.UPDATED_AT, items.length, 1).setNumberFormat(DATE_FORMAT_);
}

function restoreValidations_(range, rules) {
  try {
    range.setDataValidations(rules);
  } catch (err) {
    console.warn('Grocery Sync: could not put back the Need validation of rows ' + range.getRow() + '-' +
        range.getLastRow() + ': ' + errorMessage_(err));
  }
}

/** Deletes removed rows bottom-up (row numbers above stay valid). */
function deleteItemRows_(sheet, plan) {
  if (!plan.doomedRows.length) return;
  try {
    deleteRowsBottomUp_(sheet, plan.doomedRows);
  } catch (err) {
    throw tagWrite_(err, { sheet, sheetName: CONFIG.ITEMS_SHEET, header: ITEMS_HEADER_, firstRow: 0, numRows: 0 });
  }
}

/** Splits row-ordered writes into blocks of adjacent rows. */
function adjacentRuns_(writes) {
  const runs = [];
  writes.forEach((w) => {
    const run = runs[runs.length - 1];
    if (run && run[run.length - 1].row + 1 === w.row) run.push(w);
    else runs.push([w]);
  });
  return runs;
}

function itemCells_(item) {
  return [item.name, item.value, item.type, new Date(item.updatedAt), item.updatedBy];
}

/** Parses one Items row, tolerating hand-entered data. Returns null for rows without an Item. */
function parseItemRow_(cells) {
  const name = cellToString_(cells[COL_.ITEM - 1]);
  if (!name) return null;
  const need = parseNeed_(cells[COL_.NEED - 1], cells[COL_.TYPE - 1]);
  return {
    name,
    type: need.type,
    value: need.value,
    updatedAt: toMillis_(cells[COL_.UPDATED_AT - 1]),
    updatedBy: cellToString_(cells[COL_.UPDATED_BY - 1]),
  };
}

function parseNeed_(need, typeCell) {
  const type = typeof typeCell === 'string' ? typeCell.trim().toLowerCase() : '';
  if (type === 'integer') return { type, value: toQuantity_(need) };
  if (type === 'boolean') return { type, value: toFlag_(need) };
  // Type blank or invalid: infer it from the Need cell.
  if (typeof need === 'boolean') return { type: 'boolean', value: need };
  if (typeof need === 'number') return { type: 'integer', value: clampQuantity_(need) };
  return { type: 'boolean', value: toFlag_(need) };
}

function toQuantity_(need) {
  if (typeof need === 'number') return clampQuantity_(need);
  const text = typeof need === 'string' ? need.trim() : '';
  return /^\d+$/.test(text) ? clampQuantity_(Number(text)) : 0;
}

function clampQuantity_(n) {
  if (isNaN(n)) return 0;
  return Math.min(MAX_QUANTITY_, Math.max(0, Math.round(n)));
}

function toFlag_(need) {
  if (typeof need === 'boolean') return need;
  if (typeof need === 'number') return need > 0;
  if (need === null || need === undefined) return false;
  const text = String(need).trim().toLowerCase();
  if (TRUE_WORDS_.indexOf(text) >= 0) return true;
  if (FALSE_WORDS_.indexOf(text) >= 0) return false;
  return true;
}

// ---------------------------------------------------------------------------------------------
// Tombstones (remember deletions so an older offline upsert cannot resurrect an item)
// ---------------------------------------------------------------------------------------------

function loadTombstones_(sheet, now) {
  const lastRow = sheet.getLastRow();
  const rows = lastRow > 1 ? sheet.getRange(2, 1, lastRow - 1, TOMBSTONE_HEADER_.length).getValues() : [];
  const cutoff = now - TOMBSTONE_TTL_MS_;
  const entries = new Map();
  let dirty = false;
  rows.forEach((cells) => {
    const key = normalizeKey_(cellToString_(cells[0]));
    const deletedAt = toMillis_(cells[1]);
    if (!key || deletedAt < cutoff) {
      dirty = true; // blank, invalid or expired: dropped on the next write
      return;
    }
    const existing = entries.get(key);
    if (existing) dirty = true;
    if (!existing || deletedAt > existing.deletedAt) entries.set(key, { deletedAt, by: cellToString_(cells[2]) });
  });
  return { entries, storedRows: rows.length, dirty, cutoff };
}

function putTombstone_(tombstones, key, deletedAt, by) {
  if (deletedAt < tombstones.cutoff) return; // would be pruned straight away
  const existing = tombstones.entries.get(key);
  if (existing && existing.deletedAt >= deletedAt) return;
  tombstones.entries.set(key, { deletedAt, by });
  tombstones.dirty = true;
}

function removeTombstone_(tombstones, key) {
  if (tombstones.entries.delete(key)) tombstones.dirty = true;
}

/**
 * Rewrites the (small, machine-managed) tombstone block when anything changed or expired. Flushes, so
 * a refusal Apps Script reports late still surfaces here, tagged with these cells.
 */
function saveTombstones_(sheet, tombstones) {
  if (!tombstones.dirty) return;
  const rows = [];
  tombstones.entries.forEach((entry, key) => {
    rows.push([key, new Date(entry.deletedAt), entry.by]);
  });
  try {
    if (rows.length) {
      ensureRowCapacity_(sheet, rows.length + 1);
      const range = sheet.getRange(2, 1, rows.length, TOMBSTONE_HEADER_.length);
      range.setValues(guardTextCells_(range, rows, TOMBSTONE_TEXT_COLUMNS_));
      sheet.getRange(2, 2, rows.length, 1).setNumberFormat(DATE_FORMAT_);
    }
    const surplus = tombstones.storedRows - rows.length;
    if (surplus > 0) sheet.getRange(rows.length + 2, 1, surplus, TOMBSTONE_HEADER_.length).clearContent();
    SpreadsheetApp.flush();
  } catch (err) {
    throw tagWrite_(err, { sheet, sheetName: CONFIG.TOMBSTONE_SHEET, header: TOMBSTONE_HEADER_, firstRow: 2, numRows: rows.length, values: rows });
  }
}

/**
 * For an upsert, the tombstone write only drops the item's old tombstone (or expired ones): a
 * leftover tombstone is older than the row just written, so it blocks nothing the row would not.
 */
function saveTombstonesQuietly_(sheet, tombstones) {
  try {
    saveTombstones_(sheet, tombstones);
  } catch (err) {
    console.warn('Grocery Sync: could not update the ' + CONFIG.TOMBSTONE_SHEET + ' sheet (the change itself was saved): ' +
        classifyWriteFailure_(err).detail);
  }
}

// ---------------------------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------------------------

function historyRow_(time, name, action, need, type, device, changeId) {
  return [new Date(time), name, action, need, type, device, changeId];
}

/** Appends rows to History; flushes, so a late refusal surfaces here, tagged with these cells. */
function appendHistory_(sheet, rows) {
  if (!rows.length) return;
  const firstRow = sheet.getLastRow() + 1;
  try {
    ensureRowCapacity_(sheet, firstRow + rows.length - 1);
    const range = sheet.getRange(firstRow, 1, rows.length, HISTORY_HEADER_.length);
    range.setValues(guardTextCells_(range, rows, HISTORY_TEXT_COLUMNS_));
    sheet.getRange(firstRow, 1, rows.length, 1).setNumberFormat(DATE_FORMAT_);
    SpreadsheetApp.flush();
  } catch (err) {
    throw tagWrite_(err, { sheet, sheetName: CONFIG.HISTORY_SHEET, header: HISTORY_HEADER_, firstRow, numRows: rows.length, values: rows });
  }
}

/**
 * History is a log, not part of the changes: when Sheets refuses or fails the append, the applied
 * changes stay applied and the rows are only reported in the execution log.
 */
function appendHistoryQuietly_(sheet, rows) {
  if (!sheet || !rows.length) return;
  try {
    appendHistory_(sheet, rows);
  } catch (err) {
    console.warn('Grocery Sync: could not add ' + rows.length + ' row(s) to the ' + CONFIG.HISTORY_SHEET +
        ' sheet (the changes themselves were saved): ' + classifyWriteFailure_(err).detail);
  }
}

// ---------------------------------------------------------------------------------------------
// Change-id dedupe (a retried request must not apply or log a change twice)
// ---------------------------------------------------------------------------------------------

function changeCacheKey_(id) {
  return 'grocery-sync:change:' + id;
}

function recallAppliedChangeIds_(changes) {
  const found = new Set();
  const ids = changes
      .filter((c) => isPlainObject_(c) && isValidChangeId_(c.id))
      .map((c) => c.id);
  if (!ids.length) return found;
  try {
    const hits = CacheService.getScriptCache().getAll(ids.map(changeCacheKey_));
    ids.forEach((id) => {
      if (Object.prototype.hasOwnProperty.call(hits, changeCacheKey_(id))) found.add(id);
    });
  } catch (err) {
    console.warn('Change-id cache unavailable, skipping dedupe: ' + err);
  }
  return found;
}

function rememberAppliedChangeIds_(ids) {
  if (!ids.length) return;
  const values = {};
  ids.forEach((id) => {
    values[changeCacheKey_(id)] = '1';
  });
  try {
    CacheService.getScriptCache().putAll(values, DEDUPE_TTL_SECONDS_);
  } catch (err) {
    console.warn('Could not remember applied change ids: ' + err);
  }
}

// ---------------------------------------------------------------------------------------------
// Spreadsheet plumbing
// ---------------------------------------------------------------------------------------------

function openSpreadsheet_() {
  const url = String(CONFIG.SPREADSHEET_URL || '').trim();
  const ss = url ? SpreadsheetApp.openByUrl(url) : SpreadsheetApp.getActiveSpreadsheet();
  if (!ss) {
    throw new Error('no spreadsheet: open the script from the sheet (Extensions > Apps Script) or set CONFIG.SPREADSHEET_URL');
  }
  return ss;
}

/** Creates missing sheets and header rows. Call while holding the script lock. */
function ensureSheets_(ss) {
  const items = ensureSheet_(ss, CONFIG.ITEMS_SHEET, ITEMS_HEADER_, { adoptBlankSheet: true });
  const history = CONFIG.KEEP_HISTORY ? ensureSheet_(ss, CONFIG.HISTORY_SHEET, HISTORY_HEADER_, {}) : null;
  const tombstones = ensureSheet_(ss, CONFIG.TOMBSTONE_SHEET, TOMBSTONE_HEADER_, { hidden: true });
  return { items, history, tombstones };
}

function ensureSheet_(ss, name, header, options) {
  let sheet = ss.getSheetByName(name);
  if (!sheet) {
    // A brand-new spreadsheet has one empty "Sheet1": reuse it instead of leaving it behind.
    const blank = options.adoptBlankSheet ? soleBlankSheet_(ss) : null;
    sheet = blank ? blank.setName(name) : ss.insertSheet(name);
    if (options.hidden) sheet.hideSheet();
  }
  ensureHeader_(sheet, header);
  return sheet;
}

function soleBlankSheet_(ss) {
  const sheets = ss.getSheets();
  if (sheets.length !== 1) return null;
  return sheets[0].getLastRow() === 0 && sheets[0].getLastColumn() === 0 ? sheets[0] : null;
}

function ensureHeader_(sheet, header) {
  const maxColumns = sheet.getMaxColumns();
  if (maxColumns < header.length) sheet.insertColumnsAfter(maxColumns, header.length - maxColumns);
  const range = sheet.getRange(1, 1, 1, header.length);
  const current = range.getValues()[0];
  if (current.every((v) => !isBlank_(v))) return;
  if (current.every((v) => isBlank_(v))) {
    range.setValues([header]);
  } else {
    header.forEach((title, i) => {
      if (isBlank_(current[i])) sheet.getRange(1, i + 1).setValue(title);
    });
  }
  range.setFontWeight('bold');
  sheet.setFrozenRows(1);
}

function ensureRowCapacity_(sheet, rowsNeeded) {
  const maxRows = sheet.getMaxRows();
  if (maxRows < rowsNeeded) sheet.insertRowsAfter(maxRows, rowsNeeded - maxRows);
}

function deleteRowsBottomUp_(sheet, rows) {
  const sorted = rows.slice().sort((a, b) => b - a);
  let i = 0;
  while (i < sorted.length) {
    let first = sorted[i];
    let count = 1;
    while (i + count < sorted.length && sorted[i + count] === first - 1) {
      first -= 1;
      count += 1;
    }
    sheet.deleteRows(first, count);
    i += count;
  }
}

/** Calls callback(startIndex, items) for every maximal run of consecutive items matching predicate. */
function forEachRun_(list, predicate, callback) {
  let start = -1;
  for (let i = 0; i <= list.length; i++) {
    const inRun = i < list.length && predicate(list[i]);
    if (inRun && start < 0) start = i;
    if (!inRun && start >= 0) {
      callback(start, list.slice(start, i));
      start = -1;
    }
  }
}

function withScriptLock_(fn) {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(LOCK_TIMEOUT_MS_)) throw apiError_('busy');
  try {
    return fn();
  } finally {
    try {
      SpreadsheetApp.flush(); // make the writes visible before the next execution gets the lock
    } finally {
      lock.releaseLock();
    }
  }
}

// ---------------------------------------------------------------------------------------------
// onEdit helpers (simple trigger: best effort, never throws)
// ---------------------------------------------------------------------------------------------

/** The script lock for onEdit; waited = another execution (usually an apply) held it first. */
function acquireEditLock_() {
  try {
    const lock = LockService.getScriptLock();
    if (lock.tryLock(0)) return { lock, waited: false };
    return { lock: lock.tryLock(EDIT_LOCK_TIMEOUT_MS_) ? lock : null, waited: true };
  } catch (err) {
    console.warn('onEdit runs without the script lock: ' + err);
    return { lock: null, waited: false };
  }
}

function releaseEditLock_(lock) {
  if (!lock) return;
  try {
    SpreadsheetApp.flush();
  } catch (err) {
    console.warn('onEdit flush failed: ' + err);
  } finally {
    try {
      lock.releaseLock();
    } catch (err) {
      console.warn('onEdit could not release the script lock: ' + err);
    }
  }
}

/** For a single-cell edit: true when the cell at the edited address no longer holds the typed value. */
function editMoved_(e, range) {
  if (range.getNumRows() !== 1 || range.getNumColumns() !== 1) return false;
  const typed = e.value;
  if (typed === undefined || typed === null || typeof typed === 'object') return false;
  return cellToString_(range.getValue()).toLowerCase() !== String(typed).trim().toLowerCase();
}

/**
 * A person cleared or renamed an Item cell: unless another row still holds the old item, remember it
 * as deleted now (like a delete from the app), so a change a phone queued earlier cannot bring it back.
 */
function rememberHandRemoval_(e, range, now) {
  if (range.getColumn() !== COL_.ITEM || range.getRow() < 2) return;
  if (range.getNumRows() !== 1 || range.getNumColumns() !== 1 || isBlank_(e.oldValue)) return;
  const oldKey = normalizeKey_(cellToString_(e.oldValue));
  const sheet = range.getSheet();
  if (!oldKey || findRecord_(loadItemTable_(sheet), oldKey)) return;
  const tombstoneSheet = ensureSheet_(e.source || sheet.getParent(), CONFIG.TOMBSTONE_SHEET, TOMBSTONE_HEADER_, { hidden: true });
  const tombstones = loadTombstones_(tombstoneSheet, now);
  putTombstone_(tombstones, oldKey, now, SHEET_EDITOR_);
  saveTombstones_(tombstoneSheet, tombstones);
}

// ---------------------------------------------------------------------------------------------
// Writes the sheet refuses (data validation set to "Reject the input", protected ranges, ...)
// ---------------------------------------------------------------------------------------------

/**
 * Records on a write error which cells were being written: { sheet, sheetName, header, firstRow,
 * numRows, rules (read before writing, optional), values (the rows written, as the script meant them,
 * optional), ownColumns (rules the script sets itself) }.
 */
function tagWrite_(err, target) {
  if (err && typeof err === 'object' && !err.sheetWrite) err.sheetWrite = target;
  return err;
}

/**
 * Tells a write the sheet itself refuses (it would fail the same way again) from Google-side trouble
 * that a retry fixes. Conservative: a message that is neither a known refusal nor known trouble (e.g.
 * one in the account's language) counts as a refusal only when a rule that rejects invalid input sits
 * on the written cells and would refuse a value written there (or cannot be checked, see ruleAccepts_).
 * Returns { refused, detail }: the sheet's message plus, for a refusal, where it applies when known.
 */
function classifyWriteFailure_(err) {
  const message = errorMessage_(err);
  const target = (err && err.sheetWrite) || null;
  const rules = target ? rejectingRules_(target) : [];
  let refused;
  if (err && err.apiError) refused = false;
  else if (rules.some((r) => r.helpText && r.helpText === message) || REFUSAL_MESSAGE_RE_.test(message)) refused = true;
  else if (TRANSIENT_MESSAGE_RE_.test(message)) refused = false;
  else refused = rules.some((r) => r.mayRefuse);
  return { refused, detail: refused ? refusalDetail_(message, target, rules) : clip_(message, MAX_REJECTION_DETAIL_) };
}

/**
 * The rules on the target cells that reject invalid input, except those the script sets itself, one
 * per cell: { column, name, helpText, mayRefuse = the value written there may break the rule }.
 */
function rejectingRules_(target) {
  let grid = target.rules;
  if (!grid) {
    if (!target.numRows) return [];
    try {
      grid = target.sheet.getRange(target.firstRow, 1, target.numRows, target.header.length).getDataValidations();
    } catch (err) {
      return [];
    }
  }
  const found = [];
  grid.forEach((line, i) => line.forEach((rule, j) => {
    if (!rule || rule.getAllowInvalid() || (target.ownColumns || []).indexOf(j + 1) >= 0) return;
    const row = target.values && target.values[i];
    found.push({
      column: j + 1,
      name: target.header[j],
      helpText: cleanText_(rule.getHelpText()),
      mayRefuse: !row || !ruleAccepts_(rule, row[j]),
    });
  }));
  return found;
}

/**
 * True when the rule surely accepts the value: blank, an exact (case-sensitive, as Sheets compares)
 * entry of a dropdown list, or a checkbox's value. Rules that cannot be checked here (ranges, custom
 * formulas, numbers, dates, ...) may refuse anything.
 */
function ruleAccepts_(rule, value) {
  if (value === '' || value === null || value === undefined) return true;
  try {
    const type = rule.getCriteriaType();
    const criteria = rule.getCriteriaValues() || []; // [values, showDropdown] or [checked, unchecked]
    if (type === SpreadsheetApp.DataValidationCriteria.VALUE_IN_LIST) {
      return (typeof value === 'string' || typeof value === 'number') &&
          Array.isArray(criteria[0]) && criteria[0].some((allowed) => String(allowed) === String(value));
    }
    if (type === SpreadsheetApp.DataValidationCriteria.CHECKBOX) {
      const custom = criteria.filter((v) => v !== undefined && v !== null && v !== '');
      return custom.length ? custom.some((allowed) => String(allowed) === String(value)) : typeof value === 'boolean';
    }
    return false;
  } catch (err) {
    return false;
  }
}

/** "<the sheet's message> (data validation on the Type column of the Items sheet)", kept short. */
function refusalDetail_(message, target, rules) {
  let where = '';
  const helpTextShown = rules.some((r) => r.helpText && r.helpText === message);
  if (target && !helpTextShown && PROTECTED_MESSAGE_RE_.test(message)) {
    where = ' (protected range on the ' + target.sheetName + ' sheet)';
  } else if (target) {
    const names = refusingColumns_(message, target, rules);
    if (names.length) {
      where = ' (data validation on the ' + joinWords_(names) + (names.length > 1 ? ' columns' : ' column') +
          ' of the ' + target.sheetName + ' sheet)';
    }
  }
  return clip_(message, Math.max(40, MAX_REJECTION_DETAIL_ - where.length)) + where;
}

/**
 * The rule whose help text is the message, else the cell the message names, else the rejecting rules
 * that may refuse what was written (else all of them).
 */
function refusingColumns_(message, target, rules) {
  let columns = rules.filter((r) => r.helpText && r.helpText === message);
  if (!columns.length) {
    const cell = /\bcell \$?([A-Z]{1,3})\$?\d+\b/.exec(message);
    const column = cell ? columnNumber_(cell[1]) : 0;
    const suspects = rules.filter((r) => r.mayRefuse);
    columns = column >= 1 && column <= target.header.length ? [{ column, name: target.header[column - 1] }]
        : suspects.length ? suspects : rules;
  }
  const names = [];
  columns.slice().sort((a, b) => a.column - b.column).forEach((c) => {
    if (names.indexOf(c.name) < 0) names.push(c.name);
  });
  return names;
}

/**
 * For setupSheets: a warning per sheet whose columns carry data validation set to "Reject the input"
 * (other than the Need checkboxes, which the script manages), since Sheets would refuse writes there.
 */
function strictValidationWarnings_(sheets) {
  const fix = ' Remove the rule: select the column, choose Data > Data validation and delete it' +
      ' (or set "If the data is invalid" to "Show a warning").';
  const isNeedCheckbox = (column, rule) =>
    column === COL_.NEED && rule.getCriteriaType() === SpreadsheetApp.DataValidationCriteria.CHECKBOX;
  const checks = [
    [sheets.items, CONFIG.ITEMS_SHEET, ITEMS_HEADER_, isNeedCheckbox,
      'The app writes these cells itself (Type is "boolean" or "integer"), so the sheet would refuse its changes.'],
    [sheets.tombstones, CONFIG.TOMBSTONE_SHEET, TOMBSTONE_HEADER_, () => false,
      'The script records deletions there, so the sheet would refuse deletions from the app.'],
    [sheets.history, CONFIG.HISTORY_SHEET, HISTORY_HEADER_, () => false,
      'History rows the sheet refuses are left out of the History.'],
  ];
  const warnings = [];
  checks.forEach(([sheet, name, header, isOwnRule, consequence]) => {
    if (!sheet) return;
    try {
      const columns = strictColumns_(sheet, header, isOwnRule);
      if (!columns.length) return;
      warnings.push('Warning: data validation on the ' + joinWords_(columns) + (columns.length > 1 ? ' columns' : ' column') +
          ' of the ' + name + ' sheet is set to "Reject the input". ' + consequence + fix);
    } catch (err) {
      warnings.push('Warning: could not check the data validation of the ' + name + ' sheet: ' + errorMessage_(err));
    }
  });
  return warnings;
}

/** Header names of the columns (rows 2 and below) holding a rule that rejects invalid input. */
function strictColumns_(sheet, header, isOwnRule) {
  const rows = sheet.getMaxRows() - 1;
  if (rows < 1) return [];
  const grid = sheet.getRange(2, 1, rows, header.length).getDataValidations();
  return header.filter((name, j) => grid.some((line) => {
    const rule = line[j];
    return Boolean(rule) && !rule.getAllowInvalid() && !isOwnRule(j + 1, rule);
  }));
}

// ---------------------------------------------------------------------------------------------
// Request / response helpers
// ---------------------------------------------------------------------------------------------

/** Runs a handler and always answers JSON: Apps Script cannot set HTTP status codes. */
function respond_(handler) {
  let payload;
  try {
    payload = handler();
  } catch (err) {
    payload = { ok: false, error: errorCode_(err) };
  }
  return ContentService.createTextOutput(JSON.stringify(payload)).setMimeType(ContentService.MimeType.JSON);
}

function errorCode_(err) {
  if (err && err.apiError) return err.message;
  console.error('Grocery Sync internal error: ' + ((err && err.stack) || err));
  const detail = String((err && err.message) || err).replace(/\s+/g, ' ').trim().slice(0, 200);
  return 'internal: ' + (detail || 'unexpected error');
}

function apiError_(message) {
  const err = new Error(message);
  err.apiError = true;
  return err;
}

function badRequest_(detail) {
  return apiError_('bad_request: ' + detail);
}

function parseJsonBody_(e) {
  const contents = e && e.postData ? e.postData.contents : undefined;
  if (typeof contents !== 'string' || contents.trim() === '') throw badRequest_('missing JSON body');
  let body;
  try {
    body = JSON.parse(contents);
  } catch (err) {
    throw badRequest_('malformed JSON');
  }
  if (!isPlainObject_(body)) throw badRequest_('body must be a JSON object');
  return body;
}

function isConfigured_() {
  const secret = String(CONFIG.SECRET_TOKEN || '').trim();
  return secret !== '' && secret !== UNSET_TOKEN_;
}

function requireConfigured_() {
  if (!isConfigured_()) throw apiError_('server_not_configured');
}

function authorize_(token) {
  requireConfigured_();
  const secret = String(CONFIG.SECRET_TOKEN).trim();
  if (typeof token !== 'string' || !constantTimeEquals_(token.trim(), secret)) throw apiError_('unauthorized');
}

function constantTimeEquals_(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function describeValue_(value) {
  if (value === undefined || value === null || value === '') return '(none)';
  return JSON.stringify(String(value).slice(0, 40));
}

// ---------------------------------------------------------------------------------------------
// Small utilities
// ---------------------------------------------------------------------------------------------

/** Item key: trim, collapse whitespace runs to one space, lowercase (the app uses the same rule). */
function normalizeKey_(name) {
  return String(name).trim().replace(/\s+/g, ' ').toLowerCase();
}

/** Lowercase hex MD5 of the canonical items JSON. */
function revisionOf_(items) {
  const digest = Utilities.computeDigest(
      Utilities.DigestAlgorithm.MD5, JSON.stringify(items), Utilities.Charset.UTF_8);
  let hex = '';
  for (let i = 0; i < digest.length; i++) hex += ((digest[i] & 0xff) + 0x100).toString(16).slice(1);
  return hex;
}

/**
 * Prepares rows for range.setValues: the cells in textColumns get user-supplied text as literal text.
 * Sheets parses written strings like typed input ("=..." becomes a formula, "Jan 5" a date, "5pm" a
 * time, "1/2" a date, "$5" or "50 €" a number, and a leading apostrophe disappears), which would change
 * names and with them item keys. One leading apostrophe makes Sheets store the rest verbatim, in any
 * locale. Cells formatted as Plain text never parse input and would keep the apostrophe, so they get
 * the text as it is.
 */
function guardTextCells_(range, rows, textColumns) {
  const formats = range.getNumberFormats();
  return rows.map((row, i) => row.map((value, j) => (textColumns.indexOf(j) >= 0 ? cellText_(value, formats[i][j]) : value)));
}

function cellText_(value, numberFormat) {
  const text = String(value);
  return text === '' || numberFormat === PLAIN_TEXT_FORMAT_ ? text : "'" + text;
}

function cellToString_(value) {
  if (value === null || value === undefined) return '';
  return String(value).trim();
}

/** Text on one line (whitespace runs collapsed), '' for null. */
function cleanText_(value) {
  return value === null || value === undefined ? '' : String(value).replace(/\s+/g, ' ').trim();
}

/** err.message on one line, without the "Exception: " prefix the Apps Script log shows. */
function errorMessage_(err) {
  const text = String((err && err.message) || err || '').replace(/^\s*Exception:\s*/i, '');
  return cleanText_(text) || 'unexpected error';
}

function clip_(text, max) {
  return text.length <= max ? text : text.slice(0, max - 3).trim() + '...';
}

/** ['A'] -> "A", ['A', 'B'] -> "A and B", ['A', 'B', 'C'] -> "A, B and C". */
function joinWords_(words) {
  return words.length < 2 ? words.join('') : words.slice(0, -1).join(', ') + ' and ' + words[words.length - 1];
}

/** "A" -> 1, "C" -> 3, "AA" -> 27. */
function columnNumber_(letters) {
  let n = 0;
  for (let i = 0; i < letters.length; i++) n = n * 26 + (letters.charCodeAt(i) - 64);
  return n;
}

function isDate_(value) {
  return Object.prototype.toString.call(value) === '[object Date]';
}

function toMillis_(value) {
  if (!isDate_(value)) return 0;
  const ms = value.getTime();
  return isNaN(ms) ? 0 : ms;
}

function isBlank_(value) {
  return value === '' || value === null || value === undefined;
}

function isPlainObject_(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
