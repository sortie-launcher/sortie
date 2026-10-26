// Workshop-only fictional credits. This module never reads or writes app saves.
export const BETS = Object.freeze([1, 3, 10]);

export const REWARDS = Object.freeze([
  Object.freeze({
    id: 'courier_head_2_plain',
    name: 'Route cap',
    cost: 4,
    tier: 1,
    asset: '/mecha/src/main/assets/mecha/courier/courier_head_2.png',
    finish: 'plain',
  }),
  Object.freeze({
    id: 'survey_head_2_chrome',
    name: 'Chrome twin-lens scope',
    cost: 6,
    tier: 3,
    asset: '/mecha/src/main/assets/mecha/survey/survey_head_2.png',
    finish: 'chrome',
  }),
  Object.freeze({
    id: 'survey_head_1_holo',
    name: 'Holographic dish crown',
    cost: 10,
    tier: 10,
    asset: '/mecha/src/main/assets/mecha/survey/survey_head_1.png',
    finish: 'holo',
  }),
]);

// Three independent uniform five-symbol reels give 5^3 = 125 ordered results.
// Triples: 5. Exact pairs: 5 matching symbols * 4 others * 3 positions = 60.
// Misses: 5 * 4 * 3 = 60. A triple is NOT also a pair.
// Base expected credits = (60*b + 5*3*b)/125 = 3*b/5.
// Duplicate expected credits = (75*b + 5*directCost)/125.
// A first reward is a cosmetic, not spendable credit. Its direct-price valuation
// equals the duplicate conversion, but must not be called a cash payout.
// Exact fractions below avoid presenting rounded probabilities as guarantees.
export const PAYTABLE = Object.freeze({
  reelCount: 3,
  symbolCount: 5,
  outcomes: 125,
  miss: Object.freeze({ outcomes: 60, probability: 0.48, payoutMultiplier: 0 }),
  pair: Object.freeze({ outcomes: 60, probability: 0.48, payoutMultiplier: 1 }),
  triple: Object.freeze({ outcomes: 5, probability: 0.04, payoutMultiplier: 3 }),
  tiers: Object.freeze(REWARDS.map(({ tier, cost, id }) => Object.freeze({
    bet: tier,
    rewardId: id,
    directCost: cost,
    // First-win inventory state: gross credit return 60%; net loss 40% of bet.
    unownedCreditReturn: Object.freeze({ numerator: 3 * tier, denominator: 5 }),
    unownedNetCreditChange: Object.freeze({ numerator: -2 * tier, denominator: 5 }),
    // Owned-state gross return ratios are exactly 76%, 68%, 64% for 1,3,10.
    ownedCreditReturn: Object.freeze({ numerator: 15 * tier + cost, denominator: 25 }),
    ownedNetCreditChange: Object.freeze({ numerator: cost - 10 * tier, denominator: 25 }),
    ownedReturnRatio: Object.freeze({ numerator: 15 * tier + cost, denominator: 25 * tier }),
  }))),
});

function requireCounter(value, name) {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new RangeError(`${name} must be a nonnegative safe integer.`);
  }
}

function validateState(state) {
  if (state === null || typeof state !== 'object' || Array.isArray(state)) {
    throw new TypeError('State must be an object.');
  }
  requireCounter(state.credits, 'Credits');
  requireCounter(state.spins, 'Spins');
  if (!Array.isArray(state.owned)) {
    throw new TypeError('Owned rewards must be an array.');
  }
  const seen = new Set();
  // for...of also rejects sparse entries, rather than silently skipping holes.
  for (const id of state.owned) {
    if (typeof id !== 'string' || !REWARDS.some(reward => reward.id === id)) {
      throw new RangeError('Owned rewards contain an unknown reward ID.');
    }
    if (seen.has(id)) throw new RangeError('Owned rewards contain a duplicate ID.');
    seen.add(id);
  }
}

function makeState(credits, owned, spins) {
  requireCounter(credits, 'Resulting credits');
  requireCounter(spins, 'Resulting spins');
  return Object.freeze({ credits, owned: Object.freeze(owned), spins });
}

export function createState() {
  return makeState(30, [], 0);
}

/**
 * Resolve one spin atomically; caller supplies a uniform RNG returning [0, 1).
 * Throws on malformed state, invalid/insufficient bet, RNG or integer overflow.
 * Never mutates the input. Exactly three RNG calls for a successful spin.
 * payout excludes duplicateCredit; reward identifies either a new or duplicate
 * triple reward. Returned state already includes every debit, payout and grant.
 */
export function spin(state, bet, rng) {
  validateState(state);
  if (!BETS.includes(bet)) throw new RangeError('Bet must be 1, 3 or 10.');
  if (state.credits < bet) throw new RangeError('Insufficient demo credits.');
  if (state.spins === Number.MAX_SAFE_INTEGER) throw new RangeError('Spin counter is full.');
  if (typeof rng !== 'function') throw new TypeError('RNG must be a function.');

  // Snapshot before invoking caller code; no intermediate debit is exposed.
  const credits = state.credits;
  const spins = state.spins;
  const owned = [...state.owned];
  const reels = [];
  for (let index = 0; index < PAYTABLE.reelCount; index += 1) {
    const value = rng();
    if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value >= 1) {
      throw new RangeError('RNG must return a finite number in [0, 1).');
    }
    reels.push(Math.floor(value * PAYTABLE.symbolCount));
  }

  const [a, b, c] = reels;
  const kind = a === b && b === c ? 'triple'
    : a === b || a === c || b === c ? 'pair' : 'miss';
  const payout = PAYTABLE[kind].payoutMultiplier * bet;
  const reward = kind === 'triple' ? REWARDS.find(item => item.tier === bet) : null;
  let duplicateCredit = 0;
  if (reward !== null) {
    if (owned.includes(reward.id)) duplicateCredit = reward.cost;
    else owned.push(reward.id);
  }
  // Check each addition before it happens: unsafe intermediate arithmetic can
  // round back into an apparently safe final integer near MAX_SAFE_INTEGER.
  const debited = credits - bet;
  const credited = payout + duplicateCredit;
  if (debited > Number.MAX_SAFE_INTEGER - credited) {
    throw new RangeError('Credit balance would exceed the safe integer limit.');
  }
  return Object.freeze({
    state: makeState(debited + credited, owned, spins + 1),
    reels: Object.freeze(reels),
    kind,
    payout,
    reward,
    duplicateCredit,
  });
}

/** Buy a specified unowned cosmetic directly; throws without modifying state. */
export function buy(state, rewardId) {
  validateState(state);
  const reward = REWARDS.find(item => item.id === rewardId);
  if (!reward) throw new RangeError('Unknown reward ID.');
  if (state.owned.includes(reward.id)) throw new RangeError('Reward is already owned.');
  if (state.credits < reward.cost) throw new RangeError('Insufficient demo credits.');
  return makeState(state.credits - reward.cost, [...state.owned, reward.id], state.spins);
}
