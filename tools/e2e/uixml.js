'use strict';
/**
 * uixml.js - reads a `uiautomator dump` file (the UI tree of the screen). Used by lib.sh / ui.sh, and
 * runnable on its own against a saved dump:
 *
 *   node uixml.js list  <dump.xml> [dpi]          one line per interesting node: class, label, bounds in px
 *                                                 and dp, flags (FOCUSED, checked, click, SCROLLABLE, ...)
 *   node uixml.js find  <dump.xml> <how> <query> [index]
 *        how = text | desc | any (text or content-desc), with a suffix for the kind of match:
 *              (none) exact, "-re" JavaScript regex, "-has" substring. e.g. "any-re" "^Live".
 *        Prints "<x> <y>\t<bounds>\t<label>\t<class>" for the index-th visible match (0 = first) and exits 0,
 *        or exits 1 when there is none.
 *   node uixml.js count <dump.xml> <how> <query>  number of visible matches (always exits 0)
 *   node uixml.js field <dump.xml> <label>        the text field (EditText) labelled <label> (Compose puts the
 *        label inside the field; a hint or a TextView inside its bounds counts too). Prints
 *        "<x> <y> <length> <password> <focused>\t<text>" where length counts characters (a password field
 *        shows one bullet per character), or exits 1.
 *   node uixml.js texts <dump.xml>                 every non-empty text / content-desc, one per line
 *
 * Only visible nodes match: non-empty bounds whose centre is on the screen (the root node's bounds).
 */
const fs = require('fs');

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };

function decode(value) {
  return value.replace(/&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);/g, (whole, name) => {
    if (name[0] === '#') {
      const code = name[1] === 'x' ? parseInt(name.slice(2), 16) : parseInt(name.slice(1), 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : whole;
    }
    return ENTITIES[name];
  });
}

/** Parses the dump into a tree: { attrs, children, parent, bounds:[x1,y1,x2,y2] | null }. */
function parse(xml) {
  const root = { attrs: {}, children: [], parent: null, bounds: null };
  const stack = [root];
  const all = [];
  // Attribute values never contain '"' (it is written as &quot;), so this is safe even when they hold '>'.
  const re = /<node((?:\s+[\w:.-]+="[^"]*")*)\s*(\/?)>|<\/node\s*>/g;
  let m;
  while ((m = re.exec(xml))) {
    if (m[0].startsWith('</')) {
      if (stack.length > 1) stack.pop();
      continue;
    }
    const attrs = {};
    for (const a of m[1].matchAll(/([\w:.-]+)="([^"]*)"/g)) attrs[a[1]] = decode(a[2]);
    const b = /^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$/.exec(attrs.bounds || '');
    const node = {
      attrs,
      children: [],
      parent: stack[stack.length - 1],
      bounds: b ? b.slice(1).map(Number) : null,
      order: all.length,
    };
    node.parent.children.push(node);
    all.push(node);
    if (!m[2]) stack.push(node);
  }
  if (!all.length) throw new Error('no <node> elements: not a uiautomator dump?');
  // The screen: the union of the top-level nodes' bounds (normally the first node covers the screen).
  const screen = [Infinity, Infinity, -Infinity, -Infinity];
  for (const n of root.children) {
    if (!n.bounds) continue;
    screen[0] = Math.min(screen[0], n.bounds[0]);
    screen[1] = Math.min(screen[1], n.bounds[1]);
    screen[2] = Math.max(screen[2], n.bounds[2]);
    screen[3] = Math.max(screen[3], n.bounds[3]);
  }
  return { root, all, screen };
}

function centre(n) {
  const [x1, y1, x2, y2] = n.bounds;
  return [Math.floor((x1 + x2) / 2), Math.floor((y1 + y2) / 2)];
}

function visible(n, screen) {
  if (!n.bounds) return false;
  const [x1, y1, x2, y2] = n.bounds;
  if (x2 <= x1 || y2 <= y1) return false;
  const [cx, cy] = centre(n);
  return cx >= screen[0] && cx < screen[2] && cy >= screen[1] && cy < screen[3];
}

const text = (n) => n.attrs.text || '';
const desc = (n) => n.attrs['content-desc'] || '';
const shortClass = (n) => (n.attrs.class || '').replace(/^.*\./, '');

function matcher(how, query) {
  const m = /^(text|desc|any)(?:-(re|has))?$/.exec(how);
  if (!m) throw new Error(`how must be text, desc or any, optionally with -re or -has (got "${how}")`);
  const [, field, kind] = m;
  let test;
  if (kind === 're') {
    const rx = new RegExp(query);
    test = (s) => rx.test(s);
  } else if (kind === 'has') {
    test = (s) => s.includes(query);
  } else {
    test = (s) => s === query;
  }
  // An empty label never matches (otherwise "-re ." style queries would hit every unlabelled node).
  return (n) => {
    const values = field === 'text' ? [text(n)] : field === 'desc' ? [desc(n)] : [text(n), desc(n)];
    return values.some((v) => v !== '' && test(v));
  };
}

function matches(doc, how, query) {
  const ok = matcher(how, query);
  return doc.all.filter((n) => visible(n, doc.screen) && ok(n));
}

function label(n) {
  return text(n) || desc(n);
}

function describe(n) {
  const [x, y] = centre(n);
  return `${x} ${y}\t${n.attrs.bounds}\t${JSON.stringify(label(n))}\t${shortClass(n)}`;
}

function inside(inner, outer) {
  return inner.bounds && outer.bounds &&
    inner.bounds[0] >= outer.bounds[0] && inner.bounds[1] >= outer.bounds[1] &&
    inner.bounds[2] <= outer.bounds[2] && inner.bounds[3] <= outer.bounds[3];
}

function descendants(n, out = []) {
  for (const c of n.children) {
    out.push(c);
    descendants(c, out);
  }
  return out;
}

/** The EditText labelled `want`: by hint, by a labelled node inside it, or by a label TextView within its bounds. */
function findField(doc, want) {
  const fields = doc.all.filter((n) => /EditText$/.test(n.attrs.class || '') && visible(n, doc.screen));
  const byHint = fields.find((f) => f.attrs.hint === want);
  if (byHint) return byHint;
  const byChild = fields.find((f) => descendants(f).some((d) => text(d) === want || desc(d) === want));
  if (byChild) return byChild;
  const labels = doc.all.filter((n) => text(n) === want && !/EditText$/.test(n.attrs.class || ''));
  return fields.find((f) => labels.some((l) => inside(l, f))) || null;
}

function list(doc, dpi) {
  const dp = (px) => ((px * 160) / dpi).toFixed(1);
  const lines = [];
  for (const n of doc.all) {
    if (!n.bounds) continue;
    const a = n.attrs;
    const cls = shortClass(n);
    const scrollable = a.scrollable === 'true';
    const clickable = a.clickable === 'true';
    const interesting = label(n) || scrollable || /^(EditText|Button|RadioButton|CheckBox|Switch)$/.test(cls) ||
      (clickable && cls === 'View');
    if (!interesting) continue;
    const [x1, y1, x2, y2] = n.bounds;
    const flags = [
      scrollable && 'SCROLLABLE',
      a.focused === 'true' && 'FOCUSED',
      a.password === 'true' && 'password',
      a.checked === 'true' && 'checked',
      a.selected === 'true' && 'selected',
      clickable && 'click',
      a.enabled === 'false' && 'DISABLED',
      !visible(n, doc.screen) && 'offscreen',
    ].filter(Boolean).join(',');
    const shown = JSON.stringify(label(n));
    lines.push(`${cls.padEnd(12)} ${(shown.length > 60 ? shown.slice(0, 59) + '…"' : shown).padEnd(42)} ` +
      `[${x1},${y1}][${x2},${y2}] ${dp(x2 - x1)}x${dp(y2 - y1)}dp y ${dp(y1)}..${dp(y2)}dp ${flags}`.trimEnd());
  }
  return lines;
}

function main(argv) {
  const [cmd, file, ...args] = argv;
  const usage = 'usage: node uixml.js list|find|count|field|texts <dump.xml> ...';
  if (!cmd || !file) {
    console.error(usage);
    return 2;
  }
  let doc;
  try {
    doc = parse(fs.readFileSync(file, 'utf8'));
  } catch (err) {
    console.error(`uixml: ${file}: ${err.message}`);
    return 2;
  }
  switch (cmd) {
    case 'list': {
      const dpi = Number(args[0] || 420);
      for (const line of list(doc, dpi > 0 ? dpi : 420)) console.log(line);
      return 0;
    }
    case 'find':
    case 'count': {
      const [how, query, index = '0'] = args;
      if (!how || query === undefined) {
        console.error(`usage: node uixml.js ${cmd} <dump.xml> <how> <query>${cmd === 'find' ? ' [index]' : ''}`);
        return 2;
      }
      let hits;
      try {
        hits = matches(doc, how, query);
      } catch (err) {
        console.error(`uixml: ${err.message}`);
        return 2;
      }
      if (cmd === 'count') {
        console.log(hits.length);
        return 0;
      }
      const n = hits[Number(index)];
      if (!n) return 1;
      console.log(describe(n));
      return 0;
    }
    case 'field': {
      const want = args[0];
      if (want === undefined) {
        console.error('usage: node uixml.js field <dump.xml> <label>');
        return 2;
      }
      const f = findField(doc, want);
      if (!f) return 1;
      const [x, y] = centre(f);
      const value = text(f);
      const length = [...value].length;
      console.log(`${x} ${y} ${length} ${f.attrs.password === 'true'} ${f.attrs.focused === 'true'}\t${value}`);
      return 0;
    }
    case 'texts':
      for (const n of doc.all) {
        if (!visible(n, doc.screen)) continue;
        if (text(n)) console.log(text(n));
        if (desc(n) && desc(n) !== text(n)) console.log(desc(n));
      }
      return 0;
    default:
      console.error(usage);
      return 2;
  }
}

if (require.main === module) process.exitCode = main(process.argv.slice(2));

module.exports = { parse, matches, findField, list, decode, centre, visible };
