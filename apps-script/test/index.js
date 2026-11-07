'use strict';
// `node --test apps-script/test/` hands the directory to Node, which loads it as a module (this
// index.js) instead of searching it, so this file pulls in every *.test.js next to it.
const fs = require('node:fs');
const path = require('node:path');

fs.readdirSync(__dirname)
  .filter((file) => file.endsWith('.test.js'))
  .sort()
  .forEach((file) => require(path.join(__dirname, file)));
