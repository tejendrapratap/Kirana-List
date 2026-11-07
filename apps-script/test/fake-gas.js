'use strict';
/**
 * In-memory fake of the Apps Script services Code.gs uses, faithful enough to catch the mistakes the
 * real services punish: 0-row/0-column ranges, setValues dimension mismatches, writing past the last
 * row, deleting every non-frozen row, strict data validation and Sheets' coercion of typed strings
 * ("=..." becomes a formula, "12" a number, "TRUE" a boolean, "Jan 5" a date, "5pm" a time, "₹50" a
 * number; a leading apostrophe keeps text and is dropped; cells formatted as Plain text keep any string
 * verbatim).
 *
 * Data validation: checkbox and dropdown (value in list) rules with help text; a write that a rule set
 * to "Reject the input" refuses throws like Sheets (the help text, else "The data you entered in cell
 * C2 violates the data validation rules set on this cell.") and writes nothing.
 *
 * Inserted columns (insertColumn(s)After / Before) take the number format, font weight and data
 * validation of the column they were inserted next to, as in Sheets: a column inserted after a column
 * of checkboxes gets checkboxes too. (Inserted rows start blank.) moveColumns moves cells with their
 * rules and formats.
 *
 * It can also imitate what the synchronous vm hides by default:
 *  - Dates are stored as wall-clock values in the spreadsheet's time zone, so changing the zone shifts
 *    every stored time (gas.spreadsheet.setTimeZoneOffsetForTest).
 *  - A person editing the sheet while a script runs: gas.afterNextRead runs a callback right after a
 *    script's getValues(), i.e. between its read and its writes. onEdit events raised meanwhile wait for
 *    the script lock, as the real trigger does.
 *  - CacheService dropping entries early (gas.cache.evictAll).
 *  - A Sheets write failing for another reason, e.g. a time-out (gas.failNextWrite).
 *  - Apps Script queueing writes: with gas.reportWriteErrorsLate() a failed setValues() writes nothing
 *    and its error is thrown by the next SpreadsheetApp.flush() or getValues() instead.
 *
 * loadCodeGs(options) runs Code.gs (or options.codePath, e.g. test/fixtures/Code.v2.gs, the script from
 * before units, or test/fixtures/Code.v3.gs, the script of app 1.1 from before the Added At column) in a
 * node:vm context wired to these fakes with a controllable clock. reloadCode(path) swaps the script and
 * keeps the spreadsheet, like pasting a new Code.gs; seedV2Layout(...) writes Items and History in the
 * layout of the script from before units.
 */
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const CODE_GS_PATH = path.join(__dirname, '..', 'Code.gs');
const DEFAULT_NOW = Date.UTC(2026, 10, 7, 12, 0, 0);
const DEFAULT_MAX_ROWS = 1000;
const DEFAULT_MAX_COLUMNS = 26;
const CACHE_MAX_TTL_SECONDS = 21600;
const CACHE_MAX_KEY_LENGTH = 250;

const isDate = (v) => Object.prototype.toString.call(v) === '[object Date]';
const copyValue = (v) => (isDate(v) ? new Date(v.getTime()) : v);

function gasError(message) {
  return new Error(`Exception: ${message}`);
}

function requireInteger(value, what) {
  if (typeof value !== 'number' || !Number.isInteger(value)) {
    throw gasError(`Invalid argument: ${what} must be an integer (got ${String(value)})`);
  }
}

class Clock {
  /** @param {number|null} fixedMs null follows the real clock */
  constructor(fixedMs) {
    this.fixed = fixedMs;
  }
  now() {
    return this.fixed === null ? Date.now() : this.fixed;
  }
  set(ms) {
    this.fixed = ms;
  }
  advance(ms) {
    this.fixed = this.now() + ms;
  }
}

// ----------------------------------------------------------------------------------------------
// Sheets
// ----------------------------------------------------------------------------------------------

const PLAIN_TEXT_FORMAT = '@';
const MONTH = '(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.?';
const MONTH_DATE_RES = [
  new RegExp(`^${MONTH}\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+\\d{2,4})?$`, 'i'), // Jan 5, March 5th 2026
  new RegExp(`^\\d{1,2}(?:st|nd|rd|th)?\\s+${MONTH}(?:,?\\s+\\d{2,4})?$`, 'i'), // 5 March
  new RegExp(`^${MONTH}\\s+\\d{4}$`, 'i'), // Mar 2026
];
const TIME_RE = /^(?:\d{1,2}(?::\d{2}){0,2}\s*[ap]\.?m\.?|\d{1,2}:\d{2}(?::\d{2})?)$/i;
// Sheets recognises the locale's currency and the common foreign ones.
const CURRENCY_RE = /^(?:[$€£¥₹]\s?\d[\d,]*(?:\.\d+)?|\d[\d,]*(?:\.\d+)?\s?[$€£¥₹])$/;
const THOUSANDS_RE = /^[+-]?\d{1,3}(?:,\d{3})+(?:\.\d+)?$/;

/**
 * What Sheets stores when a script writes `value`: strings are parsed like typed input, except in
 * cells formatted as Plain text, which keep them verbatim (a leading apostrophe included).
 */
function coerceInput(value, a1, numberFormat) {
  if (value === null) return { value: '', formula: null };
  if (value === undefined) throw gasError(`Cannot write undefined to ${a1}.`);
  if (typeof value === 'boolean') return { value, formula: null };
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw gasError(`Invalid number ${value} for ${a1}.`);
    return { value, formula: null };
  }
  if (isDate(value)) {
    if (Number.isNaN(value.getTime())) throw gasError(`Invalid Date for ${a1}.`);
    return { value: new Date(value.getTime()), formula: null };
  }
  if (typeof value !== 'string') throw gasError(`Unsupported value type ${typeof value} for ${a1}.`);

  if (numberFormat === PLAIN_TEXT_FORMAT) return { value, formula: null };
  if (value.startsWith("'")) return { value: value.slice(1), formula: null };
  if (value.startsWith('=')) return { value: '#ERROR!', formula: value };
  const t = value.trim();
  if (/^[+-]?(\d+\.?\d*|\.\d+)(e[+-]?\d+)?$/i.test(t)) return { value: Number(t), formula: null };
  if (THOUSANDS_RE.test(t)) return { value: Number(t.replace(/,/g, '')), formula: null };
  if (CURRENCY_RE.test(t)) return { value: Number(t.replace(/[^\d.]/g, '')), formula: null };
  if (/^[+-]?\d+(\.\d+)?%$/.test(t)) return { value: Number(t.slice(0, -1)) / 100, formula: null };
  if (/^(true|false)$/i.test(t)) return { value: t.toLowerCase() === 'true', formula: null };
  if (/^\d{1,4}[/-]\d{1,2}([/-]\d{1,4})?$/.test(t)) {
    const parsed = Date.parse(t.includes('/') && t.split('/').length === 2 ? `${t}/2026` : t);
    return { value: new Date(Number.isNaN(parsed) ? 0 : parsed), formula: null };
  }
  if (MONTH_DATE_RES.some((re) => re.test(t))) {
    const parsed = Date.parse(/\d{4}/.test(t) ? t : `${t} 2026`);
    return { value: new Date(Number.isNaN(parsed) ? Date.UTC(2026, 0, 1) : parsed), formula: null };
  }
  if (TIME_RE.test(t)) return { value: new Date(Date.UTC(1899, 11, 30, 12, 0, 0)), formula: null };
  if (/^[+\-@]/.test(value)) return { value: '#ERROR!', formula: value };
  return { value, formula: null };
}

function columnLetter(col) {
  let s = '';
  for (let n = col; n > 0; n = Math.floor((n - 1) / 26)) s = String.fromCharCode(65 + ((n - 1) % 26)) + s;
  return s;
}

/**
 * A data validation rule. CHECKBOX and VALUE_IN_LIST ("Dropdown" in the Sheets UI) are modelled; a
 * list matches the written value exactly, case included (a "Boolean" / "Integer" dropdown refuses
 * "integer"). getCriteriaValues() returns what Apps Script returns: [checked, unchecked] for a
 * checkbox, [values, showDropdown] for a list.
 */
class DataValidation {
  constructor(criteriaType, criteriaValues, allowInvalid, helpText = null) {
    this.criteriaType = criteriaType;
    this.criteriaValues = criteriaValues;
    this.allowInvalid = allowInvalid;
    this.helpText = helpText;
  }
  getCriteriaType() {
    return this.criteriaType;
  }
  getCriteriaValues() {
    if (this.criteriaType === 'VALUE_IN_LIST') return [this.criteriaValues[0].slice(), this.criteriaValues[1]];
    return this.criteriaValues.slice();
  }
  getAllowInvalid() {
    return this.allowInvalid;
  }
  /** null when the rule has no help text, as in Apps Script. */
  getHelpText() {
    return this.helpText;
  }
  copy() {
    const builder = new DataValidationBuilder();
    Object.assign(builder, {
      criteriaType: this.criteriaType,
      criteriaValues: this.criteriaValues,
      allowInvalid: this.allowInvalid,
      helpText: this.helpText,
    });
    return builder;
  }
  accepts(value) {
    if (value === '') return true;
    if (this.criteriaType === 'CHECKBOX') {
      return this.criteriaValues.length ? this.criteriaValues.includes(value) : typeof value === 'boolean';
    }
    if (this.criteriaType === 'VALUE_IN_LIST') {
      return this.criteriaValues[0].some((allowed) => String(allowed) === String(value));
    }
    return true;
  }
}

class DataValidationBuilder {
  constructor() {
    this.criteriaType = null;
    this.criteriaValues = [];
    this.allowInvalid = true; // Apps Script default for new rules
    this.helpText = null;
  }
  requireCheckbox(checkedValue, uncheckedValue) {
    this.criteriaType = 'CHECKBOX';
    this.criteriaValues = [checkedValue, uncheckedValue].filter((v) => v !== undefined);
    return this;
  }
  requireValueInList(values, showDropdown = true) {
    if (!Array.isArray(values) || values.length === 0) throw gasError('Invalid argument: values');
    this.criteriaType = 'VALUE_IN_LIST';
    this.criteriaValues = [values.map(String), Boolean(showDropdown)];
    return this;
  }
  setAllowInvalid(allowInvalid) {
    this.allowInvalid = Boolean(allowInvalid);
    return this;
  }
  setHelpText(helpText) {
    if (typeof helpText !== 'string') throw gasError('Invalid argument: helpText');
    this.helpText = helpText === '' ? null : helpText;
    return this;
  }
  build() {
    if (!this.criteriaType) throw gasError('A data validation rule needs a criterion before build().');
    return new DataValidation(this.criteriaType, this.criteriaValues, this.allowInvalid, this.helpText);
  }
}

/**
 * What Sheets throws when a script writes a value that a rule set to "Reject the input" refuses: the
 * rule's help text when it has one (that is all the message says), else the generic text. The message
 * carries no "Exception: " prefix, as err.message in Apps Script (the log adds it).
 */
function validationError(rule, a1) {
  return new Error(rule.getHelpText() || `The data you entered in cell ${a1} violates the data validation rules set on this cell.`);
}

function newCell() {
  return { value: '', formula: null, validation: null, numberFormat: null, fontWeight: null };
}

class FakeRange {
  constructor(sheet, row, column, numRows, numColumns) {
    this.sheet = sheet;
    this.row = row;
    this.column = column;
    this.numRows = numRows;
    this.numColumns = numColumns;
  }

  getSheet() {
    return this.sheet;
  }
  getRow() {
    return this.row;
  }
  getColumn() {
    return this.column;
  }
  getNumRows() {
    return this.numRows;
  }
  getNumColumns() {
    return this.numColumns;
  }
  getLastRow() {
    return this.row + this.numRows - 1;
  }
  getLastColumn() {
    return this.column + this.numColumns - 1;
  }
  getA1Notation() {
    const start = `${columnLetter(this.column)}${this.row}`;
    if (this.numRows === 1 && this.numColumns === 1) return start;
    return `${start}:${columnLetter(this.getLastColumn())}${this.getLastRow()}`;
  }

  _map(fn) {
    const out = [];
    for (let r = 0; r < this.numRows; r++) {
      const line = [];
      for (let c = 0; c < this.numColumns; c++) line.push(fn(this.row + r, this.column + c, r, c));
      out.push(line);
    }
    return out;
  }

  _checkGrid(grid, what) {
    if (!Array.isArray(grid)) throw gasError(`The parameters (${typeof grid}) don't match the method signature for ${what}.`);
    if (grid.length !== this.numRows) {
      throw gasError(`The number of rows in the data does not match the number of rows in the range. The data has ${grid.length} but the range has ${this.numRows}.`);
    }
    grid.forEach((line) => {
      if (!Array.isArray(line)) throw gasError(`The parameters don't match the method signature for ${what}.`);
      if (line.length !== this.numColumns) {
        throw gasError(`The number of columns in the data does not match the number of columns in the range. The data has ${line.length} but the range has ${this.numColumns}.`);
      }
    });
  }

  getValues() {
    this.sheet.spreadsheet._throwLateWriteError(); // a read sends the queued writes first
    const tz = this.sheet.spreadsheet.timeZoneOffsetMs;
    const values = this._map((r, c) => {
      const cell = this.sheet._peek(r, c);
      if (!cell) return '';
      // Stored dates are wall-clock times in the spreadsheet's time zone.
      return isDate(cell.value) ? new Date(cell.value.getTime() - tz) : copyValue(cell.value);
    });
    this.sheet._afterRead(this);
    return values;
  }
  getValue() {
    return this.getValues()[0][0];
  }

  setValues(values) {
    this._checkGrid(values, 'Range.setValues');
    const tz = this.sheet.spreadsheet.timeZoneOffsetMs;
    let coerced;
    try {
      this.sheet._injectedWriteFailure(this);
      // Validate everything first: a rejected write leaves the range unchanged, as in Sheets.
      coerced = this._map((r, c, i, j) => {
        const a1 = `${columnLetter(c)}${r}`;
        const cell = this.sheet._peek(r, c);
        const input = coerceInput(values[i][j], a1, cell ? cell.numberFormat : null);
        if (isDate(values[i][j])) input.value = new Date(input.value.getTime() + tz);
        if (cell && cell.validation && !cell.validation.getAllowInvalid() && !cell.validation.accepts(input.value)) {
          throw validationError(cell.validation, a1);
        }
        return input;
      });
    } catch (err) {
      if (this.sheet.spreadsheet._keepWriteErrorForLater(err)) return this;
      throw err;
    }
    this._map((r, c, i, j) => Object.assign(this.sheet._cell(r, c), coerced[i][j]));
    return this;
  }
  setValue(value) {
    return this.setValues(this._map(() => value));
  }
  clearContent() {
    this._map((r, c) => {
      const cell = this.sheet._peek(r, c);
      if (cell) Object.assign(cell, { value: '', formula: null });
    });
    return this;
  }

  getFormulas() {
    return this._map((r, c) => (this.sheet._peek(r, c) || {}).formula || '');
  }

  setDataValidation(rule) {
    if (rule !== null && !(rule instanceof DataValidation)) throw gasError('Invalid argument: rule');
    this._map((r, c) => {
      this.sheet._cell(r, c).validation = rule;
    });
    return this;
  }
  setDataValidations(rules) {
    this._checkGrid(rules, 'Range.setDataValidations');
    rules.forEach((line) => line.forEach((rule) => {
      if (rule !== null && !(rule instanceof DataValidation)) throw gasError('Invalid argument: rules');
    }));
    this._map((r, c, i, j) => {
      this.sheet._cell(r, c).validation = rules[i][j];
    });
    return this;
  }
  clearDataValidations() {
    this._map((r, c) => {
      const cell = this.sheet._peek(r, c);
      if (cell) cell.validation = null;
    });
    return this;
  }
  getDataValidation() {
    return this.getDataValidations()[0][0];
  }
  getDataValidations() {
    return this._map((r, c) => (this.sheet._peek(r, c) || {}).validation || null);
  }

  setNumberFormat(format) {
    if (typeof format !== 'string') throw gasError('Invalid argument: numberFormat');
    this._map((r, c) => {
      this.sheet._cell(r, c).numberFormat = format;
    });
    return this;
  }
  getNumberFormat() {
    return this.getNumberFormats()[0][0];
  }
  getNumberFormats() {
    return this._map((r, c) => (this.sheet._peek(r, c) || {}).numberFormat || 'General');
  }

  setFontWeight(weight) {
    if (weight !== null && weight !== 'bold' && weight !== 'normal') throw gasError('Invalid argument: fontWeight');
    this._map((r, c) => {
      this.sheet._cell(r, c).fontWeight = weight;
    });
    return this;
  }
  getFontWeight() {
    const cell = this.sheet._peek(this.row, this.column);
    return (cell && cell.fontWeight) || 'normal';
  }
}

class FakeSheet {
  constructor(spreadsheet, name, maxRows = DEFAULT_MAX_ROWS, maxColumns = DEFAULT_MAX_COLUMNS) {
    this.spreadsheet = spreadsheet;
    this.name = name;
    this.maxRows = maxRows;
    this.maxColumns = maxColumns;
    this.grid = []; // grid[row - 1][column - 1] = cell; sparse
    this.frozenRows = 0;
    this.hidden = false;
    this.readHooks = []; // { fn, times, when } run after getValues(), see gas.afterNextRead
    this.inReadHook = false;
    this.writeFailures = []; // { message, times, when } thrown by setValues(), see gas.failNextWrite
  }

  /** Throws the next injected failure whose `when` accepts this write (see gas.failNextWrite). */
  _injectedWriteFailure(range) {
    const failure = this.writeFailures.find((f) => !f.when || f.when(range));
    if (!failure) return;
    failure.times -= 1;
    if (failure.times <= 0) this.writeFailures = this.writeFailures.filter((f) => f !== failure);
    throw new Error(failure.message);
  }

  /** Test helper: removes every data validation rule in `rules` (a Set) from this sheet's cells. */
  removeDataValidationsForTest(rules) {
    let removed = 0;
    this.grid.forEach((line) => {
      if (!line) return;
      line.forEach((cell) => {
        if (cell && cell.validation && rules.has(cell.validation)) {
          cell.validation = null;
          removed += 1;
        }
      });
    });
    return removed;
  }

  /** Runs the registered "person edits the sheet now" callbacks after a script read of range. */
  _afterRead(range) {
    if (this.inReadHook || !this.readHooks.length) return;
    const due = this.readHooks.filter((hook) => !hook.when || hook.when(range));
    if (!due.length) return;
    due.forEach((hook) => {
      hook.times -= 1;
    });
    this.readHooks = this.readHooks.filter((hook) => hook.times > 0);
    this.inReadHook = true;
    try {
      due.forEach((hook) => hook.fn(this));
    } finally {
      this.inReadHook = false;
    }
  }

  _peek(row, column) {
    const line = this.grid[row - 1];
    return line ? line[column - 1] : undefined;
  }
  _cell(row, column) {
    if (!this.grid[row - 1]) this.grid[row - 1] = [];
    const line = this.grid[row - 1];
    if (!line[column - 1]) line[column - 1] = newCell();
    return line[column - 1];
  }

  getName() {
    return this.name;
  }
  setName(name) {
    if (this.spreadsheet._nameTaken(name, this)) {
      throw gasError(`A sheet with the name "${name}" already exists. Please enter another name.`);
    }
    this.name = name;
    return this;
  }
  getParent() {
    return this.spreadsheet;
  }
  getMaxRows() {
    return this.maxRows;
  }
  getMaxColumns() {
    return this.maxColumns;
  }

  getLastRow() {
    for (let r = this.grid.length; r >= 1; r--) {
      const line = this.grid[r - 1];
      if (line && line.some((cell) => cell && cell.value !== '')) return r;
    }
    return 0;
  }
  getLastColumn() {
    let last = 0;
    this.grid.forEach((line) => {
      if (!line) return;
      for (let c = line.length; c > last; c--) {
        if (line[c - 1] && line[c - 1].value !== '') {
          last = c;
          break;
        }
      }
    });
    return last;
  }

  getRange(row, column, numRows = 1, numColumns = 1) {
    if (typeof row === 'string') throw new Error('fake-gas: A1-notation getRange is not supported');
    [row, column, numRows, numColumns].forEach((v, i) => requireInteger(v, ['row', 'column', 'numRows', 'numColumns'][i]));
    if (numRows < 1) throw gasError('The number of rows in the range must be at least 1.');
    if (numColumns < 1) throw gasError('The number of columns in the range must be at least 1.');
    if (row < 1 || column < 1 || row + numRows - 1 > this.maxRows || column + numColumns - 1 > this.maxColumns) {
      throw gasError('The coordinates of the range are outside the dimensions of the sheet.');
    }
    return new FakeRange(this, row, column, numRows, numColumns);
  }
  getDataRange() {
    return this.getRange(1, 1, Math.max(this.getLastRow(), 1), Math.max(this.getLastColumn(), 1));
  }

  deleteRows(rowPosition, howMany) {
    requireInteger(rowPosition, 'rowPosition');
    requireInteger(howMany, 'howMany');
    if (rowPosition < 1 || howMany < 1 || rowPosition + howMany - 1 > this.maxRows) {
      throw gasError('Those rows are out of bounds.');
    }
    if (this.maxRows - howMany <= this.frozenRows || this.maxRows - howMany < 1) {
      throw gasError('Sorry, it is not possible to delete all non-frozen rows.');
    }
    this.grid.splice(rowPosition - 1, howMany);
    this.maxRows -= howMany;
  }
  deleteRow(rowPosition) {
    this.deleteRows(rowPosition, 1);
  }
  insertRowsBefore(beforePosition, howMany) {
    requireInteger(beforePosition, 'beforePosition');
    requireInteger(howMany, 'howMany');
    if (beforePosition < 1 || beforePosition > this.maxRows || howMany < 1) throw gasError('Those rows are out of bounds.');
    if (beforePosition - 1 < this.grid.length) this.grid.splice(beforePosition - 1, 0, ...new Array(howMany).fill(undefined));
    this.maxRows += howMany;
    return this;
  }
  insertRowBefore(beforePosition) {
    return this.insertRowsBefore(beforePosition, 1);
  }
  insertRowsAfter(afterPosition, howMany) {
    requireInteger(afterPosition, 'afterPosition');
    requireInteger(howMany, 'howMany');
    if (afterPosition < 1 || afterPosition > this.maxRows || howMany < 1) throw gasError('Those rows are out of bounds.');
    if (afterPosition < this.grid.length) this.grid.splice(afterPosition, 0, ...new Array(howMany).fill(undefined));
    this.maxRows += howMany;
    return this;
  }
  /**
   * Inserts columns like Sheets: the new cells are empty but take the number format, font weight and
   * data validation (checkboxes, dropdowns) of the column they were inserted next to - the column at
   * afterPosition here, the one at beforePosition for insertColumnsBefore.
   */
  insertColumnsAfter(afterPosition, howMany) {
    requireInteger(afterPosition, 'afterPosition');
    requireInteger(howMany, 'howMany');
    if (afterPosition < 1 || afterPosition > this.maxColumns || howMany < 1) throw gasError('Those columns are out of bounds.');
    this._insertColumns(afterPosition, howMany, afterPosition);
    return this;
  }
  insertColumnAfter(afterPosition) {
    return this.insertColumnsAfter(afterPosition, 1);
  }
  insertColumnsBefore(beforePosition, howMany) {
    requireInteger(beforePosition, 'beforePosition');
    requireInteger(howMany, 'howMany');
    if (beforePosition < 1 || beforePosition > this.maxColumns || howMany < 1) throw gasError('Those columns are out of bounds.');
    this._insertColumns(beforePosition - 1, howMany, beforePosition);
    return this;
  }
  insertColumnBefore(beforePosition) {
    return this.insertColumnsBefore(beforePosition, 1);
  }
  /** Inserts howMany columns at 0-based index `at` of every row, formatted like column `like` (1-based, before the insert). */
  _insertColumns(at, howMany, like) {
    this.grid.forEach((line) => {
      if (!line) return;
      const model = line[like - 1];
      const fresh = () => (model ? { ...newCell(), validation: model.validation, numberFormat: model.numberFormat, fontWeight: model.fontWeight } : undefined);
      if (at <= line.length) line.splice(at, 0, ...Array.from({ length: howMany }, fresh));
    });
    this.maxColumns += howMany;
  }
  deleteColumns(columnPosition, howMany) {
    requireInteger(columnPosition, 'columnPosition');
    requireInteger(howMany, 'howMany');
    if (columnPosition < 1 || howMany < 1 || columnPosition + howMany - 1 > this.maxColumns) throw gasError('Those columns are out of bounds.');
    if (this.maxColumns - howMany < 1) throw gasError('Sorry, it is not possible to delete all columns.');
    this.grid.forEach((line) => {
      if (line) line.splice(columnPosition - 1, howMany);
    });
    this.maxColumns -= howMany;
  }
  deleteColumn(columnPosition) {
    this.deleteColumns(columnPosition, 1);
  }
  /**
   * Moves the columns the range spans so that they start before column destinationIndex (counted
   * before the move), like Sheets: their cells, rules and formats go along, the columns between shift.
   */
  moveColumns(columnSpec, destinationIndex) {
    requireInteger(destinationIndex, 'destinationIndex');
    const first = columnSpec.getColumn();
    const count = columnSpec.getNumColumns();
    if (destinationIndex < 1 || destinationIndex > this.maxColumns + 1) throw gasError('Those columns are out of bounds.');
    if (destinationIndex >= first && destinationIndex <= first + count) {
      throw gasError('The destination must be outside the columns being moved.');
    }
    const target = destinationIndex > first ? destinationIndex - 1 - count : destinationIndex - 1;
    this.grid.forEach((line) => {
      if (!line) return;
      while (line.length < this.maxColumns) line.push(undefined);
      const moved = line.splice(first - 1, count);
      line.splice(target, 0, ...moved);
    });
  }

  setFrozenRows(rows) {
    requireInteger(rows, 'rows');
    this.frozenRows = rows;
  }
  getFrozenRows() {
    return this.frozenRows;
  }
  hideSheet() {
    const visible = this.spreadsheet.sheets.filter((s) => !s.hidden);
    if (!this.hidden && visible.length === 1) throw gasError("You can't hide all the sheets in a document.");
    this.hidden = true;
    return this;
  }
  showSheet() {
    this.hidden = false;
    return this;
  }
  isSheetHidden() {
    return this.hidden;
  }

  /** Test helper: empties the sheet (values, rules, formats, size and frozen rows back to a new sheet's). */
  resetForTest() {
    this.grid = [];
    this.maxRows = DEFAULT_MAX_ROWS;
    this.maxColumns = DEFAULT_MAX_COLUMNS;
    this.frozenRows = 0;
    return this;
  }

  /** Test helper: the used block as plain values (row 1 first). */
  dump() {
    const lastRow = this.getLastRow();
    const lastColumn = this.getLastColumn();
    if (!lastRow || !lastColumn) return [];
    return this.getRange(1, 1, lastRow, lastColumn).getValues();
  }
}

class FakeSpreadsheet {
  constructor({ name = 'Groceries', id = 'fake-spreadsheet-id' } = {}) {
    this.name = name;
    this.id = id;
    this.sheets = [new FakeSheet(this, 'Sheet1')];
    this.timeZoneOffsetMs = 0; // UTC offset of the spreadsheet's time zone
    this.lateWriteErrors = false; // see gas.reportWriteErrorsLate
    this.lateWriteError = null;
  }
  /** In "late" mode keeps a failed write's error (the first one) for the next flush or read. */
  _keepWriteErrorForLater(err) {
    if (!this.lateWriteErrors) return false;
    if (!this.lateWriteError) this.lateWriteError = err;
    return true;
  }
  _throwLateWriteError() {
    const err = this.lateWriteError;
    if (!err) return;
    this.lateWriteError = null;
    throw err;
  }
  /**
   * Test helper standing in for File > Settings > Time zone: Sheets keeps the stored wall-clock values,
   * so every stored date is read back as a different instant afterwards.
   */
  setTimeZoneOffsetForTest(offsetMs) {
    this.timeZoneOffsetMs = offsetMs;
  }
  _nameTaken(name, except) {
    return this.sheets.some((s) => s !== except && s.name.toLowerCase() === String(name).toLowerCase());
  }
  getName() {
    return this.name;
  }
  getId() {
    return this.id;
  }
  getUrl() {
    return `https://docs.google.com/spreadsheets/d/${this.id}/edit`;
  }
  getSheets() {
    return this.sheets.slice();
  }
  getSheetByName(name) {
    return this.sheets.find((s) => s.name === name) || null;
  }
  insertSheet(name) {
    const sheetName = name === undefined ? `Sheet${this.sheets.length + 1}` : String(name);
    if (this._nameTaken(sheetName)) {
      throw gasError(`A sheet with the name "${sheetName}" already exists. Please enter another name.`);
    }
    const sheet = new FakeSheet(this, sheetName);
    this.sheets.push(sheet);
    return sheet;
  }
}

function createSpreadsheetApp(spreadsheet, { bound }) {
  const app = {
    flushCount: 0,
    DataValidationCriteria: Object.freeze({ CHECKBOX: 'CHECKBOX', VALUE_IN_LIST: 'VALUE_IN_LIST' }),
    getActiveSpreadsheet: () => (bound ? spreadsheet : null),
    openByUrl(url) {
      if (typeof url === 'string' && url.includes(`/d/${spreadsheet.getId()}`)) return spreadsheet;
      throw gasError('Unexpected error while getting the method or property openByUrl on object SpreadsheetApp.');
    },
    openById(id) {
      if (id === spreadsheet.getId()) return spreadsheet;
      throw gasError('Unexpected error while getting the method or property openById on object SpreadsheetApp.');
    },
    newDataValidation: () => new DataValidationBuilder(),
    flush() {
      app.flushCount += 1;
      spreadsheet._throwLateWriteError();
    },
  };
  return app;
}

// ----------------------------------------------------------------------------------------------
// LockService, CacheService, ContentService, Utilities, Logger
// ----------------------------------------------------------------------------------------------

/**
 * The script lock. Executions are synchronous here, so "another execution waits for the lock" is
 * modelled with whenFree(): the waiting run starts right after releaseLock(), and its first
 * tryLock(0) fails as it would have while the other execution held the lock.
 */
class FakeLock {
  constructor() {
    this.held = false;
    this.failing = false; // set true to make every tryLock time out
    this.contended = false; // the running execution had to wait: tryLock(0) fails once
    this.waiting = [];
    this.acquired = 0;
    this.released = 0;
    this.timeouts = [];
  }
  /** Test helper: make lock acquisition fail (true) or succeed again (false). */
  forceFail(failing = true) {
    this.failing = failing;
  }
  /** Test helper: runs fn now, or after the current holder releases the lock. */
  whenFree(fn) {
    if (this.held) this.waiting.push(fn);
    else fn();
  }
  tryLock(timeoutInMillis) {
    requireInteger(timeoutInMillis, 'timeoutInMillis');
    this.timeouts.push(timeoutInMillis);
    if (this.failing) return false;
    if (this.contended && timeoutInMillis === 0) return false;
    this.contended = false;
    this.held = true;
    this.acquired += 1;
    return true;
  }
  waitLock(timeoutInMillis) {
    if (!this.tryLock(timeoutInMillis)) {
      throw gasError('Lock timeout: another process was holding the lock for too long.');
    }
  }
  releaseLock() {
    if (this.held) this.released += 1;
    this.held = false;
    this.waiting.splice(0).forEach((run) => {
      this.contended = true;
      try {
        run();
      } finally {
        this.contended = false;
      }
    });
  }
  hasLock() {
    return this.held;
  }
}

class FakeCache {
  constructor(clock) {
    this.clock = clock;
    this.entries = new Map();
    this.failing = false; // set true to make every call throw like an overloaded CacheService
  }
  _guard() {
    if (this.failing) throw gasError('Service invoked too many times in a short time: cache. Try Utilities.sleep(1000) between calls.');
  }
  _checkKey(key) {
    if (typeof key !== 'string' || key.length === 0) throw gasError('Invalid argument: key');
    if (key.length > CACHE_MAX_KEY_LENGTH) throw gasError('Argument too large: key');
  }
  _ttl(seconds) {
    if (seconds === undefined) return 600;
    if (typeof seconds !== 'number' || seconds < 1 || seconds > CACHE_MAX_TTL_SECONDS) {
      throw gasError('Invalid argument: expirationInSeconds');
    }
    return seconds;
  }
  _live(key) {
    const entry = this.entries.get(key);
    if (!entry) return null;
    if (entry.expiresAt <= this.clock.now()) {
      this.entries.delete(key);
      return null;
    }
    return entry.value;
  }
  get(key) {
    this._guard();
    this._checkKey(key);
    return this._live(key);
  }
  getAll(keys) {
    this._guard();
    if (!Array.isArray(keys)) throw gasError('Invalid argument: keys');
    const out = {};
    keys.forEach((key) => {
      this._checkKey(key);
      const value = this._live(key);
      if (value !== null) out[key] = value;
    });
    return out;
  }
  put(key, value, expirationInSeconds) {
    this._guard();
    this._checkKey(key);
    const ttl = this._ttl(expirationInSeconds);
    this.entries.set(key, { value: String(value), expiresAt: this.clock.now() + ttl * 1000 });
  }
  putAll(values, expirationInSeconds) {
    this._guard();
    const ttl = this._ttl(expirationInSeconds);
    Object.keys(values).forEach((key) => {
      this._checkKey(key);
      this.entries.set(key, { value: String(values[key]), expiresAt: this.clock.now() + ttl * 1000 });
    });
  }
  remove(key) {
    this._guard();
    this.entries.delete(key);
  }
  /** Test helper: CacheService is best effort and may drop entries long before they expire. */
  evictAll() {
    this.entries.clear();
  }
  removeAll(keys) {
    this._guard();
    keys.forEach((key) => this.entries.delete(key));
  }
}

const MIME_TYPES = Object.freeze({
  ATOM: 'application/atom+xml',
  CSV: 'text/csv',
  ICAL: 'text/calendar',
  JAVASCRIPT: 'application/javascript',
  JSON: 'application/json',
  RSS: 'application/rss+xml',
  TEXT: 'text/plain',
  VCARD: 'text/vcard',
  XML: 'application/xml',
});

class TextOutput {
  constructor(content) {
    this.content = content === undefined || content === null ? '' : String(content);
    this.mimeType = 'TEXT';
  }
  getContent() {
    return this.content;
  }
  setContent(content) {
    this.content = String(content);
    return this;
  }
  append(text) {
    this.content += String(text);
    return this;
  }
  setMimeType(mimeType) {
    if (!Object.prototype.hasOwnProperty.call(MIME_TYPES, mimeType)) throw gasError('Invalid argument: mimeType');
    this.mimeType = mimeType;
    return this;
  }
  getMimeType() {
    return this.mimeType;
  }
  /** Fake-only: the HTTP Content-Type Apps Script would send. */
  contentType() {
    return `${MIME_TYPES[this.mimeType]}; charset=utf-8`;
  }
}

const ContentService = Object.freeze({
  MimeType: Object.freeze(Object.fromEntries(Object.keys(MIME_TYPES).map((k) => [k, k]))),
  createTextOutput: (content) => new TextOutput(content),
});

const DIGESTS = Object.freeze({ MD2: 'md2', MD5: 'md5', SHA_1: 'sha1', SHA_256: 'sha256', SHA_384: 'sha384', SHA_512: 'sha512' });

const Utilities = Object.freeze({
  DigestAlgorithm: Object.freeze(Object.fromEntries(Object.keys(DIGESTS).map((k) => [k, k]))),
  Charset: Object.freeze({ US_ASCII: 'US_ASCII', UTF_8: 'UTF_8' }),
  getUuid: () => crypto.randomUUID(),
  /** Like Java: returns SIGNED bytes (-128..127). */
  computeDigest(algorithm, value, charset) {
    const hash = DIGESTS[algorithm];
    if (!hash) throw gasError('Invalid argument: algorithm');
    let input;
    if (typeof value === 'string') input = Buffer.from(value, charset === 'US_ASCII' ? 'latin1' : 'utf8');
    else if (Array.isArray(value)) input = Buffer.from(value.map((b) => b & 0xff));
    else throw gasError('Invalid argument: value');
    return Array.from(crypto.createHash(hash).update(input).digest(), (b) => (b > 127 ? b - 256 : b));
  },
});

function formatLogArgs(args) {
  if (typeof args[0] === 'string' && args.length > 1 && args[0].includes('%s')) {
    let i = 1;
    return args[0].replace(/%s/g, () => (i < args.length ? String(args[i++]) : '%s'));
  }
  return args.map(String).join(' ');
}

// ----------------------------------------------------------------------------------------------
// Code.gs loader
// ----------------------------------------------------------------------------------------------

// Replaces Date in the script context so `new Date()` and `Date.now()` read the controllable clock.
const CLOCK_SHIM = `
(() => {
  const RealDate = Date;
  const clock = globalThis.__fakeClock;
  delete globalThis.__fakeClock;
  function ControlledDate(...args) {
    if (!new.target) return new RealDate(clock.now()).toString();
    return args.length === 0 ? new RealDate(clock.now()) : new RealDate(...args);
  }
  ControlledDate.prototype = RealDate.prototype;
  ControlledDate.now = () => clock.now();
  ControlledDate.parse = RealDate.parse;
  ControlledDate.UTC = RealDate.UTC;
  globalThis.Date = ControlledDate;
})();
`;

/**
 * Loads Code.gs into a fresh fake Apps Script environment.
 * @param {object} [options]
 * @param {string} [options.token]        overrides CONFIG.SECRET_TOKEN
 * @param {boolean} [options.keepHistory] overrides CONFIG.KEEP_HISTORY
 * @param {string} [options.spreadsheetUrl] overrides CONFIG.SPREADSHEET_URL
 * @param {boolean} [options.bound=true]  getActiveSpreadsheet() returns the sheet (false = standalone script)
 * @param {number|'real'} [options.now]   start time in ms (default fixed 2026-11-07T12:00Z); 'real' follows the wall clock
 * @param {string} [options.spreadsheetName='Groceries']
 * @param {string} [options.codePath]     the script to run (default: apps-script/Code.gs)
 */
function loadCodeGs(options = {}) {
  const clock = new Clock(options.now === 'real' ? null : (options.now ?? DEFAULT_NOW));
  const spreadsheet = new FakeSpreadsheet({ name: options.spreadsheetName });
  const lock = new FakeLock();
  const cache = new FakeCache(clock);
  const logs = [];
  const SpreadsheetApp = createSpreadsheetApp(spreadsheet, { bound: options.bound !== false });

  const Logger = {
    log(...args) {
      logs.push(formatLogArgs(args));
      return Logger;
    },
    getLog: () => logs.join('\n'),
    clear: () => {
      logs.length = 0;
    },
  };
  const consoleFake = Object.fromEntries(['log', 'info', 'warn', 'error'].map((level) => [
    level, (...args) => logs.push(`[${level}] ${formatLogArgs(args)}`),
  ]));

  const overrides = {};
  if (options.token !== undefined) overrides.SECRET_TOKEN = options.token;
  if (options.keepHistory !== undefined) overrides.KEEP_HISTORY = options.keepHistory;
  if (options.spreadsheetUrl !== undefined) overrides.SPREADSHEET_URL = options.spreadsheetUrl;

  /** A fresh script context running the code at codePath against this environment's services. */
  function runScript(codePath) {
    const scriptContext = vm.createContext({
      SpreadsheetApp,
      LockService: { getScriptLock: () => lock },
      CacheService: { getScriptCache: () => cache },
      ContentService,
      Utilities,
      Logger,
      console: consoleFake,
      __fakeClock: clock,
    });
    vm.runInContext(CLOCK_SHIM, scriptContext, { filename: 'fake-clock.js' });
    vm.runInContext(fs.readFileSync(codePath, 'utf8'), scriptContext, { filename: codePath });
    scriptContext.__configOverrides = overrides;
    vm.runInContext('Object.assign(CONFIG, globalThis.__configOverrides); delete globalThis.__configOverrides;', scriptContext);
    return scriptContext;
  }

  let codePath = path.resolve(options.codePath || CODE_GS_PATH);
  let context = runScript(codePath);

  return {
    doGet: (e) => context.doGet(e),
    doPost: (e) => context.doPost(e),
    onEdit: (e) => context.onEdit(e),
    setupSheets: () => context.setupSheets(),
    generateToken: () => context.generateToken(),
    get config() {
      return vm.runInContext('CONFIG', context);
    },
    spreadsheet,
    spreadsheetApp: SpreadsheetApp,
    lock,
    cache,
    clock,
    logs,
    get context() {
      return context;
    },
    get codePath() {
      return codePath;
    },
    /**
     * Swaps the running script for the one at newPath (default: apps-script/Code.gs), keeping the
     * spreadsheet, lock, cache, clock, logs and CONFIG overrides - like pasting a new Code.gs over the
     * old one and deploying a new version.
     */
    reloadCode(newPath = CODE_GS_PATH) {
      const resolved = path.resolve(newPath);
      context = runScript(resolved);
      codePath = resolved;
      return resolved;
    },
    /**
     * Replaces the Items and History sheets with the layout an older Code.gs (protocol 2, before units)
     * set up: Items "Item | Need | Type | Updated At | Updated By" and History "Time | Item | Action |
     * Need | Type | By | Change ID", written as that script wrote them (names as text, a Need checkbox on
     * boolean rows, date formats, bold frozen header). items: rows [name, need, type, updatedAt (ms or
     * Date, default now), updatedBy (default "Home")] or objects { name, need, type, updatedAt,
     * updatedBy }; history: rows [time, item, action, need, type, by, changeId]. _Tombstones is kept
     * (created hidden if missing).
     */
    seedV2Layout({ items = [], history = [] } = {}) {
      const now = clock.now();
      const at = (v) => (v === undefined || v === null || v === '' ? new Date(now) : isDate(v) ? new Date(v.getTime()) : new Date(Number(v)));
      const text = (v) => (v === undefined || v === null || v === '' ? '' : `'${String(v)}`);
      const sheetNamed = (name) => spreadsheet.getSheetByName(name) || spreadsheet.insertSheet(name);
      const write = (sheet, header, rows, dateColumn) => {
        sheet.resetForTest();
        sheet.getRange(1, 1, 1, header.length).setValues([header]).setFontWeight('bold');
        sheet.setFrozenRows(1);
        if (!rows.length) return;
        sheet.getRange(2, 1, rows.length, header.length).setValues(rows);
        sheet.getRange(2, dateColumn, rows.length, 1).setNumberFormat('yyyy-mm-dd hh:mm:ss');
      };
      const itemRows = items.map((row) => {
        const [name, need, type = '', updatedAt, updatedBy = 'Home'] = Array.isArray(row)
          ? row : [row.name, row.need === undefined ? row.value : row.need, row.type, row.updatedAt, row.updatedBy];
        return [text(name), need === undefined || need === null ? '' : need, type, at(updatedAt), text(updatedBy)];
      });
      const items_ = sheetNamed('Items');
      write(items_, ['Item', 'Need', 'Type', 'Updated At', 'Updated By'], itemRows, 4);
      const checkbox = SpreadsheetApp.newDataValidation().requireCheckbox().build();
      itemRows.forEach((row, i) => {
        if (String(row[2]).trim().toLowerCase() === 'boolean') items_.getRange(i + 2, 2).setDataValidation(checkbox);
      });
      const historyRows = history.map(([time, item, action, need = '', type = '', by = 'Home', changeId = '']) =>
        [at(time), text(item), action, need, type, text(by), text(changeId)]);
      write(sheetNamed('History'), ['Time', 'Item', 'Action', 'Need', 'Type', 'By', 'Change ID'], historyRows, 1);
      if (!spreadsheet.getSheetByName('_Tombstones')) {
        const tombstones = spreadsheet.insertSheet('_Tombstones');
        tombstones.getRange(1, 1, 1, 3).setValues([['Key', 'Deleted At', 'By']]).setFontWeight('bold');
        tombstones.setFrozenRows(1);
        tombstones.hideSheet();
      }
      return { items: itemRows.length, history: historyRows.length };
    },
    setNow: (ms) => clock.set(ms),
    advance: (ms) => clock.advance(ms),
    now: () => clock.now(),
    /**
     * Simulates a person typing into the sheet: writes the value(s) like Sheets parses typed input,
     * then fires the onEdit simple trigger with the edited range (value/oldValue only for a single
     * cell, as in Sheets). While a script holds the lock, the trigger runs once it is released.
     */
    handEdit({ sheet = 'Items', row, col, value, values }) {
      const target = spreadsheet.getSheetByName(sheet);
      if (!target) throw new Error(`No sheet named ${sheet}`);
      const grid = values || [[value]];
      const single = grid.length === 1 && grid[0].length === 1;
      const range = target.getRange(row, col, grid.length, grid[0].length);
      const before = target._peek(row, col);
      const oldValue = single && before && before.value !== '' ? copyValue(before.value) : undefined;
      range.setValues(grid);
      const typed = single && grid[0][0] !== '' ? grid[0][0] : undefined;
      const event = { source: spreadsheet, range, value: typed, oldValue, authMode: 'LIMITED' };
      lock.whenFree(() => context.onEdit(event));
      return event;
    },
    /**
     * Runs fn(sheet) right after the next `times` getValues() calls on the named sheet (only reads
     * whose range passes `when`, if given), i.e. while a script is between reading the sheet and
     * writing to it - like a person editing at that moment.
     */
    afterNextRead(sheetName, fn, { times = 1, when } = {}) {
      const target = spreadsheet.getSheetByName(sheetName);
      if (!target) throw new Error(`No sheet named ${sheetName}`);
      target.readHooks.push({ fn, times, when });
    },
    /**
     * Makes the next `times` setValues() calls on the named sheet (only writes whose range passes
     * `when`, if given) throw new Error(message) before writing anything, e.g. a Sheets time-out.
     */
    failNextWrite(sheetName, message, { times = 1, when } = {}) {
      const target = spreadsheet.getSheetByName(sheetName);
      if (!target) throw new Error(`No sheet named ${sheetName}`);
      target.writeFailures.push({ message, times, when });
    },
    /**
     * Imitates Apps Script queueing writes: from now on (or until called with false) a setValues() that
     * fails writes nothing and throws nothing; its error is thrown by the next SpreadsheetApp.flush() or
     * getValues() on any sheet.
     */
    reportWriteErrorsLate(late = true) {
      spreadsheet.lateWriteErrors = Boolean(late);
    },
    /**
     * Adds a dropdown rule like a person using Data > Data validation: `values` allowed in rows
     * 2..last row of `column` (or `rows` rows from `row`); reject = "Reject the input".
     */
    addListValidation({ sheet = 'Items', column, row = 2, rows, values, reject = true, helpText }) {
      const target = spreadsheet.getSheetByName(sheet);
      if (!target) throw new Error(`No sheet named ${sheet}`);
      let builder = SpreadsheetApp.newDataValidation().requireValueInList(values, true).setAllowInvalid(!reject);
      if (helpText) builder = builder.setHelpText(helpText);
      const rule = builder.build();
      target.getRange(row, column, rows === undefined ? target.getMaxRows() - row + 1 : rows, 1).setDataValidation(rule);
      return rule;
    },
  };
}

/** doGet event object for the given query parameters. */
function getEvent(parameter = {}) {
  const params = Object.fromEntries(Object.entries(parameter).map(([k, v]) => [k, String(v)]));
  return {
    parameter: params,
    parameters: Object.fromEntries(Object.entries(params).map(([k, v]) => [k, [v]])),
    queryString: new URLSearchParams(params).toString(),
    contextPath: '',
    contentLength: -1,
  };
}

/** doPost event object for a raw body. */
function postEvent(contents, type = 'application/json', parameter = {}) {
  return {
    ...getEvent(parameter),
    contentLength: Buffer.byteLength(contents),
    postData: { contents, length: Buffer.byteLength(contents), type, name: 'postData' },
  };
}

module.exports = {
  loadCodeGs,
  getEvent,
  postEvent,
  DataValidation,
  FakeSpreadsheet,
  FakeSheet,
  FakeRange,
  FakeLock,
  FakeCache,
  TextOutput,
  DEFAULT_NOW,
  CODE_GS_PATH,
};
