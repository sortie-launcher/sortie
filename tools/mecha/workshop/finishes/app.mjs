const $ = (selector) => document.querySelector(selector);
const finishes = ['plain', 'chrome', 'holo'];
const slots = ['legs', 'body', 'arms', 'head'];
const filmColors = [[100, 218, 222], [184, 157, 230], [227, 157, 181]];
const palettes = [
  { id: 'harbor', name: 'Harbor blue', base: '#dae6ed', ink: '#24394d', surface: '#f5f8fa', line: '#637d90', select: '#294d69', selectInk: '#ffffff', focus: '#294d69', description: 'Cool daylight surfaces, blue selection, dark ink. A quiet default.' },
  { id: 'plum', name: 'Plum evening', base: '#302b40', ink: '#f5eefb', surface: '#443c57', line: '#9c8eac', select: '#e5c4ee', selectInk: '#302b40', focus: '#e5c4ee', description: 'A low-light violet room, pale text and a lavender selection.' },
  { id: 'lichen', name: 'Lichen paper', base: '#e1e6d7', ink: '#293e32', surface: '#f6f7ef', line: '#647665', select: '#385342', selectInk: '#ffffff', focus: '#385342', description: 'Green-grey paper with forest ink. Warm without reducing contrast.' },
];
let previewFinish = 'plain';
let selectedPalette = palettes[0];
let ready = false;
let animating = false;
let timer = null;
let phase = 44;
let request = 0;
let pixels = null;
let platePixels = [];
const images = new Map();
const output = new Map(finishes.map((finish) => [finish, new ImageData(64, 64)]));
const contexts = new Map(finishes.map((finish) => [finish, $(`#${finish}`).getContext('2d')]));
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');

for (const palette of palettes) {
  const article = document.createElement('article');
  article.className = `palette${palette.id === selectedPalette.id ? ' selected' : ''}`;
  article.dataset.palette = palette.id;
  article.innerHTML = `<div class="launcher" style="--base:${palette.base};--ink:${palette.ink};--surface:${palette.surface};--line:${palette.line};--select:${palette.select};--select-ink:${palette.selectInk};--focus:${palette.focus}">
    <div class="launcher-top"><strong>Sortie</strong><span>Home preview</span></div>
    <div class="launcher-nav"><strong>Home</strong><span>Library</span><span>Extras</span></div>
    <div class="companion"><canvas width="64" height="64" role="img" aria-label="Rivet companion in ${palette.name} launcher palette"></canvas><p><strong>Good to see you.</strong>Your library is ready.</p></div>
    <div class="game focused"><strong>Continue playing</strong><span>Soldier Blade</span></div>
    <div class="game"><strong>Something for tonight</strong><span>Browse five suggestions</span></div>
    <p class="launcher-foot">A Open &nbsp; B Back &nbsp; · &nbsp; Layout preview</p>
    </div><button type="button" aria-pressed="${palette.id === selectedPalette.id}">Use ${palette.name.toLowerCase()} backdrop</button><p class="palette-description">${palette.description}</p>`;
  article.querySelector('button').addEventListener('click', () => {
    selectedPalette = palette;
    document.documentElement.style.setProperty('--bench', palette.base);
    document.querySelectorAll('.palette').forEach((card) => {
      const selected = card.dataset.palette === palette.id;
      card.classList.toggle('selected', selected);
      card.querySelector('button').setAttribute('aria-pressed', String(selected));
    });
  });
  $('#palettes').append(article);
}
const previewContexts = [...document.querySelectorAll('.companion canvas')].map((canvas) => canvas.getContext('2d'));
const actualContexts = new Map([...document.querySelectorAll('.actual')].map((canvas) => [canvas.dataset.finish, canvas.getContext('2d')]));

function loadImage(url) {
  if (!images.has(url)) {
    const promise = new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => resolve(image);
      image.onerror = () => reject(new Error(`Could not load ${url}`));
      image.src = url;
    });
    images.set(url, promise);
    promise.catch(() => images.delete(url));
  }
  return images.get(url);
}

async function assemble() {
  const current = ++request;
  ready = false;
  stopTimer();
  $('#export').disabled = true;
  $('#motion').disabled = true;
  const family = $('#family').value;
  const variant = $('#variant').value;
  const frame = Number($('#pose').value);
  $('#status').textContent = 'Loading the original sprite layers…';
  try {
    const sheets = await Promise.all(slots.map((slot) => loadImage(`/mecha/src/main/assets/mecha/${family}/${family}_${slot}_${variant}.png`)));
    if (current !== request) return;
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 64;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    context.imageSmoothingEnabled = false;
    for (const sheet of sheets) context.drawImage(sheet, frame * 64, 0, 64, 64, 0, 0, 64, 64);
    pixels = context.getImageData(0, 0, 64, 64);
    context.clearRect(0, 0, 64, 64);
    context.drawImage(sheets[3], frame * 64, 0, 64, 64, 0, 0, 64, 64);
    const headPixels = context.getImageData(0, 0, 64, 64).data;
    // Only midtone interior plating below the face can receive a coating.
    // Original transparent pixels, dark outlines, bright lamps and head stay exact.
    platePixels = [];
    for (let y = 26; y <= 56; y++) {
      for (let x = 1; x < 63; x++) {
        const i = (y * 64 + x) * 4;
        const [r, g, b, a] = pixels.data.subarray(i, i + 4);
        const luminance = .2126 * r + .7152 * g + .0722 * b;
        if (a === 255 && headPixels[i + 3] === 0 && luminance >= 70 && luminance <= 210 && Math.max(r, g, b) - Math.min(r, g, b) < 170) {
          platePixels.push({ i, x, y, luminance });
        }
      }
    }
    ready = true;
    $('#export').disabled = false;
    $('#motion').disabled = reducedMotion.matches;
    $('#status').textContent = `${$('#family').selectedOptions[0].text} · ${$('#variant').selectedOptions[0].text} · ${$('#pose').selectedOptions[0].text}. All three use the same four original layers.`;
    render();
    schedule();
  } catch (error) {
    if (current !== request) return;
    $('#status').textContent = `${error.message}. Serve the whole worktree root on localhost, then choose a machine to retry.`;
    for (const context of [...contexts.values(), ...actualContexts.values(), ...previewContexts]) context.clearRect(0, 0, 64, 64);
  }
}

function blend(data, i, color, amount) {
  for (let channel = 0; channel < 3; channel++) data[i + channel] = Math.round(data[i + channel] * (1 - amount) + color[channel] * amount);
}

function render() {
  if (!ready) return;
  for (const finish of finishes) output.get(finish).data.set(pixels.data);
  const chrome = output.get('chrome').data;
  const holo = output.get('holo').data;
  for (const { i, x, y, luminance } of platePixels) {
    const brushed = Math.min(238, luminance + 17 + (y % 3 === 0 ? 5 : 0));
    blend(chrome, i, [brushed - 5, brushed + 1, Math.min(255, brushed + 9)], .38);
    const diagonal = x + Math.floor(y * .55);
    const distance = Math.abs(diagonal - phase);
    if (distance <= 3) blend(chrome, i, [235, 247, 250], (4 - distance) * .10);
    // Three broad film bands; only the narrow highlight moves. No rainbow cycling.
    const film = (Math.floor((x + y) / 12) % 3);
    blend(holo, i, filmColors[film], .46);
    if (distance <= 2) blend(holo, i, [231, 249, 243], (3 - distance) * .09);
  }
  for (const finish of finishes) {
    contexts.get(finish).putImageData(output.get(finish), 0, 0);
    actualContexts.get(finish).putImageData(output.get(finish), 0, 0);
  }
  for (const context of previewContexts) context.putImageData(output.get(previewFinish), 0, 0);
}

function stopTimer() {
  clearTimeout(timer);
  timer = null;
}
function schedule() {
  stopTimer();
  if (!ready || !animating || document.hidden || reducedMotion.matches) return;
  timer = setTimeout(() => {
    timer = null;
    phase = phase >= 88 ? 12 : phase + 1;
    render();
    schedule();
  }, 125);
}
function updateMotion() {
  if (reducedMotion.matches) animating = false;
  $('#motion').disabled = !ready || reducedMotion.matches;
  $('#motion').setAttribute('aria-pressed', String(animating));
  $('#motion').textContent = animating ? 'Pause finishes' : 'Animate finishes';
  $('#motion-note').textContent = reducedMotion.matches
    ? 'Reduced motion is on. Finishes remain static; every material is still visible.'
    : 'Static by default. Optional motion runs at 8 updates/second and stops when this tab is hidden.';
  schedule();
}
$('#motion').addEventListener('click', () => { animating = !animating; updateMotion(); });
reducedMotion.addEventListener('change', updateMotion);
document.addEventListener('visibilitychange', schedule);
window.addEventListener('pagehide', stopTimer);
window.addEventListener('pageshow', schedule);
for (const id of ['family', 'variant', 'pose']) $(`#${id}`).addEventListener('change', assemble);
$('#controls').addEventListener('submit', (event) => event.preventDefault());
document.querySelectorAll('[name="preview-finish"]').forEach((radio) => radio.addEventListener('change', () => {
  previewFinish = radio.value;
  render();
}));

export function comparisonBoard() {
  if (!ready) return null;
  const board = document.createElement('canvas');
  board.width = 1056;
  board.height = 460;
  const context = board.getContext('2d');
  context.imageSmoothingEnabled = false;
  context.fillStyle = '#e6edf1';
  context.fillRect(0, 0, board.width, board.height);
  context.fillStyle = '#24394d';
  context.font = 'bold 28px sans-serif';
  context.fillText('Rivet / Material bench', 24, 40);
  context.font = '16px sans-serif';
  context.fillText(`${$('#family').selectedOptions[0].text}, set ${$('#variant').value}, ${$('#pose').selectedOptions[0].text.toLowerCase()} — offline proposals, not live prices`, 24, 68);
  const labels = ['Paint & steel / included', 'Brushed alloy / proposed 10 credits', 'Prism film / proposed 10 credits'];
  finishes.forEach((finish, index) => {
    const x = index * 344 + 16;
    context.fillStyle = selectedPalette.base;
    context.fillRect(x, 88, 336, 272);
    context.drawImage($(`#${finish}`), x + 40, 96, 256, 256);
    context.fillStyle = '#24394d';
    context.font = 'bold 16px sans-serif';
    context.fillText(labels[index], x + 8, 388);
  });
  context.font = '16px sans-serif';
  context.fillText('Materials, not power. Clear focus, contrast, palettes and reduced motion remain free.', 24, 433);
  return board;
}
$('#export').addEventListener('click', () => {
  const board = comparisonBoard();
  if (!board) return;
  board.toBlob((blob) => {
    if (!blob) { $('#status').textContent = 'PNG export failed. Try saving the comparison again.'; return; }
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `rivet-${$('#family').value}-${$('#variant').value}-finishes.png`;
    anchor.click();
    // The click has handed the blob to the download subsystem; no timer stays alive.
    queueMicrotask(() => URL.revokeObjectURL(url));
  }, 'image/png');
});
updateMotion();
assemble();
