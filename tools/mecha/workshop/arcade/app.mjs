const canvas = document.querySelector('#game');
const ctx = canvas.getContext('2d');
const action = document.querySelector('#action');
const overlay = document.querySelector('#overlay');
const headline = document.querySelector('#headline');
const screenCopy = document.querySelector('#screen-copy');
const status = document.querySelector('#status');
const scoreLabel = document.querySelector('#score');
const timeLabel = document.querySelector('#time');
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
const WIDTH = 320;
const HEIGHT = 288;
const ROUND_SECONDS = 45;
const CATCH_Y = 244;
const keys = new Set();
const sheets = [];
const colors = {
  ink: '#18232d', blue: '#274b6d', steel: '#768a91', pale: '#dce8ed',
  light: '#f4f8fa', bolt: '#f4d27c', rust: '#e2695b', green: '#8feeb8',
};
let mode = 'loading';
let frameRequest = null;
let lastTime = 0;
let elapsed = 0;
let nextDrop = .5;
let score = 0;
let bolts = 0;
let junk = 0;
let playerX = WIDTH / 2;
let pointerTarget = null;
let pointerId = null;
let moving = false;
let drops = [];
let feedback = null;

function rect(x, y, w, h, color) {
  ctx.fillStyle = color;
  ctx.fillRect(Math.round(x), Math.round(y), w, h);
}

function drawBolt(x, y) {
  x = Math.round(x);
  y = Math.round(y);
  rect(x - 5, y - 9, 10, 4, colors.ink);
  rect(x - 8, y - 5, 16, 8, colors.ink);
  rect(x - 5, y + 3, 10, 4, colors.ink);
  rect(x - 3, y + 6, 6, 7, colors.ink);
  rect(x - 4, y - 7, 8, 3, colors.bolt);
  rect(x - 6, y - 4, 12, 6, colors.bolt);
  rect(x - 4, y + 2, 8, 3, colors.bolt);
  rect(x - 1, y + 6, 2, 5, colors.bolt);
  rect(x - 3, y - 2, 6, 2, colors.ink);
}

function drawJunk(x, y) {
  x = Math.round(x);
  y = Math.round(y);
  rect(x - 6, y - 10, 12, 20, colors.ink);
  rect(x - 10, y - 6, 20, 12, colors.ink);
  rect(x - 8, y - 5, 16, 10, colors.rust);
  rect(x - 5, y - 8, 10, 16, colors.rust);
  for (let i = -4; i <= 4; i += 2) {
    rect(x + i - 1, y + i - 1, 3, 3, colors.ink);
    rect(x - i - 1, y + i - 1, 3, 3, colors.ink);
  }
}

function draw() {
  ctx.imageSmoothingEnabled = false;
  rect(0, 0, WIDTH, HEIGHT, colors.pale);
  // The bay is a still backdrop: no decorative idle animation or timers.
  for (let y = 34; y < 260; y += 32) {
    rect(12, y, 3, 3, '#a9bdc9');
    rect(305, y, 3, 3, '#a9bdc9');
  }
  rect(0, 0, WIDTH, 19, colors.ink);
  rect(0, 3, WIDTH, 9, colors.steel);
  for (let x = 8; x < WIDTH; x += 24) rect(x, 5, 12, 4, colors.light);
  rect(0, 270, WIDTH, 18, colors.ink);
  for (let x = 0; x < WIDTH; x += 24) rect(x, 274, 12, 5, colors.bolt);
  rect(0, 263, WIDTH, 5, colors.steel);
  rect(playerX - 25, 266, 50, 3, '#344650');
  if (mode === 'ready' || mode === 'loading') {
    drawBolt(51, 58);
    drawBolt(265, 163);
    drawJunk(58, 188);
  }
  for (const drop of drops) {
    if (drop.good) drawBolt(drop.x, drop.y);
    else drawJunk(drop.x, drop.y);
  }
  const spriteFrame = mode === 'ended' ? 7 :
    mode === 'running' && moving && !reducedMotion.matches ? 2 + Math.floor(elapsed * 9) % 4 : 0;
  // Each shipped 512×64 atlas is one aligned layer; preserve the app's draw order.
  for (const sheet of sheets) {
    ctx.drawImage(sheet, spriteFrame * 64, 0, 64, 64, Math.round(playerX) - 32, 207, 64, 64);
  }
  rect(playerX - 24, CATCH_Y, 48, 4, colors.ink);
  rect(playerX - 22, CATCH_Y + 4, 44, 10, colors.bolt);
  rect(playerX - 24, CATCH_Y + 4, 3, 10, colors.ink);
  rect(playerX + 21, CATCH_Y + 4, 3, 10, colors.ink);
  rect(playerX - 22, CATCH_Y + 14, 44, 3, colors.ink);
  for (let x = -14; x <= 14; x += 7) rect(playerX + x, CATCH_Y + 7, 3, 4, '#c28028');
  if (feedback && elapsed - feedback.at < .6) {
    ctx.font = 'bold 12px sans-serif';
    ctx.textAlign = 'center';
    const rise = reducedMotion.matches ? 0 : Math.round((elapsed - feedback.at) * 12);
    rect(feedback.x - 17, 192 - rise, 34, 18, colors.ink);
    ctx.fillStyle = feedback.good ? colors.green : '#f7a48a';
    ctx.fillText(feedback.good ? '+10' : '−5', Math.round(feedback.x), 205 - rise);
  }
}

function updateReadout() {
  scoreLabel.textContent = String(score);
  timeLabel.textContent = String(Math.max(0, Math.ceil(ROUND_SECONDS - elapsed)));
}

function clearInput() {
  keys.clear();
  pointerTarget = null;
  moving = false;
  if (pointerId !== null && canvas.hasPointerCapture(pointerId)) canvas.releasePointerCapture(pointerId);
  pointerId = null;
}

function stopLoop() {
  if (frameRequest !== null) cancelAnimationFrame(frameRequest);
  frameRequest = null;
  clearInput();
}

function setMessage(title, copy, announcement) {
  headline.textContent = title;
  screenCopy.textContent = copy;
  status.textContent = announcement;
  overlay.hidden = false;
}

function pause(hidden = false) {
  if (mode !== 'running') return;
  // Count the visible interval since the last frame, then freeze everything.
  advance(Math.min((performance.now() - lastTime) / 1000, ROUND_SECONDS - elapsed));
  if (mode !== 'running') return;
  mode = 'paused';
  stopLoop();
  updateReadout();
  action.textContent = 'Resume round';
  setMessage('Tray parked', `${Math.ceil(ROUND_SECONDS - elapsed)} seconds left. Nothing moves while paused.`,
    hidden ? 'Tab hidden: round paused. Resume when you are ready.' : 'Round paused. Press P, Escape or Resume round to continue.');
  draw();
}

function finish() {
  mode = 'ended';
  elapsed = ROUND_SECONDS;
  stopLoop();
  feedback = null;
  updateReadout();
  action.textContent = 'Play again';
  const summary = `${bolts} bolts caught · ${junk} pieces of junk`;
  setMessage(`${score} round points`, summary, `Round complete: ${score} points. ${bolts} bolts caught and ${junk} pieces of junk. Play again starts a fresh 45-second round.`);
  draw();
}

function start() {
  if (document.hidden) return;
  score = 0;
  bolts = 0;
  junk = 0;
  elapsed = 0;
  nextDrop = .5;
  drops = [];
  feedback = null;
  playerX = WIDTH / 2;
  run();
}

function run() {
  if (document.hidden) return;
  clearInput();
  mode = 'running';
  overlay.hidden = true;
  action.textContent = 'Pause round';
  status.textContent = 'Round running. Catch bolts; avoid crossed-out junk. Points cannot go below zero.';
  lastTime = performance.now();
  updateReadout();
  draw();
  canvas.focus({ preventScroll: true });
  frameRequest = requestAnimationFrame(tick);
}

function step(dt) {
  elapsed += dt;
  const axis = Number(keys.has('arrowright') || keys.has('d')) - Number(keys.has('arrowleft') || keys.has('a'));
  const oldX = playerX;
  if (axis) {
    pointerTarget = null;
    playerX += axis * 195 * dt;
  } else if (pointerTarget !== null) {
    const distance = pointerTarget - playerX;
    playerX += Math.sign(distance) * Math.min(Math.abs(distance), 250 * dt);
  }
  playerX = Math.max(26, Math.min(WIDTH - 26, playerX));
  moving = Math.abs(playerX - oldX) > .01;
  if (elapsed >= nextDrop) {
    drops.push({ x: 26 + Math.floor(Math.random() * 8) * 38, y: 20, good: Math.random() < .72, speed: 53 + elapsed * .65 });
    nextDrop += .65;
  }
  for (let i = drops.length - 1; i >= 0; i--) {
    const drop = drops[i];
    const previousY = drop.y;
    drop.y += drop.speed * dt;
    if (previousY + 10 < CATCH_Y && drop.y + 10 >= CATCH_Y && Math.abs(drop.x - playerX) < 28) {
      if (drop.good) { score += 10; bolts++; }
      else { score = Math.max(0, score - 5); junk++; }
      feedback = { x: playerX, at: elapsed, good: drop.good };
      drops.splice(i, 1);
    } else if (drop.y > HEIGHT + 14) drops.splice(i, 1);
  }
}

function advance(seconds) {
  // Small simulation steps prevent fast pointer movement or slow frames skipping a catch.
  let remaining = Math.max(0, Math.min(seconds, ROUND_SECONDS - elapsed));
  while (remaining > 0) {
    const dt = Math.min(remaining, 1 / 60);
    step(dt);
    remaining -= dt;
  }
  if (elapsed >= ROUND_SECONDS - .000001) finish();
}

function tick(now) {
  frameRequest = null;
  if (mode !== 'running') return;
  if (document.hidden) { pause(true); return; }
  advance((now - lastTime) / 1000);
  lastTime = now;
  if (mode !== 'running') return;
  updateReadout();
  draw();
  frameRequest = requestAnimationFrame(tick);
}

action.addEventListener('click', () => {
  if (mode === 'ready' || mode === 'ended') start();
  else if (mode === 'running') pause();
  else if (mode === 'paused') run();
  else if (mode === 'error') location.reload();
});

document.addEventListener('keydown', event => {
  if (event.target.closest('input, textarea, select, [contenteditable="true"]')) return;
  const key = event.key.toLowerCase();
  if (key === 'p' || key === 'escape') {
    if (mode !== 'running' && mode !== 'paused') return;
    event.preventDefault();
    if (event.repeat) return;
    if (mode === 'running') pause();
    else run();
  } else if (mode === 'running' && ['arrowleft', 'arrowright', 'a', 'd'].includes(key)) {
    event.preventDefault();
    keys.add(key);
  }
});
document.addEventListener('keyup', event => keys.delete(event.key.toLowerCase()));

function point(event) {
  const bounds = canvas.getBoundingClientRect();
  pointerTarget = Math.max(26, Math.min(WIDTH - 26, (event.clientX - bounds.left) * WIDTH / bounds.width));
}
canvas.addEventListener('pointerdown', event => {
  if (mode !== 'running' || pointerId !== null || (event.pointerType === 'mouse' && event.button !== 0)) return;
  event.preventDefault();
  pointerId = event.pointerId;
  canvas.setPointerCapture(pointerId);
  canvas.focus({ preventScroll: true });
  point(event);
});
canvas.addEventListener('pointermove', event => {
  if (mode === 'running' && event.pointerId === pointerId) point(event);
});
function releasePointer(event) {
  if (event.pointerId !== pointerId) return;
  pointerId = null;
  pointerTarget = null;
}
canvas.addEventListener('pointerup', releasePointer);
canvas.addEventListener('pointercancel', releasePointer);
canvas.addEventListener('lostpointercapture', releasePointer);
document.addEventListener('visibilitychange', () => {
  if (document.hidden) pause(true);
});
window.addEventListener('blur', () => pause());
window.addEventListener('pagehide', () => pause(true));
reducedMotion.addEventListener('change', () => { if (mode !== 'running') draw(); });

// Relative URLs work from either the worktree's HTTP root or a local file.
async function loadRivet() {
  try {
    const loaded = await Promise.all(['legs', 'body', 'arms', 'head'].map(slot => new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => resolve(image);
      image.onerror = () => reject(new Error(`Could not load the ${slot} layer.`));
      image.src = `../../../../mecha/src/main/assets/mecha/yard/yard_${slot}_1.png`;
    })));
    sheets.push(...loaded);
    mode = 'ready';
    action.disabled = false;
    action.textContent = 'Start 45-second round';
    status.textContent = 'Ready when you are. The round only starts when you press Start.';
    draw();
  } catch (error) {
    mode = 'error';
    action.disabled = false;
    action.textContent = 'Reload artwork';
    setMessage('Rivet needs its parts', 'The four local sprite sheets could not be loaded.', `${error.message} Open this demo from the complete Sortie worktree, then reload.`);
    draw();
  }
}
draw();
loadRivet();
