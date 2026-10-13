const svg       = document.getElementById('bunny');
const rig       = document.getElementById('rig');
const earL      = document.querySelector('.ear-l');
const earR      = document.querySelector('.ear-r');
const mascot    = svg.closest('.mascot');
const urlInput  = document.getElementById('url');

const REDUCED = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

const TRAVEL_X       = 4.6;
const TRAVEL_Y       = 8.5;
const GAZE_TRAVEL_X  = 6;
const GAZE_TRAVEL_Y  = 9;
const MOUSE_RANGE_X  = 600;
const MOUSE_RANGE_Y  = 420;
const GAZE_RANGE     = 80;

const GAZE_ORIGIN = { x: 100, y: 144 };
const PILL_W      = 13;
const EYE_SPACING = 48;

const EYE_GAP_REST   = EYE_SPACING - PILL_W;
const EYE_GAP_MIN    = 12;
const NEAR_EXTRA     = 2.2;
const CONVERGE_CURVE = 1.15;

const VERT_SQUASH  = 0.07;
const VERT_SHIFT_X = 0.5;

const FLOAT_AMP      = 1.15;
const FLOAT_PERIOD_X = 5400;
const FLOAT_PERIOD_Y = 4100;
const FLOAT_PHASE_Y  = 1.2;

const LEAN_OFFSET = 0.30;
const LEAN_LAG    = 1.60;
const LEAN_MAX    = 10;

const HEAD_LEAN_X = 0.30;
const HEAD_LEAN_Y = 0.42;
const HEAD_TILT   = 0.40;

const EAR_LEAN    = 1.60;
const EAR_DRIFT_X = 0.95;
const EAR_DRIFT_Y = 0.55;
const EAR_SWAY    = 2.00;

const STIFF = 0.10;
const DAMP  = 0.76;

const SACCADE_AMP = 0.85;
const SACCADE_MIN = 700;
const SACCADE_MAX = 2100;

const IDEA_MS  = 2200;

const BLINK_MIN = 2200;
const BLINK_MAX = 6400;

const THINK_DOT_X0 = 178;
const THINK_DOT_X1 = 208;
const THINK_DOT_Y  = 22;
const THINK_SWEEP  = 1600;

const SLEEP_MS       = 30000;
const POKE_WINDOW    = 1600;
const POKE_DIZZY     = 4;
const DIZZY_MS       = 2600;
const TYPING_MS      = 2200;
const DROOP_EAR_ROT  = 13;

function savedFollow() {
  try { return localStorage.getItem('bunnyFollow') === '1'; } catch (e) { return false; }
}

let state      = 'normal';
let mode       = savedFollow() ? 'mouse' : 'wander';
let thinkTimer = null;
let ideaTimer  = null;
let blinkTimer = null;

let pointer    = null;
let wander     = { x: 0, y: 0 };
let nextWander = 0;

let saccade     = { x: 0, y: 0 };
let nextSaccade = 0;

let driveTX = 0, driveTY = 0;
let gazeNY = 0;

let sleeping     = false;
let dizzy        = false;
let dizzyTimer   = null;
let dizzyStart   = 0;
let lastActivity = performance.now();
let typingUntil  = 0;
let trackEl      = null;
let trackPoint   = null;
let pokes        = [];
let droop        = 0;
let lastMove     = null;

const eyes = [
  { wrap: document.querySelector('#eyeL .pupil-wrap'),
    baseX: 76, baseY: 144,
    x: 0, y: 0, vx: 0, vy: 0, tx: 0, ty: 0, rot: 0, vrot: 0, sy: 1, vsy: 0, ty2: 1 },
  { wrap: document.querySelector('#eyeR .pupil-wrap'),
    baseX: 124, baseY: 144,
    x: 0, y: 0, vx: 0, vy: 0, tx: 0, ty: 0, rot: 0, vrot: 0, sy: 1, vsy: 0, ty2: 1 }
];

function toSvgPoint(clientX, clientY) {
  const m = svg.getScreenCTM();
  if (!m) return null;
  const p = new DOMPoint(clientX, clientY).matrixTransform(m.inverse());
  return { x: p.x, y: p.y };
}

window.addEventListener('pointermove', function (e) {
  const p = toSvgPoint(e.clientX, e.clientY);
  if (p) pointer = p;
  if (!lastMove || Math.hypot(e.clientX - lastMove.x, e.clientY - lastMove.y) > 3) {
    lastMove = { x: e.clientX, y: e.clientY };
    activity();
  }
}, { passive: true });

document.addEventListener('mouseleave', function () { pointer = null; });
window.addEventListener('blur', function () { pointer = null; });

function gazeUnit(px, py, rangeX, rangeY) {
  let nx = (px - GAZE_ORIGIN.x) / rangeX;
  let ny = (py - GAZE_ORIGIN.y) / rangeY;
  const m = Math.hypot(nx, ny);
  if (m > 1) { nx /= m; ny /= m; }
  return { x: nx, y: ny };
}

function floatOffset(now) {
  if (REDUCED) return { x: 0, y: 0 };
  return {
    x: Math.sin((now / FLOAT_PERIOD_X) * Math.PI * 2) * FLOAT_AMP,
    y: Math.sin((now / FLOAT_PERIOD_Y) * Math.PI * 2 + FLOAT_PHASE_Y) *
       FLOAT_AMP * 0.65
  };
}

function thinkFocus(now) {
  const t = (now % THINK_SWEEP) / THINK_SWEEP;
  const s = 0.5 - 0.5 * Math.cos(t * Math.PI * 2);
  return {
    x: THINK_DOT_X0 + (THINK_DOT_X1 - THINK_DOT_X0) * s,
    y: THINK_DOT_Y
  };
}

function updateSaccade(now) {
  if (now >= nextSaccade) {
    const a = Math.random() * Math.PI * 2;
    const r = SACCADE_AMP * Math.sqrt(Math.random());
    saccade.x = Math.cos(a) * r;
    saccade.y = Math.sin(a) * r;
    nextSaccade = now + SACCADE_MIN + Math.random() * (SACCADE_MAX - SACCADE_MIN);
  }
}

let inputPoint = null;

function updateInputPoint() {
  if (!urlInput) { inputPoint = null; return; }
  const r = urlInput.getBoundingClientRect();
  const len = Math.min(urlInput.value.length, 40) / 40;
  inputPoint = toSvgPoint(r.left + 24 + (r.width - 48) * len, r.top + r.height / 2);
}

function updateTrackPoint() {
  if (!trackEl) { trackPoint = null; return; }
  if (!trackEl.isConnected) { trackEl = null; trackPoint = null; return; }
  const r = trackEl.getBoundingClientRect();
  const bar = trackEl.parentElement ? trackEl.parentElement.getBoundingClientRect() : r;
  if (!r.width && !bar.width) { trackPoint = null; return; }
  const x = Math.min(bar.right, Math.max(bar.left, r.right));
  trackPoint = toSvgPoint(x, bar.top + bar.height / 2);
}

function updateTargets(now) {
  updateSaccade(now);

  let nx = 0, ny = 0;
  let travelX = TRAVEL_X, travelY = TRAVEL_Y;

  if (state === 'thinking') {
    const f = thinkFocus(now);
    const g = gazeUnit(f.x, f.y, GAZE_RANGE, GAZE_RANGE);
    nx = g.x; ny = g.y;
    travelX = GAZE_TRAVEL_X;
    travelY = GAZE_TRAVEL_Y;
  } else if (trackPoint) {
    const g = gazeUnit(trackPoint.x, trackPoint.y, 300, 300);
    nx = g.x; ny = g.y;
    travelX = GAZE_TRAVEL_X;
    travelY = GAZE_TRAVEL_Y;
  } else if (now < typingUntil && inputPoint) {
    const g = gazeUnit(inputPoint.x, inputPoint.y, 240, 150);
    nx = g.x; ny = Math.max(0.55, g.y);
    travelX = GAZE_TRAVEL_X;
    travelY = GAZE_TRAVEL_Y;
  } else if (mode === 'mouse') {
    if (pointer) {
      const g = gazeUnit(pointer.x, pointer.y, MOUSE_RANGE_X, MOUSE_RANGE_Y);
      nx = g.x; ny = g.y;
    }
  } else {
    if (now >= nextWander) {
      const a = Math.random() * Math.PI * 2;
      const r = Math.sqrt(Math.random());
      wander.x = Math.cos(a) * r;
      wander.y = Math.sin(a) * r;
      nextWander = now + 800 + Math.random() * 900;
    }
    nx = wander.x; ny = wander.y;
  }

  gazeNY = ny;

  const gx = nx * travelX;
  const gy = ny * travelY;
  const fl = floatOffset(now);
  const sx = saccade.x + fl.x;
  const sy = saccade.y + fl.y;

  driveTX = gx + sx;
  driveTY = gy + sy;

  const dir = nx >= 0 ? 1 : -1;
  const c   = Math.pow(Math.min(1, Math.abs(nx)), CONVERGE_CURVE);
  const gap = EYE_GAP_REST - (EYE_GAP_REST - EYE_GAP_MIN) * c;

  const nearIdx = dir > 0 ? 1 : 0;
  const farIdx  = 1 - nearIdx;
  const near = eyes[nearIdx];
  const far  = eyes[farIdx];

  const nearOff = gx + dir * NEAR_EXTRA * c;
  const nearAbs = near.baseX + nearOff;
  const farAbs  = nearAbs - dir * (gap + PILL_W);
  const farOff  = farAbs - far.baseX;

  const vShift = Math.abs(ny) * VERT_SHIFT_X;
  near.tx = nearOff + sx - dir * vShift;
  far.tx  = farOff + sx + dir * vShift * 0;
  near.ty = gy + sy;
  far.ty  = gy + sy;

  const squash = 1 - Math.abs(ny) * VERT_SQUASH;
  near.ty2 = squash;
  far.ty2  = squash;
}

const SHAPES = {
  pill: { a: [0, -7.5, 0, -2.5, 0, 2.5, 0, 7.5],            b: [0, -7.5, 0, -2.5, 0, 2.5, 0, 7.5],           w: 13  },
  idea: { a: [-14, 6, -14, -12.67, 14, -12.67, 14, 6],      b: [-14, 6, -14, -12.67, 14, -12.67, 14, 6],     w: 6   },
  x:    { a: [-13, -13, -4.33, -4.33, 4.33, 4.33, 13, 13],  b: [-13, 13, -4.33, 4.33, 4.33, -4.33, 13, -13], w: 5.5 },
  sleep:{ a: [-12, -3, -6, 5, 6, 5, 12, -3],                b: [-12, -3, -6, 5, 6, 5, 12, -3],               w: 5   }
};
const MORPH_RATE = 0.8;

const strokesA = Array.from(document.querySelectorAll('.stroke-a'));
const strokesB = Array.from(document.querySelectorAll('.stroke-b'));
const weights  = { pill: 1, idea: 0, x: 0, sleep: 0 };

function smooth(t) { return t * t * (3 - 2 * t); }

function pathFrom(pts) {
  return 'M ' + pts[0].toFixed(2) + ' ' + pts[1].toFixed(2) +
         ' C ' + pts[2].toFixed(2) + ' ' + pts[3].toFixed(2) +
         ' ' + pts[4].toFixed(2) + ' ' + pts[5].toFixed(2) +
         ' ' + pts[6].toFixed(2) + ' ' + pts[7].toFixed(2);
}

function updateMorph(dtN) {
  const target = { pill: 0, idea: 0, x: 0, sleep: 0 };
  if (state === 'idea') target.idea = 1;
  else if (state === 'error') target.x = 1;
  else if (sleeping) target.sleep = 1;
  else target.pill = 1;

  const k = REDUCED ? 1 : 1 - Math.pow(MORPH_RATE, dtN);
  let sum = 0;
  for (const key in weights) {
    weights[key] += (target[key] - weights[key]) * k;
    sum += weights[key];
  }

  const mixed = { pill: 0, idea: 0, x: 0, sleep: 0 };
  let msum = 0;
  for (const key in weights) {
    mixed[key] = smooth(Math.max(0, Math.min(1, weights[key] / sum)));
    msum += mixed[key];
  }

  const a = new Array(8).fill(0);
  const b = new Array(8).fill(0);
  let w = 0;
  for (const key in mixed) {
    const f = mixed[key] / msum;
    for (let i = 0; i < 8; i++) {
      a[i] += SHAPES[key].a[i] * f;
      b[i] += SHAPES[key].b[i] * f;
    }
    w += SHAPES[key].w * f;
  }

  const da = pathFrom(a);
  const db = pathFrom(b);
  const ws = w.toFixed(2);
  for (let i = 0; i < strokesA.length; i++) {
    strokesA[i].setAttribute('d', da);
    strokesB[i].setAttribute('d', db);
    strokesA[i].setAttribute('stroke-width', ws);
    strokesB[i].setAttribute('stroke-width', ws);
  }
}

let last = performance.now();
let headX = 0, headY = 0, headRot = 0;
let driveX = 0, driveY = 0;
let earRotL = 0, earRotR = 0, earTrX = 0, earTrY = 0;

function frame(now) {
  refreshClasses();
  const dt   = Math.min(64, Math.max(0, now - last));
  last = now;
  const dtN  = dt / 16.667;
  const damp = Math.pow(DAMP, dtN);
  const k    = 1 - Math.pow(0.86, dtN);

  if (!sleeping && !dizzy && state === 'normal' && !trackEl &&
      now - lastActivity > SLEEP_MS) {
    fallAsleep();
  }

  if (dizzy) {
    const t   = Math.min(1, (now - dizzyStart) / DIZZY_MS);
    const env = smooth(Math.min(1, t / 0.2)) * smooth(Math.min(1, (1 - t) / 0.35));
    const ph  = now / 210;
    mascot.style.transform =
      'rotate(' + (Math.sin(ph) * 9 * env).toFixed(2) + 'deg) ' +
      'translateX(' + (Math.sin(ph + 1.1) * 3 * env).toFixed(2) + 'px)';
  }

  const live = (state === 'normal' || state === 'thinking') && !sleeping;

  updateMorph(dtN);

  if (live) {
    if (trackEl) updateTrackPoint(); else trackPoint = null;
    if (now < typingUntil) updateInputPoint();
    updateTargets(now);
  } else {
    for (const e of eyes) { e.tx = 0; e.ty = 0; e.ty2 = 1; }
  }

  for (const e of eyes) {
    e.vx = (e.vx + (e.tx - e.x) * STIFF * dtN) * damp;
    e.vy = (e.vy + (e.ty - e.y) * STIFF * dtN) * damp;
    e.x += e.vx * dtN;
    e.y += e.vy * dtN;

    let lean = 0;
    if (!REDUCED && live) {
      const lag = e.tx - e.x;
      lean = ((eyes[0].x + eyes[1].x) / 2) * LEAN_OFFSET + lag * LEAN_LAG;
      if (lean >  LEAN_MAX) lean =  LEAN_MAX;
      if (lean < -LEAN_MAX) lean = -LEAN_MAX;
    }
    e.vrot = (e.vrot + (lean - e.rot) * STIFF * dtN) * damp;
    e.rot += e.vrot * dtN;

    e.vsy = (e.vsy + (e.ty2 - e.sy) * STIFF * dtN) * damp;
    e.sy += e.vsy * dtN;

    e.wrap.setAttribute(
      'transform',
      'translate(' + e.x.toFixed(2) + ',' + e.y.toFixed(2) + ') ' +
      'rotate(' + e.rot.toFixed(2) + ') ' +
      'scale(1,' + e.sy.toFixed(3) + ')'
    );
  }

  driveX += ((live ? driveTX : 0) - driveX) * k;
  driveY += ((live ? driveTY : 0) - driveY) * k;

  headX   += ((driveX * HEAD_LEAN_X) - headX) * k;
  headY   += ((driveY * HEAD_LEAN_Y) - headY) * k;
  headRot += ((driveX * HEAD_TILT) - headRot) * k;

  droop += ((sleeping ? 1 : 0) - droop) * (1 - Math.pow(0.94, dtN));

  rig.setAttribute(
    'transform',
    'translate(' + headX.toFixed(2) + ' ' + (headY + droop * 5).toFixed(2) + ') ' +
    'rotate(' + (headRot + droop * 4).toFixed(2) + ' 100 152)'
  );

  const earLeanDeg = driveX * EAR_LEAN;
  const swayL = REDUCED ? 0 : Math.sin(now / 2700) * EAR_SWAY;
  const swayR = REDUCED ? 0 : Math.sin(now / 2700 + 2.1) * EAR_SWAY;

  earRotL += ((earLeanDeg + swayL) - earRotL) * k;
  earRotR += ((earLeanDeg + swayR) - earRotR) * k;
  earTrX  += ((driveX * EAR_DRIFT_X) - earTrX) * k;
  earTrY  += ((driveY * EAR_DRIFT_Y) - earTrY) * k;

  const dEarY = droop * 4;
  earL.setAttribute(
    'transform',
    'translate(' + earTrX.toFixed(2) + ' ' + (earTrY + dEarY).toFixed(2) + ') ' +
    'rotate(' + (earRotL - droop * DROOP_EAR_ROT).toFixed(2) + ')'
  );
  earR.setAttribute(
    'transform',
    'translate(' + earTrX.toFixed(2) + ' ' + (earTrY + dEarY).toFixed(2) + ') ' +
    'rotate(' + (earRotR + droop * DROOP_EAR_ROT).toFixed(2) + ')'
  );

  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

function scheduleBlink() {
  clearTimeout(blinkTimer);
  blinkTimer = setTimeout(function () {
    if ((state === 'normal' || state === 'thinking') && !sleeping && !dizzy) {
      svg.classList.add('blink');
    }
    scheduleBlink();
  }, BLINK_MIN + Math.random() * (BLINK_MAX - BLINK_MIN));
}
scheduleBlink();

function refreshClasses() {
  svg.classList.toggle('state-thinking', state === 'thinking');
  svg.classList.toggle('state-idea',     state === 'idea');
  svg.classList.toggle('state-x',        state === 'error');
  svg.classList.toggle('state-sleep',    sleeping && state === 'normal');
}

function clearTimers() {
  clearTimeout(thinkTimer);
  clearTimeout(ideaTimer);
  thinkTimer = null;
  ideaTimer  = null;
}

function shake() {
  svg.classList.remove('shake');
  void svg.getBoundingClientRect();
  svg.classList.add('shake');
}

function setState(next) {
  if (next !== 'normal') endDizzy();
  if (next !== 'normal' || sleeping) activity();
  clearTimers();
  svg.classList.remove('shake');
  state = next;
  if (next === 'error') shake();
  if (next === 'idea' && IDEA_MS > 0) {
    ideaTimer = setTimeout(function () {
      ideaTimer = null;
      state = 'normal';
      refreshClasses();
    }, IDEA_MS);
  }
  refreshClasses();
}

function pulse(target, cls, ms) {
  target.classList.remove(cls);
  void target.getBoundingClientRect();
  target.classList.add(cls);
  setTimeout(function () { target.classList.remove(cls); }, ms);
}

function activity() {
  lastActivity = performance.now();
  if (sleeping) wakeUp();
}

function fallAsleep() {
  sleeping = true;
}

function wakeUp() {
  sleeping = false;
  pulse(mascot, 'm-hop', 500);
}

function perk() {
  if (sleeping) activity();
  pulse(svg, 'perk', 1100);
  pulse(mascot, 'm-hop', 500);
}

function happy() {
  pulse(svg, 'happy', 800);
  pulse(mascot, 'm-hop', 500);
}

function endDizzy() {
  if (!dizzy) return;
  dizzy = false;
  clearTimeout(dizzyTimer);
  svg.classList.remove('dizzy');
  mascot.style.transform = '';
}

function startDizzy() {
  dizzy = true;
  svg.classList.remove('blink');
  svg.classList.add('dizzy');
  dizzyStart = performance.now();
  clearTimeout(dizzyTimer);
  dizzyTimer = setTimeout(endDizzy, DIZZY_MS);
}

function poke() {
  activity();
  if (dizzy) return;
  const now = performance.now();
  pokes = pokes.filter(function (t) { return now - t < POKE_WINDOW; });
  pokes.push(now);
  if (pokes.length >= POKE_DIZZY) {
    pokes = [];
    startDizzy();
    return;
  }
  pulse(mascot, 'm-hop', 500);
  pulse(svg, 'blink', 200);
}

let lastPoke = 0;
function onTap() {
  const t = performance.now();
  if (t - lastPoke < 80) return;
  lastPoke = t;
  poke();
}
['pointerdown', 'touchstart', 'click'].forEach(function (name) {
  mascot.addEventListener(name, onTap, { passive: true });
});

['pointerdown', 'keydown', 'wheel', 'touchstart', 'focusin'].forEach(function (name) {
  window.addEventListener(name, activity, { passive: true, capture: true });
});
window.addEventListener('scroll', activity, { passive: true, capture: true });
document.addEventListener('visibilitychange', function () {
  if (!document.hidden) activity();
});

if (urlInput) {
  urlInput.addEventListener('input', function () {
    typingUntil = performance.now() + TYPING_MS;
    activity();
  });
  urlInput.addEventListener('keydown', function (e) {
    if (e.key.length === 1 || e.key === 'Backspace') {
      typingUntil = performance.now() + TYPING_MS;
    }
  });
  urlInput.addEventListener('paste', function (e) {
    const cd = e.clipboardData || window.clipboardData;
    const text = cd ? cd.getData('text') : '';
    if (/https?:\/\/|\w\.\w{2,}\//i.test(text)) perk();
  });
}

svg.addEventListener('animationend', function (e) {
  if (e.animationName === 'shake') svg.classList.remove('shake');
  if (e.animationName === 'blink') svg.classList.remove('blink');
});

export const bunny = {
  set: setState,
  perk: perk,
  track: function (el) { trackEl = el; activity(); },
  untrack: function (el, ok) {
    if (trackEl === el) { trackEl = null; trackPoint = null; }
    if (ok) happy();
  },
  get follow() { return mode === 'mouse'; },
  setFollow: function (on) {
    mode = on ? 'mouse' : 'wander';
    nextWander = 0;
    try { localStorage.setItem('bunnyFollow', on ? '1' : '0'); } catch (e) {}
  }
};

refreshClasses();
