// Checks the built site in dist/: every internal link, image and anchor resolves; GitHub links into this repository
// point at files that exist on this checkout; nothing is loaded from another host at runtime.
//
//   npm run build && npm run check-links

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, posix } from 'node:path';
import { fileURLToPath } from 'node:url';

const SITE_DIR = fileURLToPath(new URL('..', import.meta.url));
const REPO_DIR = join(SITE_DIR, '..');
const DIST = join(SITE_DIR, 'dist');
const BASE = '/sortie/';
const ORIGIN = 'https://sortie-launcher.github.io';
const SIBLINGS = ['/sortie-zines/']; // other sites on the same Pages origin that may be linked
const REPO_URL = /^https:\/\/github\.com\/sortie-launcher\/sortie\/(blob|tree|edit)\/main\/([^#?]*)/;

const htmlFiles = [];
(function walk(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) walk(path);
    else if (entry.name.endsWith('.html')) htmlFiles.push(path);
  }
})(DIST);

const idCache = new Map();
function idsOf(file) {
  if (!idCache.has(file)) {
    const html = readFileSync(file, 'utf8');
    idCache.set(file, new Set([...html.matchAll(/\sid=(?:"([^"]*)"|'([^']*)'|([^\s>]+))/g)].map((m) => m[1] ?? m[2] ?? m[3])));
  }
  return idCache.get(file);
}

/** Maps a site path (with base) to a file in dist, or null. */
function distFile(pathname) {
  if (!pathname.startsWith(BASE)) return null;
  let rel = decodeURIComponent(pathname.slice(BASE.length));
  let file = join(DIST, rel);
  if (rel === '' || rel.endsWith('/')) file = join(file, 'index.html');
  else if (existsSync(file) && statSync(file).isDirectory()) file = join(file, 'index.html');
  return existsSync(file) ? file : null;
}

const problems = [];
const external = new Set();
let checked = 0;

for (const file of htmlFiles) {
  const html = readFileSync(file, 'utf8');
  const pagePath = `${BASE}${posix.relative(DIST, file).split('\\').join('/')}`.replace(/index\.html$/, '');
  const refs = [];
  for (const m of html.matchAll(/<(a|link|img|script|source|iframe|use)\b([^>]*)>/gi)) {
    const [, tag, attrs] = m;
    for (const a of attrs.matchAll(/\s(href|src|srcset|xlink:href)=(?:"([^"]*)"|'([^']*)')/gi)) {
      const name = a[1].toLowerCase();
      const value = (a[2] ?? a[3]).replace(/&amp;/g, '&');
      const urls = name === 'srcset' ? value.split(',').map((s) => s.trim().split(/\s+/)[0]).filter(Boolean) : [value];
      for (const url of urls) refs.push({ tag: tag.toLowerCase(), name, url, rel: /\srel="([^"]*)"/.exec(attrs)?.[1] ?? '' });
    }
  }
  for (const { tag, url, rel } of refs) {
    if (!url || url.startsWith('data:') || url.startsWith('mailto:') || url.startsWith('javascript:')) continue;
    if (tag === 'link' && /\bcanonical\b/.test(rel)) continue; // metadata, not a link a reader follows
    const resolved = new URL(url, ORIGIN + pagePath);
    const where = `${posix.relative(DIST, file)}: <${tag}> ${url}`;
    if (resolved.origin !== ORIGIN || !resolved.pathname.startsWith(BASE)) {
      // Anything fetched by the browser must come from this site; plain links may leave it (e.g. the Field Notes reader).
      const loadsResource = tag !== 'a' && !(tag === 'link' && /\b(sitemap|alternate)\b/.test(rel));
      if (loadsResource) problems.push(`${where}: loads a resource from outside this site`);
      else if (resolved.origin === ORIGIN && !SIBLINGS.some((s) => resolved.pathname.startsWith(s))) {
        problems.push(`${where}: outside the ${BASE} base`);
      }
      external.add(resolved.href);
      const repo = REPO_URL.exec(resolved.href);
      if (repo && !existsSync(join(REPO_DIR, decodeURIComponent(repo[2])))) {
        problems.push(`${where}: no such file in the repository (${repo[2]})`);
      }
      continue;
    }
    checked++;
    const target = distFile(resolved.pathname);
    if (!target) {
      problems.push(`${where}: not found in dist`);
      continue;
    }
    const hash = decodeURIComponent(resolved.hash.slice(1));
    if (hash && target.endsWith('.html') && !idsOf(target).has(hash)) problems.push(`${where}: no element with id "${hash}"`);
  }
}

console.log(`check-dist: ${htmlFiles.length} pages, ${checked} internal references, ${external.size} distinct external links`);
if (process.argv.includes('--external')) for (const url of [...external].sort()) console.log(`  external: ${url}`);
if (problems.length) {
  console.error(`check-dist: ${problems.length} problem(s)\n  ${problems.join('\n  ')}`);
  process.exit(1);
}
console.log('check-dist: all internal links, images and anchors resolve');
