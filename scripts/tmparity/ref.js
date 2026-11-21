// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
//
// Tokenizes every file of a corpus with vscode-textmate, the tokenizer VS Code itself uses, for Tedit's
// `-e only tmparity` check. Run by prepare.sh; see there.
//
// usage: node ref.js <modules dir> <extensions dir> <out dir>
//   <modules dir>    holds vscode-textmate/ and vscode-oniguruma/, taken from the local VS Code install
//   <extensions dir> VS Code's extensions: */syntaxes and */package.json (for injectTo)
//   <out dir>        has corpus.tsv (scope, index, name) and files/<index>; gets ref/<index>.txt and stacks.txt
//
// ref/<index>.txt has one line per text line: "-" for a line over 20,000 characters (editors do not tokenize those),
// else "start:stack,start:stack,..." where stack is a line number in stacks.txt, which lists each scope stack once,
// outermost scope first, separated by spaces.
'use strict';
const fs = require('fs');
const path = require('path');
const [modsDir, extDir, outDir] = process.argv.slice(2);
if (!outDir) { console.error('usage: node ref.js <modules dir> <extensions dir> <out dir>'); process.exit(2); }
const tm = require(path.join(modsDir, 'vscode-textmate/release/main.js'));
const onig = require(path.join(modsDir, 'vscode-oniguruma/release/main.js'));
const wasm = fs.readFileSync(path.join(modsDir, 'vscode-oniguruma/release/onig.wasm')).buffer;

const byScope = {};
const injectTo = {};
for (const ext of fs.readdirSync(extDir)) {
  const dir = path.join(extDir, ext);
  const syntaxes = path.join(dir, 'syntaxes');
  if (fs.existsSync(syntaxes)) {
    for (const f of fs.readdirSync(syntaxes)) {
      if (!f.endsWith('.json') && !f.endsWith('.tmLanguage')) continue;
      const p = path.join(syntaxes, f);
      try {
        const g = tm.parseRawGrammar(fs.readFileSync(p, 'utf8'), p);
        if (g.scopeName && !byScope[g.scopeName]) byScope[g.scopeName] = g;
      } catch (e) { /* a grammar VS Code cannot read either */ }
    }
  }
  try {
    const pj = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8'));
    for (const g of (pj.contributes && pj.contributes.grammars) || []) {
      for (const t of g.injectTo || []) (injectTo[t] = injectTo[t] || []).push(g.scopeName);
    }
  } catch (e) { /* no package.json */ }
}
// As VS Code looks them up: an injection for text.html also reaches text.html.basic.
const getInjections = scope => {
  const parts = scope.split('.');
  let out = [];
  for (let i = 1; i <= parts.length; i++) out = out.concat(injectTo[parts.slice(0, i).join('.')] || []);
  return out;
};

(async () => {
  await onig.loadWASM(wasm);
  const registry = new tm.Registry({
    onigLib: Promise.resolve({ createOnigScanner: s => new onig.OnigScanner(s), createOnigString: s => new onig.OnigString(s) }),
    loadGrammar: async s => byScope[s] || null,
    getInjections,
  });
  const stacks = new Map();
  fs.mkdirSync(path.join(outDir, 'ref'), { recursive: true });
  const rows = fs.readFileSync(path.join(outDir, 'corpus.tsv'), 'utf8').split('\n').filter(Boolean);
  for (const row of rows) {
    const [scope, index, name] = row.split('\t');
    const grammar = await registry.loadGrammar(scope);
    if (!grammar) { console.log(`skip ${name}: no grammar for ${scope}`); continue; }
    const lines = fs.readFileSync(path.join(outDir, 'files', index), 'utf8').split(/\r?\n/);
    let state = tm.INITIAL;
    const out = [];
    for (const line of lines) {
      if (line.length > 20000) { out.push('-'); continue; }
      const r = grammar.tokenizeLine(line, state, 0);
      out.push(r.tokens.map(t => {
        const key = t.scopes.join(' ');
        if (!stacks.has(key)) stacks.set(key, stacks.size);
        return `${t.startIndex}:${stacks.get(key)}`;
      }).join(','));
      state = r.ruleStack;
    }
    fs.writeFileSync(path.join(outDir, 'ref', `${index}.txt`), out.join('\n') + '\n');
    console.log(`${name} (${scope}): ${lines.length} lines`);
  }
  fs.writeFileSync(path.join(outDir, 'stacks.txt'), [...stacks.keys()].join('\n') + '\n');
})();
