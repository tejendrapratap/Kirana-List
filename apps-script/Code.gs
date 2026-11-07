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
 *      secret token, tap Save. Set up the second phone the same way.
 *
 * UPDATING FROM THE SCRIPT OF APP 1.1 (one with units, but without the "Added At" column)
 *   Replace the old code with this file, put your SECRET_TOKEN (and SPREADSHEET_URL, if you set one)
 *   back into CONFIG below, save, and deploy a new version (below). That is all: you do not need to run
 *   "setupSheets". The first change from a phone adds an "Added At" column right after Updated By on the
 *   Items sheet (running setupSheets adds it too); your own columns to the right of Updated By move one
 *   column to the right with all their cells. From then on the script writes Added At once, when it
 *   creates a row, and never changes it afterwards, so the app can tell "Added by" from "Updated by".
 *   Items that existed before keep Added At empty (not known), and the app shows "Updated by" for them.
 *   Until the new version is deployed, the old one keeps answering the phones and leaves Added At empty
 *   for the items it adds.
 *
 * UPDATING FROM AN OLDER VERSION OF THIS SCRIPT (one without units)
 *   Replace the old code with this file, but put your SECRET_TOKEN (and SPREADSHEET_URL, if you set
 *   one) back into CONFIG below. Save, then deploy a new version (below) straight away: until then the
 *   phones still talk to the old version, which writes its columns in the old order. Only then, if you
 *   like, run "setupSheets" once: it adds a "Unit" column right after Need on the Items and History
 *   sheets (and the "Added At" column described above on Items); every value, checkbox and rule of the
 *   other columns moves along with its column. (Without it, the first change from a phone adds the
 *   columns.) A Type dropdown of your own that allows
 *   "integer" gets "quantity" added to its list. A phone still running the older app keeps syncing: it
 *   sees every amount rounded up to a whole number (0.5 kg as 1) and leaves the units as they are.
 *   Rows the old version wrote after the column was added (deployed too late) are still read right,
 *   and their next change writes them in the new order.
 *
 * AFTER EVERY CHANGE TO THIS CODE (including a new SECRET_TOKEN)
 *   Deploy > Manage deployments > Edit (pencil icon) > Version: New version > Deploy.
 *   The URL stays the same. Saving alone does not update the running web app.
 *
 * EDITING THE SHEET BY HAND
 *   "Items" has one row per item: Item | Need | Unit | Type | Updated At | Updated By | Added At.
 *   Type "boolean": Need is a checkbox and Unit stays empty. Type "quantity": Need is the amount to buy
 *   (0 = not needed; decimals are fine, e.g. 1.5 or 0.25, rounded to 3 places) and Unit what it is
 *   counted in: pick pcs, kg, g, L, ml, dozen or pack from the dropdown or type your own (bunch, box,
 *   ...; at most 16 characters). Usual spellings become the standard unit ("Kgs" reads as kg, "litres"
 *   as L). "2 kg" or "1,5" typed into Need works too; a unit typed there counts while Unit is empty.
 *   A comma before one or two digits is a decimal mark ("1,5" = 1.5); before three it groups thousands
 *   ("1,000 ml" = 1000 ml, "1,00,000" = 100000).
 *   Editing Item, Need, Unit or Type stamps Updated At / Updated By.
 *   Added At is when the row was created: the script fills it in when it adds a row for an item from a
 *   phone, and when you type a name into an empty Item cell or into a row whose Updated At and Added At
 *   are still empty. While you fill in the other cells of a row you typed (within 10 minutes, and before
 *   anyone else changes it), it moves along with Updated At, so the item still counts as just added.
 *   After that it never changes. Leave it as it is: the app compares it with Updated At to say "Added by"
 *   (not changed since it was added) or "Updated by". Rows from before this column existed keep it empty
 *   (not known).
 *   To remove an item, delete it in the app, or select its Item cell and press Delete (rows without an
 *   Item are ignored; tidy them up whenever you like). Clearing or renaming an Item cell is remembered
 *   as a deletion, so a phone that was offline cannot bring the old item back. Deleting a whole row is
 *   not remembered that way, so prefer clearing the Item cell.
 *   Inserting, deleting or sorting rows is safest while nobody is changing the list in the app.
 *   The script finds the columns by their titles in row 1: do not rename them, and keep Item, Need,
 *   Unit, Type, Updated At, Updated By and Added At next to each other (put columns of your own to the
 *   right). A column titled Unit elsewhere (one of your own from before units) is moved next to Need
 *   when the Unit column is added; one titled Added At is moved next to Updated By the same way.
 *   Keep the spreadsheet's time zone (File > Settings) as it is once items exist (stored times would
 *   shift).
 *   Do not add data validation set to "Reject the input" (Data > Data validation) to these sheets:
 *   the script writes Type as "boolean" or "quantity" and the other cells as the app sends them, and
 *   Sheets refuses any value such a rule does not allow. The app then shows the refused change with
 *   the sheet's message (for example "Choose Boolean or Integer.") and the column, the rest of the
 *   list keeps syncing; remove or relax the rule and tap Retry in the app. The Need checkboxes and the
 *   Unit dropdown belong to the script (the dropdown only warns about other units, it never refuses).
 */

const CONFIG = {
  SECRET_TOKEN: 'CHANGE_ME',   // must be changed; 'CHANGE_ME' or '' -> every request answers server_not_configured
  SPREADSHEET_URL: '',         // leave empty when the script is opened from the sheet (Extensions > Apps Script); else the sheet URL (openByUrl). onEdit only works for the bound case.
  ITEMS_SHEET: 'Items',
  HISTORY_SHEET: 'History',
  TOMBSTONE_SHEET: '_Tombstones',
  KEEP_HISTORY: true,
};

// ---------------------------------------------------------------------------------------------
// Protocol (JSON; Apps Script cannot set HTTP status codes, so every failure is {"ok":false,"error"})
//   GET  ?action=ping&token=T[&v=3]                 {"ok":true,"app":"grocery-sync","version":3,"spreadsheet"}
//   GET  ?action=list&token=T[&since=REV][&v=3]     {"ok":true,["version":3,]"revision","serverTime","spreadsheet","items"}
//                                                   or {"ok":true,["version":3,]"revision","serverTime","unchanged":true}
//   POST {"token","action":"apply","device"[,"protocol":3],"changes":[...]}
//                                                   {"ok":true,["version":3,]"results","revision","serverTime","spreadsheet","items"}
// Version 3 clients (GET v=3, POST "protocol":3) get "version":3 and items with units:
//   {"name","type":"quantity","value":1.5,"unit":"kg","updatedAt","updatedBy"[,"addedAt"]}  (value
//   0..999999, at most 3 decimals; unit may be "") or {"name","type":"boolean","value":true,"updatedAt",
//   "updatedBy"[,"addedAt"]}. "addedAt" (ms) is the Added At of the row, sent only when it holds a date
//   (after 1970); rows from before the Added At column have none. An app ignoring it keeps working.
// Older clients (version 2, without units) get no "version", no "addedAt" and quantities as
// {"type":"integer","value":<the amount rounded up to a whole number>} without a unit; the revision is
// that of what they get.
// Changes: {"id","op":"upsert","name","type":"quantity","value":1.5,"unit":"kg","time"[,"addedAt"]}:
// "unit" absent or null keeps the row's unit (blank for a new row), "" clears it; "type":"boolean" with
// true/false; the legacy "type":"integer" (whole number) is a quantity that keeps the row's unit.
// An upsert that creates the row (its name is not on the sheet, e.g. a re-add after a delete) writes Added
// At = its time, the same as Updated At (its "time", at most the server's clock); with "addedAt" (ms, the
// app sends it for a rename and for Undo of a delete) and 0 < addedAt <= that time, Added At = addedAt
// instead. Otherwise "addedAt" is ignored (never an error), and an upsert of a row that exists never
// changes its Added At. {"id","op":"delete","name","time"}. Per change: "applied", "stale", "duplicate",
// "deferred" or "error" (with "error").
// ---------------------------------------------------------------------------------------------

const APP_ID_ = 'grocery-sync';
const PROTOCOL_VERSION_ = 3;
const LEGACY_PROTOCOL_VERSION_ = 2;   // apps from before units: no v=3 / "protocol":3
const UNSET_TOKEN_ = 'CHANGE_ME';

const MAX_CHANGES_ = 100;
const MAX_CHANGE_ID_LENGTH_ = 64;
const MAX_NAME_LENGTH_ = 100;         // for items the app adds; names typed into the sheet may be longer
const MAX_CELL_TEXT_LENGTH_ = 50000;  // what a Sheets cell can hold
const MAX_QUANTITY_ = 999999;
const AMOUNT_SCALE_ = 1000;           // amounts have at most 3 decimals
const AMOUNT_DECIMALS_ = 3;
const MAX_UNIT_LENGTH_ = 16;
const MAX_DEVICE_LENGTH_ = 40;
const DEFAULT_DEVICE_ = 'Phone';
const SHEET_EDITOR_ = 'Sheet';

const TYPE_BOOLEAN_ = 'boolean';
const TYPE_QUANTITY_ = 'quantity';
const TYPE_INTEGER_ = 'integer';      // protocol 2 and sheets from before units
const QUANTITY_TYPE_WORDS_ = ['quantity', 'integer', 'number', 'qty'];

// The standard units (the Unit dropdown) and their usual spellings, compared in lower case. Any other
// unit is the person's own and kept as typed. The app normalises units with the same table.
const STANDARD_UNITS_ = ['pcs', 'kg', 'g', 'L', 'ml', 'dozen', 'pack'];
const UNIT_ALIASES_ = {
  pcs: ['pc', 'pcs', 'piece', 'pieces', 'nos', 'no'],
  kg: ['kg', 'kgs', 'kilo', 'kilos', 'kilogram', 'kilograms'],
  g: ['g', 'gm', 'gms', 'gram', 'grams', 'gr'],
  L: ['l', 'ltr', 'ltrs', 'litre', 'litres', 'liter', 'liters'],
  ml: ['ml', 'millilitre', 'millilitres', 'milliliter', 'milliliters'],
  dozen: ['dozen', 'doz', 'dz'],
  pack: ['pack', 'packs', 'pkt', 'packet', 'packets'],
};
const UNIT_HELP_TEXT_ = 'Pick a unit or type your own (bunch, box, ...).';
// "2", "1.5", "1,5", "1,000", "2 kg", "1.5L", "x 3": an amount (its digits are read by amountFromText_) and a unit word.
const AMOUNT_TEXT_RE_ = /^(?:[x\u00d7]\s*)?([.,]?\d[\d.,]*)\s*(\p{L}{1,16})?$/iu;
// Quantity Need cells show amounts as Automatic does: 2, 1.5, 0.25. (A pattern such as 0.### would show
// whole amounts with a bare decimal point, "2." and "0.", since Sheets always shows a pattern's point.)
const NEED_NUMBER_FORMAT_ = 'General';

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
// A row a person types into the sheet is still being added while they fill in its other cells (Need, Unit,
// Type), one edit each: for this long after it was typed, while nobody else has changed it, each of those
// edits moves Added At along with Updated At, so the item reads "Added by Sheet" rather than "Updated by".
const HAND_ROW_TYPING_MS_ = 10 * 60 * 1000;
// The app reads "Added by" when Added At and Updated At are less than this apart (else "Updated by").
const SAME_TIME_MS_ = 1000;

// Columns are found by these titles in row 1 (see locateColumns_), so sheets set up by an older version
// of this script (the *_V2_ layouts, without Unit; ITEMS_HEADER_V3_, the script of app 1.1, without Added
// At) are read correctly until the Unit and Added At columns are added.
const UNIT_TITLE_ = 'Unit';
const ADDED_AT_TITLE_ = 'Added At';
const ITEMS_HEADER_V3_ = ['Item', 'Need', 'Unit', 'Type', 'Updated At', 'Updated By'];
const ITEMS_HEADER_ = ITEMS_HEADER_V3_.concat([ADDED_AT_TITLE_]);
const ITEMS_HEADER_V2_ = ['Item', 'Need', 'Type', 'Updated At', 'Updated By'];
const ITEM_FIELDS_ = ['item', 'need', 'unit', 'type', 'updatedAt', 'updatedBy', 'addedAt']; // ITEMS_HEADER_, in order
const HISTORY_HEADER_ = ['Time', 'Item', 'Action', 'Need', 'Unit', 'Type', 'By', 'Change ID'];
const HISTORY_HEADER_V2_ = ['Time', 'Item', 'Action', 'Need', 'Type', 'By', 'Change ID'];
const HISTORY_TEXT_TITLES_ = ['Item', 'Unit', 'By', 'Change ID'];
const HISTORY_ACTION_ = 2;            // index in a History row (HISTORY_HEADER_ order)
const HISTORY_CHANGE_ID_ = 7;
const TOMBSTONE_HEADER_ = ['Key', 'Deleted At', 'By'];

const TRUE_WORDS_ = ['true', 'yes', 'y', 'x', '1'];
const FALSE_WORDS_ = ['false', 'no', 'n', '0', ''];

// Columns (0-based) holding user-supplied text, written through guardTextCells_.
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

/** GET ?action=ping|list&token=T[&since=REV][&v=3] */
function doGet(e) {
  return respond_(() => {
    const params = (e && e.parameter) || {};
    authorize_(params.token);
    switch (params.action) {
      case 'ping':
        return handlePing_();
      case 'list':
        return handleList_(params.since, clientVersion_(params.v));
      default:
        throw badRequest_('unknown action ' + describeValue_(params.action));
    }
  });
}

/** POST {"token","action":"apply","device"[,"protocol":3],"changes":[...]} */
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
 * Simple trigger: stamps Updated At / Updated By when a person edits Item, Need, Unit or Type by hand
 * (and Added At, with the same time, on a row being added by hand: see rowsBeingAdded_), and remembers an
 * Item cell that was cleared or renamed as a deletion of the old name.
 */
function onEdit(e) {
  let lock = null;
  try {
    const range = e && e.range;
    if (!range) return;
    const sheet = range.getSheet();
    if (sheet.getName() !== CONFIG.ITEMS_SHEET) return;
    const firstRow = Math.max(range.getRow(), 2);
    const lastRow = range.getRow() + range.getNumRows() - 1;
    if (lastRow < firstRow) return;
    let header = readHeader_(sheet);
    if (!editsItemInput_(range, itemLayout_(header))) return;

    // Hand edits never wait for the app's changes, but this trigger does, so an apply cannot move rows
    // between the reads and the writes below.
    const edit = acquireEditLock_();
    lock = edit.lock;
    if (edit.waited) {
      // An apply that ran first may have deleted rows above the edited one (or added the Unit column):
      // the address then no longer shows what the person typed, and stamping it would mark a different
      // item as changed.
      if (editMoved_(e, range)) return;
      header = readHeader_(sheet);
      if (!editsItemInput_(range, itemLayout_(header))) return;
    }
    const layout = itemLayoutOf_(sheet, header);

    const count = lastRow - firstRow + 1;
    const names = sheet.getRange(firstRow, layout.item, count, 1).getValues();
    const now = new Date();
    const adding = rowsBeingAdded_(sheet, layout, firstRow, count, now.getTime(), typedIntoEmptyItemCell_(e, range, layout));
    const rows = names.map((cells, i) => ({ named: cellToString_(cells[0]) !== '', adding: adding[i] }));
    // Rows whose Item is now blank were cleared, not edited: leave them alone.
    forEachRun_(rows, (r) => r.named, (start, run) => {
      stampRows_(sheet, layout, firstRow + start, run.length, now);
    });
    // A row being added by hand gets Added At = the same now; any other row keeps its Added At.
    forEachRun_(rows, (r) => r.named && r.adding, (start, run) => {
      stampAddedAt_(sheet, layout, firstRow + start, run.length, now);
    });
    rememberHandRemoval_(e, range, now.getTime(), layout);
  } catch (err) {
    console.error('onEdit failed: ' + ((err && err.stack) || err));
  } finally {
    releaseEditLock_(lock);
  }
}

/**
 * Run once from the editor: creates the sheets (or adds the Unit and Added At columns) and triggers the
 * authorization prompt.
 */
function setupSheets() {
  const ss = openSpreadsheet_();
  const setup = withScriptLock_(() => {
    const sheets = ensureSheets_(ss);
    const warnings = strictValidationWarnings_(sheets);
    const problem = itemWriteBlock_(itemLayoutOf_(sheets.items)).problem;
    if (problem) warnings.unshift('Warning: the phones cannot save changes until you fix the ' + CONFIG.ITEMS_SHEET + ' sheet: ' + problem);
    return { migrated: sheets.migrated, warnings };
  });
  setup.migrated.forEach((m) => Logger.log(m.title === UNIT_TITLE_
      ? 'Added the Unit column (right after Need) to the ' + m.sheet + ' sheet.'
      : 'Added the ' + ADDED_AT_TITLE_ + ' column (right after Updated By) to the ' + m.sheet + ' sheet.'));
  if (setup.migrated.length) {
    // The deployed web app runs the code of its version, not this saved code: an old one writes the old
    // column order (before units) or leaves Added At empty (app 1.1).
    console.warn('Deploy this code now if you have not yet (Deploy > Manage deployments > Edit > Version: New version > Deploy).' +
        (setup.migrated.some((m) => m.title === UNIT_TITLE_)
          ? ' Until then the version from before units still answers the phones and writes their changes in the old column order.'
          : ' Until then the older version still answers the phones and leaves ' + ADDED_AT_TITLE_ + ' empty for the items they add.'));
  }
  Logger.log('Grocery Sync sheets are ready in the spreadsheet "' + ss.getName() + '".');
  setup.warnings.forEach((warning) => console.warn(warning));
  Logger.log(isConfigured_()
      ? 'Next: Deploy > New deployment > Web app (Execute as: Me, Who has access: Anyone) and copy the /exec URL into the app.' +
        ' Updating an existing web app: Deploy > Manage deployments > Edit > Version: New version > Deploy.'
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

/** Lists the items as the client understands them (see clientItems_); never takes the lock once Items exists. */
function handleList_(since, version) {
  const ss = openSpreadsheet_();
  let sheet = ss.getSheetByName(CONFIG.ITEMS_SHEET);
  if (!sheet) sheet = withScriptLock_(() => ensureSheets_(ss)).items;
  const items = clientItems_(tableItems_(loadItemTable_(sheet, true)), version);
  const revision = revisionOf_(items);
  const serverTime = Date.now();
  if (since && since === revision) return versioned_(version, { ok: true, revision, serverTime, unchanged: true });
  return versioned_(version, { ok: true, revision, serverTime, spreadsheet: ss.getName(), items });
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
 * "internal: ..." (or "busy"), so the app retries it later. An Items sheet from before units or from
 * app 1.1 gets its Unit and Added At columns first (ensureSheets_).
 */
function handleApply_(body) {
  const changes = body.changes;
  if (!Array.isArray(changes)) throw badRequest_('changes must be an array');
  if (changes.length === 0) throw badRequest_('changes must not be empty');
  if (changes.length > MAX_CHANGES_) throw badRequest_('too many changes (max ' + MAX_CHANGES_ + ')');
  const device = cleanDeviceName_(body.device);
  const version = clientVersion_(body.protocol);
  const ss = openSpreadsheet_();

  return withScriptLock_(() => {
    const deadline = Date.now() + APPLY_TIME_BUDGET_MS_;
    const sheets = ensureSheets_(ss);
    const alreadyApplied = recallAppliedChangeIds_(changes);
    let outcome = applyBatch_(sheets, device, changes, alreadyApplied);
    if (outcome.refused) outcome = applyOneByOne_(sheets, device, changes, alreadyApplied, deadline, outcome.addedIds);
    finishApply_(sheets, outcome);
    // One at a time, the last in-memory state may hold a refused change: answer with what the sheet holds.
    const items = clientItems_(tableItems_(outcome.table || loadItemTable_(sheets.items)), version);
    return versioned_(version, {
      ok: true, results: outcome.results, revision: revisionOf_(items), serverTime: outcome.now, spreadsheet: ss.getName(), items,
    });
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
      const addedIds = new Set(state.history
          .filter((row) => row[HISTORY_ACTION_] === 'added')
          .map((row) => row[HISTORY_CHANGE_ID_]));
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
      if (row[HISTORY_ACTION_] === 'updated' && progress.addedIds.has(change.id)) row[HISTORY_ACTION_] = 'added';
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
 * Rows are written by position. A person may have inserted, deleted, sorted or edited rows (or
 * columns) since they were read (hand edits never wait for the script lock): then the caller starts
 * again from a fresh read.
 */
function rowsStillValid_(sheets, state) {
  return !tableHasWrites_(state.table) || itemRowsUnchanged_(sheets.items, state.table);
}

/**
 * Persists the in-memory state in an order that keeps a refused write harmless:
 *  1. Items values (setValues is all or nothing): a refusal leaves no row deleted and no tombstone
 *     written; at most earlier blocks of changed existing rows of the same batch are written, which a
 *     replay writes again unchanged, and the Need and Unit rules of the refused block are put back.
 *  2. Tombstones: a delete whose tombstone is refused keeps its row (no resurrection risk).
 *  3. Row deletions, which data validation cannot refuse.
 * Apps Script may report a write's failure only at the next flush (or read), so each part flushes
 * before the next one starts: a failure surfaces in the part that caused it (tagged with its cells,
 * see tagWrite_), never inside another part or after rows are gone.
 * tombstonesBestEffort: a failed tombstone write is only logged (an upsert merely drops an old one).
 */
function saveState_(sheets, state, tombstonesBestEffort) {
  const plan = planItemWrites_(state.table);
  writeItemRows_(sheets.items, plan, state.table.block);
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

  const value = upsertValue_(change, record ? record.item : null);
  // Added At is written once, when the row is created; a row that exists keeps its own (see writeItemRows_).
  const addedAt = record ? record.item.addedAt : createdAt_(change.addedAt, time);
  const item = { name, type: value.type, value: value.value, unit: value.unit, updatedAt: time, updatedBy: state.device, addedAt };
  let action;
  if (record) {
    record.item = item;
    record.dirty = true;
    action = 'updated';
  } else {
    state.table.records.push({ row: 0, key, item, dirty: true, deleted: false, addedAtCell: new Date(addedAt) });
    action = 'added';
  }
  if (tombstone) removeTombstone_(state.tombstones, key);
  state.history.push(historyRow_(time, name, action, item, state.device, change.id));
  return { status: 'applied' };
}

/**
 * What a (valid) upsert stores: { type, value, unit }. A quantity's "unit" absent or null keeps the
 * unit the item has (blank for a new item or one that was yes/no), "" clears it. The protocol 2 type
 * "integer" is a quantity that keeps the unit. Yes/no items have no unit.
 */
function upsertValue_(change, current) {
  if (change.type === TYPE_BOOLEAN_) return { type: TYPE_BOOLEAN_, value: change.value, unit: '' };
  const keep = current && current.type === TYPE_QUANTITY_ ? current.unit : '';
  if (change.type === TYPE_INTEGER_) return { type: TYPE_QUANTITY_, value: change.value, unit: keep };
  const unit = change.unit === undefined || change.unit === null ? keep : normalizeUnit_(change.unit);
  return { type: TYPE_QUANTITY_, value: roundAmount_(change.value), unit };
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
      ? historyRow_(time, record.item.name, 'deleted', record.item, state.device, change.id)
      : historyRow_(time, name, 'deleted', null, state.device, change.id));
  return { status: 'applied' };
}

/**
 * The Added At of a row an upsert creates: the change's "addedAt" hint (ms) when 0 < addedAt <= time,
 * else time (the same as its Updated At). time is the change's time as written to Updated At (a phone
 * clock ahead of the server's counts as the server's, see effectiveTime_), so Added At is never later than
 * Updated At. The app sends the hint for a rename (delete + upsert of the new name) and for Undo of a
 * delete, so the item keeps the time it was first added; a hint that is not such a number is ignored,
 * never an error.
 */
function createdAt_(hint, time) {
  if (typeof hint !== 'number' || !isFinite(hint)) return time;
  const ms = Math.floor(hint);
  return ms > 0 && ms <= time ? ms : time;
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
  if (change.type === TYPE_BOOLEAN_) {
    return typeof change.value === 'boolean' ? null : 'value must be true or false for type boolean';
  }
  if (change.type === TYPE_QUANTITY_) {
    if (!isAmount_(change.value)) return 'value must be a number from 0 to ' + MAX_QUANTITY_ + ' for type quantity';
    return unitError_(change.unit);
  }
  if (change.type === TYPE_INTEGER_) {
    const v = change.value;
    return Number.isSafeInteger(v) && v >= 0 && v <= MAX_QUANTITY_
        ? null : 'value must be a whole number from 0 to ' + MAX_QUANTITY_ + ' for type integer';
  }
  return 'invalid type';
}

/** A quantity's value: a number from 0 to MAX_QUANTITY_ (more than 3 decimals are rounded, see roundAmount_). */
function isAmount_(value) {
  return typeof value === 'number' && isFinite(value) && value >= 0 && value <= MAX_QUANTITY_;
}

/** A quantity change's "unit": absent, null or text of at most 16 characters once cleaned. */
function unitError_(unit) {
  if (unit === undefined || unit === null) return null;
  if (typeof unit !== 'string') return 'unit must be text';
  return cleanText_(unit).length > MAX_UNIT_LENGTH_ ? 'unit is longer than ' + MAX_UNIT_LENGTH_ + ' characters' : null;
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
// Protocol versions: what each client gets
// ---------------------------------------------------------------------------------------------

/** The protocol a client speaks: 3 when it says so (GET v=3, POST "protocol":3), else 2 (an app from before units). */
function clientVersion_(value) {
  let n = 0;
  if (typeof value === 'number') n = value;
  else if (typeof value === 'string' && /^\s*\d+\s*$/.test(value)) n = Number(value);
  return n >= PROTOCOL_VERSION_ ? PROTOCOL_VERSION_ : LEGACY_PROTOCOL_VERSION_;
}

/** Adds "version":3 (right after "ok") to a list or apply answer for a version 3 client. */
function versioned_(version, payload) {
  if (version < PROTOCOL_VERSION_) return payload;
  return Object.assign({ ok: payload.ok, version: PROTOCOL_VERSION_ }, payload);
}

/**
 * The items as a client of that protocol version understands them. Version 3: quantities with their
 * unit, and "addedAt" (last) when the row's Added At holds a date after 1970. Version 2 knows only whole
 * numbers ("integer"), no units and no addedAt: the amount rounded up, so 0.5 kg still counts as needed.
 * (The revision is computed from this list, so it follows what the client sees.)
 */
function clientItems_(items, version) {
  return items.map((item) => {
    if (version < PROTOCOL_VERSION_) {
      return item.type === TYPE_BOOLEAN_
        ? { name: item.name, type: TYPE_BOOLEAN_, value: item.value, updatedAt: item.updatedAt, updatedBy: item.updatedBy }
        : { name: item.name, type: TYPE_INTEGER_, value: Math.ceil(item.value), updatedAt: item.updatedAt, updatedBy: item.updatedBy };
    }
    const sent = item.type === TYPE_BOOLEAN_
      ? { name: item.name, type: TYPE_BOOLEAN_, value: item.value, updatedAt: item.updatedAt, updatedBy: item.updatedBy }
      : { name: item.name, type: TYPE_QUANTITY_, value: item.value, unit: item.unit, updatedAt: item.updatedAt, updatedBy: item.updatedBy };
    if (item.addedAt > 0) sent.addedAt = item.addedAt;
    return sent;
  });
}

// ---------------------------------------------------------------------------------------------
// Amounts and units
// ---------------------------------------------------------------------------------------------

/**
 * An amount as stored and sent: 0..MAX_QUANTITY_, rounded half up to 3 decimals on its decimal digits
 * (1.0005 becomes 1.001, as a person rounds it, although that double is a hair below 1.0005), without
 * binary artefacts (0.1 + 0.2 becomes 0.3). Anything that is not a number above 0 is 0.
 */
function roundAmount_(n) {
  if (typeof n !== 'number' || !(n > 0)) return 0;
  if (n >= MAX_QUANTITY_) return MAX_QUANTITY_;
  const text = String(n); // the shortest decimal that reads back as n
  if (/e/i.test(text)) return 0; // only numbers below 1e-6 are written with an exponent here
  const point = text.indexOf('.');
  if (point < 0 || text.length - point - 1 <= AMOUNT_DECIMALS_) return n;
  let fraction = text.slice(point + 1, point + 1 + AMOUNT_DECIMALS_);
  while (fraction.length < AMOUNT_DECIMALS_) fraction += '0';
  const scaled = Number(text.slice(0, point) + fraction) + (text.charAt(point + 1 + AMOUNT_DECIMALS_) >= '5' ? 1 : 0);
  return scaled / AMOUNT_SCALE_; // exact: the nearest double to a decimal with at most 3 places
}

/**
 * A unit as stored and sent: whitespace runs (line breaks included) collapsed, trimmed, at most 16
 * characters; the usual spellings of the standard units (any case, see UNIT_ALIASES_) become pcs, kg,
 * g, L, ml, dozen or pack; any other unit is the person's own and kept as typed.
 */
function normalizeUnit_(text) {
  let clean = cleanText_(text);
  if (clean.length > MAX_UNIT_LENGTH_) {
    clean = clean.slice(0, MAX_UNIT_LENGTH_);
    // Never split a character made of two UTF-16 code units (an emoji): the app cuts the same way.
    const last = clean.charCodeAt(clean.length - 1);
    if (last >= 0xd800 && last <= 0xdbff) clean = clean.slice(0, -1);
    clean = clean.trim();
  }
  const lower = clean.toLowerCase();
  for (let i = 0; i < STANDARD_UNITS_.length; i++) {
    if (UNIT_ALIASES_[STANDARD_UNITS_[i]].indexOf(lower) >= 0) return STANDARD_UNITS_[i];
  }
  return clean;
}

/** The unit in a Unit cell: text (or a number) as normalizeUnit_ cleans it; blank for a checkbox or date. */
function unitFromCell_(cell) {
  if (typeof cell === 'string') return normalizeUnit_(cell);
  if (typeof cell === 'number' && isFinite(cell)) return normalizeUnit_(String(cell));
  return '';
}

/**
 * Need typed as text, conservatively: "2", "1.5", "1,5" (comma as the decimal mark), "1,000 ml"
 * (comma grouping thousands), "2 kg", "1.5L", "x 3" or "3x". Returns { amount, unit (normalised, '' if
 * none), marked = it says it is a quantity (a unit word or an "x") } or null for anything else ("lots",
 * "1-2", "2 big bags", "1,5,0").
 */
function parseAmountText_(value) {
  if (typeof value !== 'string') return null;
  const text = cleanText_(value);
  const match = AMOUNT_TEXT_RE_.exec(text);
  if (!match) return null;
  const amount = amountFromText_(match[1]);
  if (!isFinite(amount)) return null;
  const word = match[2] || '';
  const times = /^[x\u00d7]/i.test(text) || word.toLowerCase() === 'x';
  return { amount: roundAmount_(amount), unit: word.toLowerCase() === 'x' ? '' : normalizeUnit_(word), marked: times || word !== '' };
}

/**
 * The number in amount text: "2", "1.5", ".5"; a comma before one or two digits is the decimal mark
 * ("1,5", "0,25", ",5"); commas before groups of three digits separate thousands ("1,000", "12,500.5",
 * and the Indian "1,00,000"), as Sheets shows numbers in such locales. NaN for anything else: "1,5,0",
 * "0,500" or "1,2345" could mean either, so they are not guessed (a comma before three digits is never
 * read as a decimal mark: "1,000 ml" is not 1 ml).
 */
function amountFromText_(digits) {
  if (/^(?:\d+(?:\.\d+)?|\.\d+)$/.test(digits)) return Number(digits);
  if (/^\d*,\d{1,2}$/.test(digits)) return Number(digits.replace(',', '.'));
  if (/^[1-9]\d{0,2}(?:,\d{3})+(?:\.\d+)?$/.test(digits) || /^[1-9]\d?(?:,\d{2})*,\d{3}(?:\.\d+)?$/.test(digits)) {
    return Number(digits.replace(/,/g, ''));
  }
  return NaN;
}

function unitDropdown_() {
  return SpreadsheetApp.newDataValidation()
      .requireValueInList(STANDARD_UNITS_, true)
      .setAllowInvalid(true) // "Show a warning": a unit of the person's own is never refused
      .setHelpText(UNIT_HELP_TEXT_)
      .build();
}

// ---------------------------------------------------------------------------------------------
// Items sheet
// ---------------------------------------------------------------------------------------------

/**
 * Where the Items columns are (see sheetColumns_): { item, need, unit, type, updatedAt, updatedBy,
 * addedAt } (1-based, 0 = none; unit is 0 in a sheet from before units, addedAt in one from app 1.1) and
 * width = the last of them. Added At is found by its title only, never by its position alone, since a
 * column of the person's own may sit right after Updated By: see addedAtColumn_ for the one exception,
 * which looksLikeAddedAt(column) decides (header only when it is not given).
 */
function itemLayout_(headerCells, looksLikeAddedAt) {
  const columns = sheetColumns_(headerCells, ITEMS_HEADER_, ITEMS_HEADER_V2_);
  const at = ITEM_FIELDS_.indexOf('addedAt');
  columns[at] = addedAtColumn_(headerCells, columns.slice(0, at), looksLikeAddedAt);
  const layout = { width: 0 };
  ITEM_FIELDS_.forEach((field, i) => {
    layout[field] = columns[i];
    layout.width = Math.max(layout.width, columns[i]);
  });
  return layout;
}

/** itemLayout_ of the Items sheet, reading its cells when that is needed to find Added At (header: row 1, if already read). */
function itemLayoutOf_(sheet, header) {
  return itemLayout_(header || readHeader_(sheet), (column) => columnLooksLikeAddedAt_(sheet, column));
}

/**
 * The Added At column (1-based, 0 = none): the column titled Added At (in any case), wherever it is.
 * Without that title, the column right after the other six (Item ... Updated By, side by side) when
 * looksLikeAddedAt says its cells are the script's Added At (its title cleared or renamed by hand, see
 * looksLikeAddedAt_). Anything else there, a blank column included, is not Added At: the next change
 * adds the column (see addAddedAtColumn_).
 */
function addedAtColumn_(headerCells, sixColumns, looksLikeAddedAt) {
  const keys = headerCells.map(headerKey_);
  const titled = keys.indexOf(headerKey_(ADDED_AT_TITLE_)) + 1;
  if (titled) return titled;
  const after = columnAfterBlock_(sixColumns);
  if (!after || after > keys.length || !looksLikeAddedAt) return 0;
  if (ITEMS_HEADER_.map(headerKey_).indexOf(keys[after - 1]) >= 0) return 0;
  return looksLikeAddedAt(after) ? after : 0;
}

/** The column right after `columns` (1-based) when all of them are present and side by side, else 0. */
function columnAfterBlock_(columns) {
  if (!columns.length || columns.some((c) => !c)) return 0;
  const first = Math.min.apply(null, columns);
  const last = Math.max.apply(null, columns);
  return last - first + 1 === columns.length ? last + 1 : 0;
}

/**
 * True when the data cells of a column without the Added At title (values, rows 2 and below) are the
 * script's Added At whose title was cleared or renamed by hand: at least one date, nothing but dates and
 * blank cells, and every date in the format the script gives Added At (readFormats() = their number
 * formats, read only then). A column of the person's own holds text, numbers, or dates typed by hand,
 * which Sheets shows in the spreadsheet's own date format.
 */
function looksLikeAddedAt_(values, readFormats) {
  let dates = 0;
  for (let i = 0; i < values.length; i++) {
    if (isBlank_(values[i])) continue;
    if (!isDate_(values[i])) return false;
    dates += 1;
  }
  if (!dates) return false;
  let formats;
  try {
    formats = readFormats();
  } catch (err) {
    return false;
  }
  return values.every((v, i) => !isDate_(v) || headerKey_(formats[i]) === DATE_FORMAT_);
}

/** looksLikeAddedAt_ for a column of the sheet. */
function columnLooksLikeAddedAt_(sheet, column) {
  const rows = sheet.getLastRow() - 1;
  if (rows < 1 || column > sheet.getMaxColumns()) return false;
  const range = sheet.getRange(2, column, rows, 1);
  return looksLikeAddedAt_(range.getValues().map((line) => line[0]), () => range.getNumberFormats().map((line) => line[0]));
}

/**
 * The cells the script writes in an Items row: the seven columns side by side, in any order. Returns
 * { first (column), count, header (titles by position), item, need, unit, type, updatedAt, updatedBy,
 * addedAt (0-based positions), textColumns } or { problem } when the sheet's columns do not allow it.
 */
function itemWriteBlock_(layout) {
  const columns = ITEM_FIELDS_.map((field) => layout[field]);
  const missing = ITEMS_HEADER_.filter((title, i) => !columns[i]);
  const present = columns.filter((c) => c > 0);
  const first = Math.min.apply(null, present);
  let gap = 0;
  if (present.length && Math.max.apply(null, present) - first + 1 !== present.length) {
    gap = first;
    while (present.indexOf(gap) >= 0) gap++;
  }
  // Only Added At missing while a column sits between the others: the change after that is fixed adds
  // Added At by itself (addAddedAtColumn_), so the column in between is what to fix.
  if (missing.length && !(gap && missing.length === 1 && missing[0] === ADDED_AT_TITLE_)) {
    return {
      problem: 'the Items sheet has no ' + joinWords_(missing) + (missing.length > 1 ? ' columns' : ' column') +
          ': row 1 needs the titles ' + joinWords_(ITEMS_HEADER_) + '.',
    };
  }
  if (gap) {
    return {
      problem: 'keep the columns ' + joinWords_(ITEMS_HEADER_) + ' of the Items sheet next to each other: column ' +
          columnLetter_(gap) + ' is between them (move it to the right of them).',
    };
  }
  const count = columns.length;
  const block = { problem: null, first, count, header: [] };
  ITEM_FIELDS_.forEach((field, i) => {
    block[field] = columns[i] - first;
    block.header[columns[i] - first] = ITEMS_HEADER_[i];
  });
  // Added At: text a person typed there is written back as it was (only strings are guarded).
  block.textColumns = [block.item, block.unit, block.updatedBy, block.addedAt];
  return block;
}

/** The data rows of Items (columns A up to the last Items column) exactly as Sheets returns them. */
function readItemRows_(sheet, width) {
  const lastRow = sheet.getLastRow();
  return lastRow > 1 && width > 0 ? sheet.getRange(2, 1, lastRow - 1, width).getValues() : [];
}

/**
 * Reads every data row of Items, finding the columns by their titles. Each record keeps its sheet row so
 * that writes touch only the rows that changed; blank and malformed rows are kept (untouched) to
 * preserve the sheet layout. Rows at the end with nothing in the read columns but unticked checkboxes
 * (a whole column of checkboxes keeps getLastRow() at the bottom of the sheet) are free: new items are
 * written into them, right below the list, instead of after the last checkbox.
 * oneRead: row 1 and the data rows in a single read, for a list, which takes no lock: an apply adding
 * the Unit or Added At column between two reads would make them disagree (old titles, cells already moved).
 * Each record also keeps its Added At cell as read (addedAtCell), which a write of the row puts back.
 */
function loadItemTable_(sheet, oneRead) {
  let layout;
  let rows = null;
  if (oneRead) {
    const all = sheet.getRange(1, 1, Math.max(sheet.getLastRow(), 1), sheet.getMaxColumns()).getValues();
    rows = all.slice(1);
    layout = itemLayout_(all[0], (column) => looksLikeAddedAt_(rows.map((cells) => cells[column - 1]),
        () => sheet.getRange(2, column, rows.length, 1).getNumberFormats().map((line) => line[0])));
  } else {
    layout = itemLayoutOf_(sheet);
  }
  const values = !rows ? readItemRows_(sheet, layout.width) : layout.width > 0 ? rows.map((cells) => cells.slice(0, layout.width)) : [];
  let used = values.length;
  while (used > 0 && values[used - 1].every(isFreeCell_)) used--;
  const records = values.slice(0, used).map((cells, i) => {
    const item = parseItemRow_(cells, layout);
    const addedAtCell = layout.addedAt ? cells[layout.addedAt - 1] : '';
    return { row: i + 2, key: item ? normalizeKey_(item.name) : null, item, dirty: false, deleted: false, addedAtCell };
  });
  return { records, layout, block: itemWriteBlock_(layout), fingerprint: JSON.stringify(values) };
}

/**
 * True when Items still holds exactly what loadItemTable_ read, so the stored row numbers are valid. A
 * column inserted, deleted or moved within the read columns changes the data too.
 */
function itemRowsUnchanged_(sheet, table) {
  return JSON.stringify(readItemRows_(sheet, table.layout.width)) === table.fingerprint;
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

/**
 * The items { name, type ("boolean" | "quantity"), value, unit, updatedAt, updatedBy, addedAt (0 = not
 * known) }, in sheet row order, first row winning for duplicate keys. clientItems_ turns them into what a
 * client gets.
 */
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
      unit: r.item.unit,
      updatedAt: r.item.updatedAt,
      updatedBy: r.item.updatedBy,
      addedAt: r.item.addedAt,
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
    if (r.dirty) writes.push({ row, item: r.item, addedAtCell: r.addedAtCell, created: !(r.row > 0) });
  });
  // Sheets refuses to delete every non-frozen row and to write past the last row.
  return { writes, doomedRows, rowsNeeded: doomedRows.length + Math.max(kept + 1, 2) };
}

/**
 * Writes each block of adjacent changed/new rows with one setValues (see writeItemBlock_). The Items
 * columns must sit side by side (itemWriteBlock_); if not, nothing is written and the request fails
 * with "internal: <what to change>", which the app shows and retries.
 */
function writeItemRows_(sheet, plan, block) {
  if (!plan.writes.length && !plan.doomedRows.length) return;
  if (plan.writes.length && block.problem) throw apiError_('internal: ' + block.problem);
  ensureRowCapacity_(sheet, plan.rowsNeeded);
  if (!plan.writes.length) return;
  const first = plan.writes[0].row;
  const last = plan.writes[plan.writes.length - 1].row;
  // The rules on the written cells: put back if a block is refused, and they name what refused it.
  const rules = sheet.getRange(first, block.first, last - first + 1, block.count).getDataValidations();
  const own = { checkbox: SpreadsheetApp.newDataValidation().requireCheckbox().build(), dropdown: unitDropdown_() };
  adjacentRuns_(plan.writes).forEach((run) => {
    const offset = run[0].row - first;
    writeItemBlock_(sheet, block, run[0].row, run, rules.slice(offset, offset + run.length), own);
  });
}

/**
 * Writes a run of adjacent rows (writes from planItemWrites_). The Need and Unit rules are set before
 * the values, so a strict rule there can never reject what the app writes (the script owns those rules,
 * so it never names Need or Unit as the refusing column): yes/no rows get the Need checkbox and no Unit
 * rule, quantity rows no Need rule and the Unit dropdown (which only warns). When the sheet refuses the
 * values, those rules are put back, so nothing of the block stays changed. Added At gets its date format
 * on the rows created here only; the other rows get their Added At cell back as it was.
 */
function writeItemBlock_(sheet, block, firstRow, writes, rules, own) {
  const items = writes.map((w) => w.item);
  const values = writes.map((w) => itemCells_(w, block));
  const target = {
    sheet, sheetName: CONFIG.ITEMS_SHEET, header: block.header, firstRow, firstColumn: block.first, numRows: items.length,
    rules, values, ownColumns: [block.need + 1, block.unit + 1],
  };
  const need = sheet.getRange(firstRow, block.first + block.need, items.length, 1);
  const unit = sheet.getRange(firstRow, block.first + block.unit, items.length, 1);
  const range = sheet.getRange(firstRow, block.first, items.length, block.count);
  let needChanged = false;
  let unitChanged = false;
  try {
    need.setDataValidations(items.map((item) => [item.type === TYPE_BOOLEAN_ ? own.checkbox : null]));
    needChanged = true;
    unit.setDataValidations(items.map((item) => [item.type === TYPE_QUANTITY_ ? own.dropdown : null]));
    unitChanged = true;
    range.setValues(guardTextCells_(range, values, block.textColumns));
    // A rule here may refuse the values: make the refusal surface now, while the cells are known.
    if (rejectingRules_(target).length) SpreadsheetApp.flush();
  } catch (err) {
    if (needChanged) restoreValidations_(need, rules.map((line) => [line[block.need]]));
    if (unitChanged) restoreValidations_(unit, rules.map((line) => [line[block.unit]]));
    throw tagWrite_(err, target);
  }
  sheet.getRange(firstRow, block.first + block.updatedAt, items.length, 1).setNumberFormat(DATE_FORMAT_);
  forEachRun_(writes, (w) => w.created, (start, run) => {
    sheet.getRange(firstRow + start, block.first + block.addedAt, run.length, 1).setNumberFormat(DATE_FORMAT_);
  });
  try {
    // Only how the amounts look (a Need column formatted "0" by hand would show 1.5 as 2): best effort.
    forEachRun_(items, (item) => item.type === TYPE_QUANTITY_, (start, run) => {
      sheet.getRange(firstRow + start, block.first + block.need, run.length, 1).setNumberFormat(NEED_NUMBER_FORMAT_);
    });
  } catch (err) {
    console.warn('Grocery Sync: could not set the number format of the Need cells (the values were saved): ' + errorMessage_(err));
  }
}

function restoreValidations_(range, rules) {
  try {
    range.setDataValidations(rules);
  } catch (err) {
    console.warn('Grocery Sync: could not put back the data validation of ' + range.getA1Notation() + ': ' + errorMessage_(err));
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

/**
 * An Items row as written (a write from planItemWrites_): a yes/no item's Unit is empty, Type is
 * "boolean" or "quantity". Added At is the new row's date, or the cell exactly as it was read for a row
 * that exists (blank, a date, or whatever a person typed there), so a change never alters it.
 */
function itemCells_(write, block) {
  const item = write.item;
  const cells = [];
  for (let i = 0; i < block.count; i++) cells.push('');
  cells[block.item] = item.name;
  cells[block.need] = item.value;
  cells[block.unit] = item.type === TYPE_QUANTITY_ ? item.unit : '';
  cells[block.type] = item.type;
  cells[block.updatedAt] = new Date(item.updatedAt);
  cells[block.updatedBy] = item.updatedBy;
  cells[block.addedAt] = write.addedAtCell === undefined || write.addedAtCell === null ? '' : write.addedAtCell;
  return cells;
}

/** Parses one Items row, tolerating hand-entered data. Returns null for rows without an Item. */
function parseItemRow_(cells, layout) {
  const cell = (column) => (column ? cells[column - 1] : '');
  const name = cellToString_(cell(layout.item));
  if (!name) return null;
  // Added At: a date, else 0 (not known: blank, text, a row from before the column existed).
  const addedAt = toMillis_(cell(layout.addedAt));
  if (writtenByOldScript_(cells, layout)) {
    // Columns A-E as the script from before units wrote them: Item | Need | Type | Updated At | Updated By.
    const old = parseNeed_(cells[1], cells[2], '');
    return { name, type: old.type, value: old.value, unit: '', updatedAt: toMillis_(cells[3]), updatedBy: cellToString_(cells[4]), addedAt };
  }
  const need = parseNeed_(cell(layout.need), cell(layout.type), cell(layout.unit));
  return {
    name,
    type: need.type,
    value: need.value,
    unit: need.unit,
    updatedAt: toMillis_(cell(layout.updatedAt)),
    updatedBy: cellToString_(cell(layout.updatedBy)),
    addedAt,
  };
}

/**
 * A row the web app from before units wrote after the Unit column was added: that code stays deployed
 * until a new version is deployed, and it writes Item | Need | Type | Updated At | Updated By into
 * columns A-E whatever the titles say. Unit then holds its "integer" or "boolean", Type a date and
 * Updated At the phone's name, which no row of the new layout does. Read as that script meant it, the
 * item keeps its time (so an older change cannot win over it); its next change writes the new layout.
 */
function writtenByOldScript_(cells, layout) {
  if (layout.item !== 1 || layout.need !== 2 || layout.unit !== 3 || layout.type !== 4 || layout.updatedAt !== 5) return false;
  const oldType = typeof cells[2] === 'string' ? cells[2].trim().toLowerCase() : '';
  return (oldType === TYPE_INTEGER_ || oldType === TYPE_BOOLEAN_) && isDate_(cells[3]) && !isDate_(cells[4]);
}

/**
 * Need, Type and Unit cells as { type, value, unit }. Type is read in any case; "integer", "number",
 * "qty" and "quantity" mean a quantity. Blank or unknown: a number or text such as "2 kg" or "x 3" is
 * a quantity, anything else yes/no.
 */
function parseNeed_(need, typeCell, unitCell) {
  const type = sheetType_(typeCell);
  if (type === TYPE_BOOLEAN_) return { type: TYPE_BOOLEAN_, value: toFlag_(need), unit: '' };
  if (type === TYPE_QUANTITY_) return quantityNeed_(need, unitCell);
  // Type blank or invalid: infer it from the Need cell.
  if (typeof need === 'boolean') return { type: TYPE_BOOLEAN_, value: need, unit: '' };
  if (typeof need === 'number') return quantityNeed_(need, unitCell);
  const typed = parseAmountText_(need);
  if (typed && typed.marked) return quantityNeed_(need, unitCell);
  return { type: TYPE_BOOLEAN_, value: toFlag_(need), unit: '' };
}

function sheetType_(typeCell) {
  const word = typeof typeCell === 'string' ? typeCell.trim().toLowerCase() : '';
  if (word === TYPE_BOOLEAN_) return TYPE_BOOLEAN_;
  return QUANTITY_TYPE_WORDS_.indexOf(word) >= 0 ? TYPE_QUANTITY_ : '';
}

/**
 * A quantity row: the amount rounded half up to 3 decimals (text it cannot read, a checkbox or a date
 * is 0), the Unit cell's unit, else a unit typed after the amount in Need ("2 kg").
 */
function quantityNeed_(need, unitCell) {
  let value = 0;
  let typedUnit = '';
  if (typeof need === 'number') {
    value = roundAmount_(need);
  } else {
    const typed = parseAmountText_(need);
    if (typed) {
      value = typed.amount;
      typedUnit = typed.unit;
    }
  }
  return { type: TYPE_QUANTITY_, value, unit: unitFromCell_(unitCell) || typedUnit };
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

/** A History row in HISTORY_HEADER_ order; item null = a delete of an item the sheet did not hold. */
function historyRow_(time, name, action, item, device, changeId) {
  return [new Date(time), name, action, item ? item.value : '', item ? item.unit : '', item ? item.type : '', device, changeId];
}

/**
 * Where History rows go, found by the titles in row 1 (a History sheet from before units, whose Unit
 * column could not be added, gets its rows without the unit); the titles' usual positions when some
 * are missing. Cells between the titled columns are written blank: appended rows are empty anyway.
 */
function historyBlock_(sheet) {
  let columns = sheetColumns_(readHeader_(sheet), HISTORY_HEADER_, HISTORY_HEADER_V2_);
  const legacy = !columns[HISTORY_HEADER_.indexOf(UNIT_TITLE_)];
  const titles = legacy ? HISTORY_HEADER_V2_ : HISTORY_HEADER_;
  if (titles.some((title) => !columns[HISTORY_HEADER_.indexOf(title)])) {
    columns = HISTORY_HEADER_.map((title) => titles.indexOf(title) + 1);
  }
  const used = columns.filter((c) => c > 0);
  const first = Math.min.apply(null, used);
  const count = Math.max.apply(null, used) - first + 1;
  const blank = () => {
    const cells = [];
    for (let i = 0; i < count; i++) cells.push('');
    return cells;
  };
  const header = blank();
  columns.forEach((c, i) => {
    if (c) header[c - first] = HISTORY_HEADER_[i];
  });
  return {
    first,
    count,
    header,
    timeColumn: columns[0],
    textColumns: HISTORY_TEXT_TITLES_.map((title) => columns[HISTORY_HEADER_.indexOf(title)]).filter((c) => c > 0).map((c) => c - first),
    place: (row) => {
      const cells = blank();
      columns.forEach((c, i) => {
        if (c) cells[c - first] = row[i];
      });
      return cells;
    },
  };
}

/** Appends rows to History; flushes, so a late refusal surfaces here, tagged with these cells. */
function appendHistory_(sheet, rows) {
  if (!rows.length) return;
  const block = historyBlock_(sheet);
  const values = rows.map(block.place);
  const firstRow = sheet.getLastRow() + 1;
  try {
    ensureRowCapacity_(sheet, firstRow + rows.length - 1);
    const range = sheet.getRange(firstRow, block.first, rows.length, block.count);
    range.setValues(guardTextCells_(range, values, block.textColumns));
    sheet.getRange(firstRow, block.timeColumn, rows.length, 1).setNumberFormat(DATE_FORMAT_);
    SpreadsheetApp.flush();
  } catch (err) {
    throw tagWrite_(err, {
      sheet, sheetName: CONFIG.HISTORY_SHEET, header: block.header, firstRow, firstColumn: block.first, numRows: rows.length, values,
    });
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

/**
 * Creates missing sheets and header rows, adds the Unit column to Items and History sheets from before
 * units and the Added At column to Items sheets from before it (migrated = { sheet, title } for each
 * column added). Call while holding the script lock.
 */
function ensureSheets_(ss) {
  const migrated = [];
  const items = ensureSheet_(ss, CONFIG.ITEMS_SHEET, ITEMS_HEADER_, { adoptBlankSheet: true, migrate: migrateItemsSheet_ }, migrated);
  const history = CONFIG.KEEP_HISTORY
      ? ensureSheet_(ss, CONFIG.HISTORY_SHEET, HISTORY_HEADER_, { migrate: migrateHistorySheetQuietly_ }, migrated)
      : null;
  const tombstones = ensureSheet_(ss, CONFIG.TOMBSTONE_SHEET, TOMBSTONE_HEADER_, { hidden: true }, migrated);
  return { items, history, tombstones, migrated };
}

function ensureSheet_(ss, name, header, options, migrated) {
  let sheet = ss.getSheetByName(name);
  if (!sheet) {
    // A brand-new spreadsheet has one empty "Sheet1": reuse it instead of leaving it behind.
    const blank = options.adoptBlankSheet ? soleBlankSheet_(ss) : null;
    sheet = blank ? blank.setName(name) : ss.insertSheet(name);
    if (options.hidden) sheet.hideSheet();
  } else if (options.migrate) {
    // migrate returns the titles of the columns it added
    options.migrate(sheet).forEach((title) => {
      if (migrated) migrated.push({ sheet: name, title });
    });
  }
  ensureHeader_(sheet, header);
  return sheet;
}

function soleBlankSheet_(ss) {
  const sheets = ss.getSheets();
  if (sheets.length !== 1) return null;
  return sheets[0].getLastRow() === 0 && sheets[0].getLastColumn() === 0 ? sheets[0] : null;
}

/**
 * Writes the header of a new sheet, and the titles missing from an existing one into blank header
 * cells at their usual positions (a title found elsewhere in row 1 is left where it is).
 */
function ensureHeader_(sheet, header) {
  const maxColumns = sheet.getMaxColumns();
  if (maxColumns < header.length) sheet.insertColumnsAfter(maxColumns, header.length - maxColumns);
  const range = sheet.getRange(1, 1, 1, header.length);
  const current = range.getValues()[0];
  if (current.every((v) => !isBlank_(v))) return;
  if (current.every((v) => isBlank_(v))) {
    range.setValues([header]);
  } else {
    const present = readHeader_(sheet).map(headerKey_);
    header.forEach((title, i) => {
      if (isBlank_(current[i]) && present.indexOf(headerKey_(title)) < 0) sheet.getRange(1, i + 1).setValue(title);
    });
  }
  range.setFontWeight('bold');
  sheet.setFrozenRows(1);
}

/** Row 1 of a sheet, every column. */
function readHeader_(sheet) {
  return sheet.getRange(1, 1, 1, sheet.getMaxColumns()).getValues()[0];
}

/** Header text compared in lower case, whitespace runs collapsed ("updated  at" = "Updated At"). */
function headerKey_(value) {
  return cleanText_(value).toLowerCase();
}

/**
 * The columns (1-based, 0 = none) of `titles` in a header row: where the title is (in any case), else
 * at its position in `titles` when that header cell holds no other title of `known` (a renamed title,
 * e.g. "Product" in A, still finds the items).
 */
function locateColumns_(headerCells, titles, known) {
  const keys = headerCells.map(headerKey_);
  const knownKeys = known.map(headerKey_);
  const columns = titles.map(() => 0);
  const taken = new Set();
  titles.forEach((title, t) => {
    const i = keys.indexOf(headerKey_(title));
    if (i < 0) return;
    columns[t] = i + 1;
    taken.add(i);
  });
  titles.forEach((title, t) => {
    if (columns[t] || t >= keys.length || taken.has(t) || knownKeys.indexOf(keys[t]) >= 0) return;
    columns[t] = t + 1;
    taken.add(t);
  });
  return columns;
}

/**
 * The columns of `titles` (the current layout) in a sheet whose row 1 is headerCells: a sheet without a
 * Unit column (see hasUnitColumn_) is one from before units and is searched with `legacyTitles` (its
 * Unit column is then 0). Returns one column per title of `titles` (see locateColumns_).
 */
function sheetColumns_(headerCells, titles, legacyTitles) {
  const searched = hasUnitColumn_(headerCells, titles, legacyTitles) ? titles : legacyTitles;
  const found = locateColumns_(headerCells, searched, titles);
  return titles.map((title) => {
    const i = searched.indexOf(title);
    return i >= 0 ? found[i] : 0;
  });
}

/**
 * True when the sheet has its Unit column: row 1 has the Unit title, or the column right after Need has
 * a blank title or one of the person's own and Type comes right after it (a Unit title cleared or
 * renamed by hand, or a column being added right now). It is then found by its position, like any
 * renamed title (see locateColumns_). In a sheet from before units, Type comes right after Need.
 */
function hasUnitColumn_(headerCells, titles, legacyTitles) {
  const keys = headerCells.map(headerKey_);
  if (keys.indexOf(headerKey_(UNIT_TITLE_)) >= 0) return true;
  const need = locateColumns_(headerCells, legacyTitles, titles)[legacyTitles.indexOf('Need')];
  if (!need || need + 1 >= keys.length) return false;
  return titles.map(headerKey_).indexOf(keys[need]) < 0 && keys[need + 1] === headerKey_('Type');
}

/**
 * Brings an Items sheet from an older script up to date; returns the titles of the columns it added.
 *  1. From before units (Item | Need | Type | Updated At | Updated By): adds the Unit column right after
 *     Need. A new column, so every value, checkbox, rule and format of the other columns moves along
 *     with its column. The Unit column gets the units dropdown on quantity rows. A Type dropdown of the
 *     person's own that allows "integer" gets "quantity" too (see allowQuantityInTypeRules_).
 *  2. From before Added At (app 1.1: Item | Need | Unit | Type | Updated At | Updated By), or just
 *     after step 1: adds the Added At column right after Updated By (see addAddedAtColumn_). The rows
 *     already there keep it blank (not known).
 * Nothing happens when the columns are there already or there is no header yet (ensureHeader_ writes
 * it). Call while holding the script lock.
 */
function migrateItemsSheet_(sheet) {
  const added = [];
  const unit = addUnitColumn_(sheet, ITEMS_HEADER_V2_, ITEMS_HEADER_);
  if (unit.column) {
    const unitColumn = unit.column;
    const rows = sheet.getMaxRows() - 1;
    if (rows > 0) {
      const records = loadItemTable_(sheet).records;
      const dropdown = unitDropdown_();
      const rules = [];
      for (let i = 0; i < rows; i++) {
        const item = records[i] && records[i].item;
        rules.push([item && item.type === TYPE_QUANTITY_ ? dropdown : null]);
      }
      sheet.getRange(2, unitColumn, rows, 1).setDataValidations(rules);
    }
    if (!unit.moved) console.log('Grocery Sync: added the Unit column (column ' + columnLetter_(unitColumn) + ') to the ' + sheet.getName() + ' sheet.');
    allowQuantityInTypeRules_(sheet);
    added.push(UNIT_TITLE_);
  }
  if (addAddedAtColumn_(sheet).column) added.push(ADDED_AT_TITLE_);
  return added;
}

/**
 * Gives an Items sheet without an Added At column (see addedAtColumn_) one right after the other six
 * columns (Item ... Updated By, side by side in any order; right after Updated By as the script sets them
 * up), the way addUnitColumn_ adds Unit. Returns { column: the Added At column, or 0 when nothing was
 * done, moved }:
 *  - row 1 blank (ensureHeader_ writes it), a title of the six missing, or the six apart: nothing (the
 *    phones get a message saying what to fix; the column is added by the first change after the fix);
 *  - a column titled Added At right before or after the six: nothing to do. Titled Added At elsewhere
 *    (one of the person's own, or moved by hand): moved next to them, with its cells, rules and formats
 *    (moved = true);
 *  - right after the six the script's Added At whose title was cleared (see looksLikeAddedAt_): titled
 *    Added At again; whose title was renamed: left as the person named it;
 *  - right after the six an empty untitled column (e.g. an earlier attempt stopped after inserting it):
 *    titled Added At;
 *  - else a new column titled Added At is inserted right after the six, so the person's own columns
 *    there move one column to the right with all their cells. It takes over the rules of the column it
 *    was inserted after, so its rules are cleared.
 * The column gets the date format of Updated At on every data row. Call while holding the script lock.
 */
function addAddedAtColumn_(sheet) {
  const nothing = { column: 0, moved: false };
  const header = readHeader_(sheet);
  if (header.every(isBlank_)) return nothing;
  const six = sheetColumns_(header, ITEMS_HEADER_, ITEMS_HEADER_V2_).slice(0, ITEMS_HEADER_V3_.length);
  const after = columnAfterBlock_(six);
  if (!after) return nothing;
  const last = after - 1;
  const keys = header.map(headerKey_);
  const titled = keys.indexOf(headerKey_(ADDED_AT_TITLE_)) + 1;
  if (titled) {
    if (titled === after || titled === Math.min.apply(null, six) - 1) return nothing;
    sheet.moveColumns(sheet.getRange(1, titled), after);
    const moved = titled > last ? after : last;
    console.log('Grocery Sync: moved the ' + ADDED_AT_TITLE_ + ' column of the ' + sheet.getName() + ' sheet from column ' +
        columnLetter_(titled) + ' to column ' + columnLetter_(moved) + ', right after ' + ITEMS_HEADER_V3_[six.indexOf(last)] + '.');
    return { column: moved, moved: true };
  }
  const exists = after <= sheet.getMaxColumns();
  const title = exists ? keys[after - 1] : '';
  if (exists && columnLooksLikeAddedAt_(sheet, after)) {
    if (title !== '') return nothing; // renamed by hand: still the Added At column (see addedAtColumn_)
  } else if (!(exists && title === '' && columnIsEmpty_(sheet, after))) {
    sheet.insertColumnAfter(last);
  }
  const cell = sheet.getRange(1, after);
  cell.setValue(ADDED_AT_TITLE_);
  cell.setFontWeight('bold');
  const rows = sheet.getMaxRows() - 1;
  if (rows > 0) {
    const data = sheet.getRange(2, after, rows, 1);
    data.clearDataValidations();
    data.setNumberFormat(DATE_FORMAT_);
  }
  console.log('Grocery Sync: added the ' + ADDED_AT_TITLE_ + ' column (column ' + columnLetter_(after) + ') to the ' + sheet.getName() + ' sheet.');
  return { column: after, moved: false };
}

/**
 * The script from before units wrote Type as "integer", this one writes "quantity". A dropdown of the
 * person's own on Type that allows "integer" (in any case) but not "quantity" would flag every quantity
 * the script writes from now on, or refuse it when set to "Reject the input": "quantity" is added to its
 * list, keeping the rule's other settings and help text. Best effort (only logged when it fails).
 */
function allowQuantityInTypeRules_(sheet) {
  try {
    const typeColumn = itemLayout_(readHeader_(sheet)).type;
    const rows = sheet.getMaxRows() - 1;
    if (!typeColumn || rows < 1) return;
    const range = sheet.getRange(2, typeColumn, rows, 1);
    const adapted = new Map(); // one new rule per distinct old one
    let changed = 0;
    const rules = range.getDataValidations().map((line) => {
      const rule = line[0];
      if (!rule || rule.getCriteriaType() !== SpreadsheetApp.DataValidationCriteria.VALUE_IN_LIST) return [rule];
      const criteria = rule.getCriteriaValues() || [];
      const values = Array.isArray(criteria[0]) ? criteria[0] : [];
      const words = values.map((v) => String(v).trim().toLowerCase());
      if (words.indexOf(TYPE_INTEGER_) < 0 || words.indexOf(TYPE_QUANTITY_) >= 0) return [rule];
      const key = JSON.stringify([values, criteria[1], rule.getAllowInvalid(), rule.getHelpText()]);
      if (!adapted.has(key)) {
        adapted.set(key, rule.copy().requireValueInList(values.concat([TYPE_QUANTITY_]), criteria[1] !== false).build());
      }
      changed += 1;
      return [adapted.get(key)];
    });
    if (!changed) return;
    range.setDataValidations(rules);
    console.log('Grocery Sync: added "' + TYPE_QUANTITY_ + '" to the dropdown on the Type column of the ' + sheet.getName() +
        ' sheet (it allowed "' + TYPE_INTEGER_ + '"; the script now writes "' + TYPE_QUANTITY_ + '").');
  } catch (err) {
    console.warn('Grocery Sync: could not add "' + TYPE_QUANTITY_ + '" to the dropdown on the Type column of the ' + sheet.getName() +
        ' sheet: ' + errorMessage_(err));
  }
}

/**
 * Adds the Unit column to a History sheet from before units (returns ["Unit"], else []); when that fails,
 * History rows go without units. History has no Added At column (its rows say "added" or "updated").
 */
function migrateHistorySheetQuietly_(sheet) {
  try {
    const added = addUnitColumn_(sheet, HISTORY_HEADER_V2_, HISTORY_HEADER_);
    if (added.column && !added.moved) console.log('Grocery Sync: added the Unit column to the ' + sheet.getName() + ' sheet.');
    return added.column > 0 ? [UNIT_TITLE_] : [];
  } catch (err) {
    console.warn('Grocery Sync: could not add the Unit column to the ' + sheet.getName() + ' sheet (its new rows go without units): ' +
        errorMessage_(err));
    return [];
  }
}

/**
 * Gives a sheet from before units (Type right after Need) its Unit column right after Need. Returns
 * { column: the Unit column, or 0 when nothing was done, moved }:
 *  - row 1 blank (ensureHeader_ writes it), or a column titled Unit right after Need: nothing to do;
 *  - a column titled Unit elsewhere while Type comes right after Need (one the person kept units in
 *    before, or moved by hand): moved next to Need, with its cells, rules and formats (moved = true);
 *    when something else follows Need it stays, and the phones get a message saying what to move;
 *  - right after Need a column with a title of the person's own, and Type after it: the Unit column
 *    renamed by hand (see hasUnitColumn_), left as it is;
 *  - right after Need a column with a blank title and Type after it (the Unit title cleared by hand), or
 *    an empty untitled column (an earlier attempt stopped after inserting it): titled Unit;
 *  - else a new column titled Unit is inserted after Need. It takes over Need's rules and formats
 *    (checkboxes!), so its rules are cleared.
 */
function addUnitColumn_(sheet, legacyTitles, titles) {
  const nothing = { column: 0, moved: false };
  const header = readHeader_(sheet);
  if (header.every(isBlank_)) return nothing;
  const keys = header.map(headerKey_);
  const typeKey = headerKey_('Type');
  const titled = keys.indexOf(headerKey_(UNIT_TITLE_)) + 1;
  const searched = titled ? titles : legacyTitles;
  const need = locateColumns_(header, searched, titles)[searched.indexOf('Need')];
  if (!need) {
    if (titled) return nothing; // the phones' message says which title is missing
    throw new Error('the ' + sheet.getName() + ' sheet has no Need column (row 1), so its Unit column cannot be added');
  }
  if (titled) {
    if (titled === need + 1 || keys[need] !== typeKey) return nothing;
    sheet.moveColumns(sheet.getRange(1, titled), need + 1);
    const moved = titled > need ? need + 1 : need;
    console.log('Grocery Sync: moved the Unit column of the ' + sheet.getName() + ' sheet from column ' + columnLetter_(titled) +
        ' to column ' + columnLetter_(moved) + ', right after Need.');
    return { column: moved, moved: true };
  }
  const unit = need + 1;
  const after = unit <= keys.length ? keys[unit - 1] : '';
  const typeFollows = keys[unit] === typeKey;
  if (after !== '' && typeFollows && titles.map(headerKey_).indexOf(after) < 0) return nothing;
  if (!(after === '' && (typeFollows || columnIsEmpty_(sheet, unit)))) sheet.insertColumnAfter(need);
  const title = sheet.getRange(1, unit);
  title.setValue(UNIT_TITLE_);
  title.setFontWeight('bold');
  if (sheet.getMaxRows() > 1) sheet.getRange(2, unit, sheet.getMaxRows() - 1, 1).clearDataValidations();
  return { column: unit, moved: false };
}

function columnIsEmpty_(sheet, column) {
  if (column > sheet.getMaxColumns()) return false;
  const rows = Math.max(sheet.getLastRow(), 1);
  return sheet.getRange(1, column, rows, 1).getValues().every((line) => isBlank_(line[0]));
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

/** True when the edited range covers the Item, Need, Unit or Type column. */
function editsItemInput_(range, layout) {
  if (!layout.item) return false;
  const from = range.getColumn();
  const to = range.getLastColumn();
  return [layout.item, layout.need, layout.unit, layout.type].some((c) => c > 0 && c >= from && c <= to);
}

/** Writes Updated At = now and Updated By = "Sheet" into `count` rows from `row`. */
function stampRows_(sheet, layout, row, count, now) {
  const fill = (value) => {
    const values = [];
    for (let i = 0; i < count; i++) values.push(value);
    return values;
  };
  if (layout.updatedAt && layout.updatedBy === layout.updatedAt + 1) {
    sheet.getRange(row, layout.updatedAt, count, 2).setValues(fill([now, SHEET_EDITOR_]));
  } else {
    if (layout.updatedAt) sheet.getRange(row, layout.updatedAt, count, 1).setValues(fill([now]));
    if (layout.updatedBy) sheet.getRange(row, layout.updatedBy, count, 1).setValues(fill([SHEET_EDITOR_]));
  }
  if (layout.updatedAt) sheet.getRange(row, layout.updatedAt, count, 1).setNumberFormat(DATE_FORMAT_);
}

/**
 * For each of `count` rows from `row`, read before stampRows_ (nowMs = the time it is about to stamp): true
 * for a row a person is adding by hand, whose Added At gets that same time:
 *  - its Updated At and Added At cells are both blank: a row being typed (any row the script or an earlier
 *    edit wrote has an Updated At);
 *  - or itemCellWasEmpty (a name typed into an empty Item cell, see typedIntoEmptyItemCell_; count is 1)
 *    and it has an Updated At: the row still holds the cells of an item removed by clearing its Item cell
 *    (as the sheet's notes suggest), and the new item must not inherit that item's Added At;
 *  - or it was typed by hand moments ago and is still being filled in, one cell per edit (Item, then
 *    Need, Unit, Type): Updated By is "Sheet", Added At equals Updated At (nobody has changed it since it
 *    was added) and that was less than HAND_ROW_TYPING_MS_ ago. Without this, the second cell typed would
 *    make a brand-new item read "Updated by Sheet".
 * Rows a phone added or changed (Updated By is its name) and older rows keep their Added At. False for
 * every row while the sheet has no Added At column yet.
 */
function rowsBeingAdded_(sheet, layout, row, count, nowMs, itemCellWasEmpty) {
  if (!layout.updatedAt || !layout.addedAt) {
    const none = [];
    for (let i = 0; i < count; i++) none.push(false);
    return none;
  }
  const columns = [layout.updatedAt, layout.addedAt].concat(layout.updatedBy ? [layout.updatedBy] : []);
  const first = Math.min.apply(null, columns);
  const last = Math.max.apply(null, columns);
  return sheet.getRange(row, first, count, last - first + 1).getValues().map((cells) => {
    const updatedAt = cells[layout.updatedAt - first];
    const addedAt = cells[layout.addedAt - first];
    if (isBlank_(updatedAt)) return isBlank_(addedAt);
    if (itemCellWasEmpty) return true;
    if (!layout.updatedBy || cellToString_(cells[layout.updatedBy - first]) !== SHEET_EDITOR_) return false;
    const updatedMs = toMillis_(updatedAt);
    const addedMs = toMillis_(addedAt);
    if (!updatedMs || !addedMs || Math.abs(updatedMs - addedMs) >= SAME_TIME_MS_) return false;
    const age = nowMs - updatedMs;
    return age > -SAME_TIME_MS_ && age < HAND_ROW_TYPING_MS_;
  });
}

/**
 * True when a person typed a name into an Item cell that was empty (see rowsBeingAdded_). Only for a single
 * cell: Sheets reports the previous value (oldValue) and the typed one (value) only then; a pasted block is
 * treated like any other edit.
 */
function typedIntoEmptyItemCell_(e, range, layout) {
  if (range.getColumn() !== layout.item || range.getRow() < 2) return false;
  if (range.getNumRows() !== 1 || range.getNumColumns() !== 1) return false;
  return isBlank_(e.oldValue) && !isBlank_(e.value) && typeof e.value !== 'object' && String(e.value).trim() !== '';
}

/** Writes Added At = now into `count` rows from `row` (rows added by hand, see rowsBeingAdded_), as a date like Updated At. */
function stampAddedAt_(sheet, layout, row, count, now) {
  const values = [];
  for (let i = 0; i < count; i++) values.push([now]);
  const range = sheet.getRange(row, layout.addedAt, count, 1);
  range.setValues(values);
  range.setNumberFormat(DATE_FORMAT_);
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
function rememberHandRemoval_(e, range, now, layout) {
  if (range.getColumn() !== layout.item || range.getRow() < 2) return;
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
 * Records on a write error which cells were being written: { sheet, sheetName, header (the titles of
 * the written columns), firstRow, firstColumn (default 1), numRows, rules (read before writing,
 * optional), values (the rows written, as the script meant them, optional), ownColumns (rules the
 * script sets itself, 1-based within the written columns) }.
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
 * per cell: { column (within the target), name, helpText, mayRefuse = the value written there may
 * break the rule }.
 */
function rejectingRules_(target) {
  let grid = target.rules;
  if (!grid) {
    if (!target.numRows) return [];
    try {
      grid = target.sheet.getRange(target.firstRow, target.firstColumn || 1, target.numRows, target.header.length).getDataValidations();
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
    const column = cell ? columnNumber_(cell[1]) - (target.firstColumn || 1) + 1 : 0;
    const suspects = rules.filter((r) => r.mayRefuse);
    columns = column >= 1 && column <= target.header.length && target.header[column - 1]
        ? [{ column, name: target.header[column - 1] }]
        : suspects.length ? suspects : rules;
  }
  const names = [];
  columns.slice().sort((a, b) => a.column - b.column).forEach((c) => {
    if (c.name && names.indexOf(c.name) < 0) names.push(c.name);
  });
  return names;
}

/**
 * For setupSheets: a warning per sheet whose columns carry data validation set to "Reject the input"
 * (other than the Need checkboxes and the Unit column, whose rules the script manages), since Sheets
 * would refuse writes there.
 */
function strictValidationWarnings_(sheets) {
  const fix = ' Remove the rule: select the column, choose Data > Data validation and delete it' +
      ' (or set "If the data is invalid" to "Show a warning").';
  const isItemsOwnRule = (name, rule) => name === UNIT_TITLE_ ||
      (name === 'Need' && rule.getCriteriaType() === SpreadsheetApp.DataValidationCriteria.CHECKBOX);
  // The columns as the script finds them (for Items: Added At only where itemLayout_ finds it).
  const itemColumns = (sheet) => {
    const layout = itemLayoutOf_(sheet);
    return ITEM_FIELDS_.map((field) => layout[field]);
  };
  const titledColumns = (titles, legacyTitles) => (sheet) => sheetColumns_(readHeader_(sheet), titles, legacyTitles);
  const checks = [
    [sheets.items, CONFIG.ITEMS_SHEET, ITEMS_HEADER_, itemColumns, isItemsOwnRule,
      'The app writes these cells itself (Type is "boolean" or "quantity"), so the sheet would refuse its changes.'],
    [sheets.tombstones, CONFIG.TOMBSTONE_SHEET, TOMBSTONE_HEADER_, titledColumns(TOMBSTONE_HEADER_, TOMBSTONE_HEADER_), () => false,
      'The script records deletions there, so the sheet would refuse deletions from the app.'],
    [sheets.history, CONFIG.HISTORY_SHEET, HISTORY_HEADER_, titledColumns(HISTORY_HEADER_, HISTORY_HEADER_V2_), () => false,
      'History rows the sheet refuses are left out of the History.'],
  ];
  const warnings = [];
  checks.forEach(([sheet, name, titles, locate, isOwnRule, consequence]) => {
    if (!sheet) return;
    try {
      const located = locate(sheet);
      const columns = strictColumns_(sheet, titles.map((title, i) => ({ name: title, column: located[i] })), isOwnRule);
      if (!columns.length) return;
      warnings.push('Warning: data validation on the ' + joinWords_(columns) + (columns.length > 1 ? ' columns' : ' column') +
          ' of the ' + name + ' sheet is set to "Reject the input". ' + consequence + fix);
    } catch (err) {
      warnings.push('Warning: could not check the data validation of the ' + name + ' sheet: ' + errorMessage_(err));
    }
  });
  return warnings;
}

/** Titles of the columns ({ name, column }, rows 2 and below) holding a rule that rejects invalid input. */
function strictColumns_(sheet, columns, isOwnRule) {
  const rows = sheet.getMaxRows() - 1;
  const present = columns.filter((c) => c.column > 0).sort((a, b) => a.column - b.column);
  if (rows < 1 || !present.length) return [];
  const first = present[0].column;
  const grid = sheet.getRange(2, first, rows, present[present.length - 1].column - first + 1).getDataValidations();
  return present.filter((c) => grid.some((line) => {
    const rule = line[c.column - first];
    return Boolean(rule) && !rule.getAllowInvalid() && !isOwnRule(c.name, rule);
  })).map((c) => c.name);
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
 * the text as it is. Other values in those columns (a date in Added At) are written as they are.
 */
function guardTextCells_(range, rows, textColumns) {
  const formats = range.getNumberFormats();
  return rows.map((row, i) => row.map((value, j) => (
    textColumns.indexOf(j) >= 0 && typeof value === 'string' ? cellText_(value, formats[i][j]) : value)));
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

/** 1 -> "A", 3 -> "C", 27 -> "AA". */
function columnLetter_(column) {
  let letters = '';
  for (let n = column; n > 0; n = Math.floor((n - 1) / 26)) letters = String.fromCharCode(65 + ((n - 1) % 26)) + letters;
  return letters;
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
