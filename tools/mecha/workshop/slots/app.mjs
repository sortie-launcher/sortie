import { BETS, REWARDS, createState, spin, buy } from './engine.mjs';

const KEY = 'rivet-workshop-salvage-v1';
const $ = (id) => document.getElementById(id);
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
const SYMBOLS = ['Rivet', 'Cog', 'Coil', 'Bolt', 'Lamp'];
// Original 12-pixel workshop stamps, not borrowed game symbols.
const STAMPS = [
  ['....####....','...######...','..########..','..##....##..','..##....##..','..########..','...######...','....####....','....####....','....####....','...######...','............'],
  ['....####....','..#.####.#..','.##########.','..###..###..','####....####','###......###','###......###','####....####','..###..###..','.##########.','..#.####.#..','....####....'],
  ['............','..########..','.##......##.','.##.####.##.','.##.#..#.##.','.##.#..#.##.','.##.#..#.##.','.##.####.##.','.##......##.','..########..','....#..#....','....#..#....'],
  ['......###...','.....###....','....###.....','...###......','..########..','..########..','......###...','.....###....','....###.....','...###......','..###.......','............'],
  ['....####....','..########..','..##....##..','.##......##.','.##......##.','.##......##.','..##....##..','...######...','....####....','....####....','...######...','............'],
];
const FINISHES = {
  plain: 'Original coral enamel. A courier’s offset cap and warm sorting lamp.',
  chrome: 'Polished silver panels with hard, bright glints on the twin-lens scope.',
  holo: 'Prismatic bands and tiny diffraction glints across the dish crown.',
};
let snapshot;
let bet = BETS[0];
let busy = false;
let animating = false;
let storageBlocked = false;
let raf = 0;
let elapsed = 0;
let lastTime = null;
const spriteCache = new Map();
const randomWord = new Uint32Array(1);

function rng() {
  crypto.getRandomValues(randomWord);
  return randomWord[0] / 4294967296;
}

function validateSnapshot(value) {
  const state = value?.state;
  if (value?.version !== 1 || !state || !Number.isSafeInteger(state.credits) || state.credits < 0 ||
      !Number.isSafeInteger(state.spins) || state.spins < 0 || !Array.isArray(state.owned) ||
      new Set(state.owned).size !== state.owned.length || state.owned.some((id) => !REWARDS.some((r) => r.id === id))) {
    throw new Error('The saved demo cannot be read. Reset this demo to start again; no Sortie data is involved.');
  }
  const result = value.lastResult;
  if (result !== null && (!result || !['spin', 'buy'].includes(result.action) ||
      (result.action === 'buy' && !REWARDS.some((r) => r.id === result.rewardId)) ||
      (result.action === 'spin' && (!BETS.includes(result.bet) || !Array.isArray(result.reels) || result.reels.length !== 3 ||
      result.reels.some((r) => !Number.isInteger(r) || r < 0 || r > 4) || !['miss', 'pair', 'triple'].includes(result.kind) ||
      !Number.isSafeInteger(result.payout) || result.payout < 0 || !Number.isSafeInteger(result.duplicateCredit) || result.duplicateCredit < 0 ||
      (result.rewardId !== null && !REWARDS.some((r) => r.id === result.rewardId)))))) {
    throw new Error('The saved result cannot be read. Reset the cabinet demo to continue.');
  }
  return value;
}

function load() {
  const saved = localStorage.getItem(KEY);
  return saved ? validateSnapshot(JSON.parse(saved)) : { version: 1, state: createState(), lastResult: null };
}

function commit(next) {
  // One atomic storage replacement contains both the charged wallet and the resolved receipt.
  // Never animate, reveal, or mutate the in-memory wallet before this succeeds.
  localStorage.setItem(KEY, JSON.stringify(next));
  snapshot = next;
}

function showError(error) {
  $('save-status').textContent = error.message || 'The action could not be saved. No new credits were spent.';
  $('save-status').classList.add('error');
}

function drawReels(reels) {
  document.querySelectorAll('.reel').forEach((reel, index) => {
    const symbol = reels[index];
    const ctx = reel.querySelector('canvas').getContext('2d');
    ctx.clearRect(0, 0, 32, 32);
    STAMPS[symbol].forEach((row, y) => [...row].forEach((pixel, x) => {
      if (pixel !== '#') return;
      ctx.fillStyle = '#182f3b';
      ctx.fillRect(4 + x * 2, 4 + y * 2, 2, 2);
      if (y < 5) { ctx.fillStyle = '#296968'; ctx.fillRect(4 + x * 2, 4 + y * 2, 2, 1); }
    }));
    reel.querySelector('span').textContent = SYMBOLS[symbol];
  });
  $('reel-box').setAttribute('aria-label', `Reels: ${reels.map((r) => SYMBOLS[r]).join(', ')}`);
}

async function partCanvas(reward) {
  if (!spriteCache.has(reward.id)) {
    spriteCache.set(reward.id, new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => {
        const source = document.createElement('canvas');
        source.width = source.height = 64;
        const ctx = source.getContext('2d', { willReadFrequently: true });
        ctx.drawImage(img, 0, 0, 64, 64, 0, 0, 64, 64);
        const pixels = ctx.getImageData(0, 0, 64, 64);
        let left = 64, top = 64, right = 0, bottom = 0;
        for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) {
          const i = (y * 64 + x) * 4;
          if (!pixels.data[i + 3]) continue;
          left = Math.min(left, x); top = Math.min(top, y); right = Math.max(right, x); bottom = Math.max(bottom, y);
          const lum = pixels.data[i] * .21 + pixels.data[i + 1] * .72 + pixels.data[i + 2] * .07;
          // Preserve neutral outlines and dark mechanical detail; finish only visible shell pixels.
          if (lum < 80 || reward.finish === 'plain') continue;
          let color;
          if (reward.finish === 'chrome') {
            const value = Math.min(246, Math.round(105 + lum * .52 + ((x + y) % 11 < 2 ? 45 : 0)));
            color = [value - 9, value, Math.min(255, value + 7)];
          } else {
            const ramp = [[128, 215, 212], [158, 175, 235], [224, 159, 216], [245, 207, 152]];
            color = (x * 3 + y * 7) % 37 === 0 ? [250, 251, 235] : ramp[Math.floor((x + y) / 4) % ramp.length];
          }
          pixels.data.set(color, i);
        }
        ctx.putImageData(pixels, 0, 0);
        resolve({ source, left, top, width: right - left + 1, height: bottom - top + 1 });
      };
      img.onerror = () => reject(new Error(`Artwork unavailable for ${reward.name}`));
      img.src = reward.asset;
    }));
  }
  const { source, left, top, width, height } = await spriteCache.get(reward.id);
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 64;
  canvas.setAttribute('role', 'img');
  canvas.setAttribute('aria-label', `${reward.name}, ${reward.finish} finish preview`);
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;
  const scale = Math.max(1, Math.floor(54 / Math.max(width, height)));
  ctx.drawImage(source, left, top, width, height, Math.floor((64 - width * scale) / 2), Math.floor((64 - height * scale) / 2), width * scale, height * scale);
  return canvas;
}

function addArtwork(target, reward) {
  partCanvas(reward).then((canvas) => { if (target.isConnected) target.prepend(canvas); }).catch((error) => {
    const note = document.createElement('span'); note.className = 'art-error'; note.textContent = error.message;
    if (target.isConnected) target.prepend(note);
  });
}

function setupShelf() {
  for (const reward of REWARDS) {
    const card = document.createElement('article'); card.className = 'reward-card';
    const info = document.createElement('div');
    const heading = document.createElement('h3'); heading.textContent = reward.name;
    const description = document.createElement('p'); description.textContent = FINISHES[reward.finish];
    const purchase = document.createElement('button'); purchase.type = 'button'; purchase.dataset.reward = reward.id;
    purchase.addEventListener('click', () => transact('buy', reward.id));
    const tier = document.createElement('span'); tier.className = 'finish-label'; tier.textContent = `Part + ${reward.finish} finish · stake ${reward.tier} reward`;
    info.append(heading, description, purchase, tier); card.append(info); $('rewards').append(card); addArtwork(card, reward);
  }
  for (const amount of BETS) {
    const label = document.createElement('label'); label.className = 'bet';
    const input = document.createElement('input'); input.type = 'radio'; input.name = 'stake'; input.value = amount; input.checked = amount === bet;
    input.addEventListener('change', () => { bet = amount; updateControls(); });
    const span = document.createElement('span'); span.textContent = `${amount} ${amount === 1 ? 'credit' : 'credits'}`;
    label.append(input, span); document.querySelector('.bet-row').append(label);
  }
}

function updateControls() {
  const state = snapshot.state;
  $('balance').textContent = state.credits;
  $('spins').textContent = state.spins;
  $('stake').textContent = bet;
  $('spin').querySelector('span:last-child').innerHTML = `Spin for <span id="stake">${bet}</span> ${bet === 1 ? 'credit' : 'credits'}`;
  $('spin').disabled = busy || storageBlocked || state.credits < bet;
  $('bets').disabled = busy || storageBlocked;
  $('reset').disabled = busy;
  const reward = REWARDS.find((r) => r.tier === bet);
  $('stake-note').textContent = state.credits < bet ? 'Not enough demo credits for this stake. Choose a smaller stake or reset the demo.' : `Triple reward: ${reward.name}. Same 4% chance at every stake.`;
  document.querySelectorAll('[data-reward]').forEach((button) => {
    const item = REWARDS.find((r) => r.id === button.dataset.reward);
    const owned = state.owned.includes(item.id);
    button.textContent = owned ? 'In your collection' : `Buy for ${item.cost} credits`;
    button.setAttribute('aria-label', owned ? `${item.name}, in your collection` : `Buy ${item.name} for ${item.cost} credits`);
    button.disabled = busy || storageBlocked || owned || state.credits < item.cost;
  });
  $('inventory-note').textContent = state.owned.length ? `${state.owned.length} of ${REWARDS.length} part + finish combinations collected in this demo.` : 'Your demo collection is empty.';
}

function reveal() {
  cancelAnimationFrame(raf); raf = 0; busy = false; animating = false; lastTime = null;
  document.querySelector('.cabinet').classList.remove('spinning');
  $('reel-box').removeAttribute('aria-busy');
  const result = snapshot.lastResult;
  $('result-reward').replaceChildren();
  if (!result) {
    drawReels([0, 1, 2]);
    $('result-title').textContent = 'Ready when you are';
    $('result-message').textContent = 'Three matching symbols deliver a part + finish. Or choose exactly what you want from the shelf.';
  } else if (result.action === 'buy') {
    drawReels([0, 1, 2]);
    const reward = REWARDS.find((r) => r.id === result.rewardId);
    $('result-title').textContent = 'Straight from the shelf';
    $('result-message').textContent = `${reward.cost} credits spent. ${reward.name} is now in your demo collection.`;
    rewardReceipt(reward);
  } else {
    drawReels(result.reels);
    const reward = REWARDS.find((r) => r.id === result.rewardId);
    $('result-title').textContent = { miss: 'No match this time', pair: 'Two match. Stake returned.', triple: 'Three match. Delivery!' }[result.kind];
    const net = result.payout + result.duplicateCredit - result.bet;
    $('result-message').textContent = `Stake ${result.bet}; returned ${result.payout + result.duplicateCredit} credits. Net ${net > 0 ? '+' : ''}${net}.` +
      (reward ? (result.duplicateCredit ? ` You already own ${reward.name}: ${result.duplicateCredit} of the returned credits are its duplicate conversion.` : ` ${reward.name} added to your demo collection.`) : '');
    if (reward) rewardReceipt(reward);
  }
  updateControls();
}

function rewardReceipt(reward) {
  const receipt = document.createElement('div'); receipt.className = 'reward-receipt';
  const label = document.createElement('span'); label.textContent = `${reward.name} · ${reward.finish}`;
  receipt.append(label); $('result-reward').append(receipt); addArtwork(receipt, reward);
}

function tick(now) {
  if (document.hidden || !animating) return;
  if (lastTime !== null) elapsed += now - lastTime;
  lastTime = now;
  if (elapsed >= 850 || reducedMotion.matches) { reveal(); return; }
  raf = requestAnimationFrame(tick);
}

async function transact(action, rewardId) {
  if (busy || storageBlocked) return;
  busy = true; updateControls();
  const execute = () => {
    const latest = load();
    if (action === 'buy') {
      commit({ version: 1, state: buy(latest.state, rewardId), lastResult: { action, rewardId } });
    } else {
      const result = spin(latest.state, bet, rng);
      commit({ version: 1, state: result.state, lastResult: { action, bet, reels: result.reels, kind: result.kind, payout: result.payout, duplicateCredit: result.duplicateCredit, rewardId: result.reward?.id ?? null } });
    }
  };
  try {
    // Web Locks also serialize writes from multiple open demo tabs when supported.
    if (navigator.locks) await navigator.locks.request(KEY, execute); else execute();
    $('save-status').classList.remove('error');
    $('save-status').textContent = 'Saved locally. Reloading will show this result, not pay it again.';
    if (action === 'buy' || reducedMotion.matches) { reveal(); return; }
    $('result-title').textContent = 'Opening the salvage hatch…';
    $('result-message').textContent = 'Your result is already resolved and saved.';
    $('result-reward').replaceChildren();
    document.querySelector('.cabinet').classList.add('spinning');
    $('reel-box').setAttribute('aria-busy', 'true');
    animating = true;
    elapsed = 0; lastTime = null;
    if (!document.hidden) raf = requestAnimationFrame(tick);
  } catch (error) {
    busy = false; updateControls(); showError(error);
  }
}

$('spin').addEventListener('click', () => transact('spin'));
// Holding an activation key must not turn a manual cabinet into autoplay.
document.addEventListener('keydown', (event) => {
  if (event.repeat && (event.key === 'Enter' || event.key === ' ') && event.target.closest('button')) event.preventDefault();
});
document.addEventListener('visibilitychange', () => {
  document.body.classList.toggle('page-hidden', document.hidden);
  if (document.hidden) { cancelAnimationFrame(raf); raf = 0; lastTime = null; }
  else if (animating) { lastTime = null; raf = requestAnimationFrame(tick); }
});
reducedMotion.addEventListener('change', () => { if (animating && reducedMotion.matches) reveal(); });
window.addEventListener('storage', (event) => {
  if (event.key !== KEY && event.key !== null) return;
  // A queued transaction reads the newest snapshot inside its lock. Do not unlock its UI early.
  if (busy && !animating) return;
  try { snapshot = load(); storageBlocked = false; reveal(); $('save-status').textContent = 'Updated from another demo tab.'; }
  catch (error) { storageBlocked = true; showError(error); updateControls(); }
});
$('reset').addEventListener('click', () => { $('reset-confirm').hidden = false; $('reset-no').focus(); });
$('reset-no').addEventListener('click', () => { $('reset-confirm').hidden = true; $('reset').focus(); });
$('reset-yes').addEventListener('click', async () => {
  if (busy) return;
  busy = true; updateControls();
  try {
    const reset = () => commit({ version: 1, state: createState(), lastResult: null });
    if (navigator.locks) await navigator.locks.request(KEY, reset); else reset();
    storageBlocked = false; $('reset-confirm').hidden = true;
    $('save-status').classList.remove('error'); $('save-status').textContent = 'Demo reset. Your Sortie credits and parts are untouched.';
    reveal(); $('reset').focus();
  } catch (error) { busy = false; showError(error); updateControls(); }
});

setupShelf();
try { snapshot = load(); }
catch (error) { snapshot = { version: 1, state: createState(), lastResult: null }; storageBlocked = true; showError(error); }
reveal();
