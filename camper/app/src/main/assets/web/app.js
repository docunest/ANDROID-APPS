'use strict';
// Camper UI: renders the agent's /api/state, sends taps to /api/cmd. Same page for the Z1 wall screen
// (landscape, fixed 640x360 design scaled to fit) and the phone (portrait, stacked).

const $stage = document.getElementById('stage');
let S = null;
const dieselOn = () => !!(S && S.diesel && S.diesel.connected);
const ui = { screen: ['adjust', 'weather'].includes(location.hash.slice(1)) ? location.hash.slice(1) : 'home', sheet: null, tab: 'floor', wxView: 'now', wxSrc: 'camper', offline: false, toast: null };

const COLORS = { max: '#FF6A3D', diesel: '#FF8A3D', floor: '#FFB547', off: '#7D8792', cool: '#5CC8FF', coolmax: '#3FA8FF' };
const LADDER = [
  { k: 'max', label: 'Max', row: -3, heat: true, diesel: true },
  { k: 'diesel', label: 'Diesel', row: -2, heat: true, diesel: true },
  { k: 'floor', label: 'Floor', row: -1, heat: true },
  { k: 'off', label: 'Off', row: 0 },
  { k: 'cool', label: 'Cool', row: 1, cool: true },
  { k: 'coolmax', label: 'Cool+', row: 2, cool: true },
];
// half dial: centre on the left screen edge, chips evenly spaced down its curve
const R = 172, RING = 132, CX = 10, CY = 170;
LADDER.forEach((p) => {
  const dy = p.row * 48;
  p.a = 90 + Math.asin(dy / R) * 180 / Math.PI;
  p.x = CX + Math.sqrt(R * R - dy * dy);
  p.y = CY + dy;
});
const pt = (a, r) => { const t = a * Math.PI / 180; return [CX + r * Math.sin(t), CY - r * Math.cos(t)]; };
const arc = (a1, a2, r) => {
  if (a1 === a2) return '';
  const p1 = pt(a1, r), p2 = pt(a2, r);
  return `M${p1[0].toFixed(1)} ${p1[1].toFixed(1)} A${r} ${r} 0 0 1 ${p2[0].toFixed(1)} ${p2[1].toFixed(1)}`;
};
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const deg = (v) => (v == null ? '–' : (Math.round(v * 10) / 10).toFixed(1) + '°');
const num = (s) => parseFloat(String(s || '').replace('−', '-'));
const RAIN_BG = ['#2A3038', '#3E5872', '#2F6A9E', '#3B66C9', '#6A5AE0'];
const dayFill = (d) => (d <= -6 ? '#2F5FB8' : d <= -3 ? '#253F66' : d >= 6 ? '#9A5A1E' : d >= 3 ? '#6B4520' : '#232931');
const rainFill = (mm) => (mm >= 10 ? '#7C6CF2' : mm >= 2 ? '#5CA8F0' : mm > 0 ? '#3E5872' : 'transparent');
const ICON_SLIDERS = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" aria-hidden="true"><path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/></svg>';
const ICON_BACK = '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 5l-7 7 7 7"/></svg>';

function warm() {
  const t = S.temps.calibrated;
  return (t != null && t >= 25) || (S.weather && num(S.weather.now) >= 26);
}
function position() { return S.position || S.inferred_position || 'off'; }
function fmtLeft(min) { return min >= 60 ? `${Math.floor(min / 60)} h ${min % 60} min` : `${min} min`; }

// ---------- home ----------

function modeLine(pos) {
  if (pos === 'floor') {
    if (!S.position && S.floor) return `Set ${S.floor.setpoint}°`;
    return S.floor_mode === 'on' ? `On · ${S.on_target}°` : `Auto · ${S.floor_target}°`;
  }
  if (pos === 'diesel' || pos === 'max') {
    const st = S.diesel && S.diesel.status;
    return st && st.level ? `Floor + diesel L${st.level}` : 'Floor + diesel';
  }
  if (pos === 'cool') return 'Cooling to 24°';
  if (pos === 'coolmax') return 'Cooling to 22°';
  return warm() ? 'Cooling off' : 'Heating off';
}

function dialHTML() {
  const pos = position(), cur = LADDER.find((p) => p.k === pos), c = COLORS[pos], w = warm();
  const fill = cur.a < 90 ? arc(cur.a, 90, RING) : cur.a > 90 ? arc(90, cur.a, RING) : '';
  const hp = pt(cur.a, RING);
  const chips = LADDER.map((p) => {
    const on = p.k === pos;
    const dim = !on && ((p.diesel && !dieselOn()) || (w && p.heat) || (!w && p.cool));
    const st = on ? `background:${COLORS[p.k]};border-color:${COLORS[p.k]};color:#120D07;box-shadow:0 0 16px ${COLORS[p.k]}55;` : '';
    return `<button class="chip${dim ? ' dim' : ''}" data-act="pos:${p.k}" aria-pressed="${on}" style="left:${(p.x - 38).toFixed(0)}px;top:${(p.y - 19).toFixed(0)}px;${st}">${p.label}</button>`;
  }).join('');
  const t = S.temps;
  return `<section class="dial" aria-label="Climate dial">
    <svg width="234" height="340" viewBox="0 0 234 340" aria-hidden="true">
      <path d="${arc(8, 172, RING)}" fill="none" stroke="#1C2128" stroke-width="12" stroke-linecap="round"/>
      ${fill ? `<path d="${fill}" fill="none" stroke="${c}" stroke-width="12" stroke-linecap="round" style="filter:drop-shadow(0 0 7px ${c})"/>` : ''}
      <circle cx="${hp[0].toFixed(1)}" cy="${hp[1].toFixed(1)}" r="9" fill="#fff" style="filter:drop-shadow(0 0 6px ${c})"/>
    </svg>
    <div class="center">
      <div class="lbl">INSIDE</div>
      <div class="big">${deg(t.calibrated)}</div>
      <div class="modeline" style="color:${pos === 'off' ? '#A9B2BC' : c}">${esc(modeLine(pos))}</div>
      <div class="raw">Thermostat <b>${deg(t.thermostat)}</b><br>AC sensor <b>${deg(t.ac)}</b></div>
    </div>
    ${chips}
  </section>`;
}

// tiny battery of the wall phone, next to the clock; amber when hot
function batteryHTML() {
  const b = S.battery;
  if (!b) return '';
  const hot = b.temp != null && b.temp >= 40;
  const c = hot ? 'var(--amber)' : b.percent <= 20 && !b.charging ? '#FF6A3D' : 'var(--mute)';
  const w = Math.max(1, Math.round(b.percent / 100 * 12));
  return `<span class="batt" style="color:${c}" title="Screen battery"><svg width="20" height="11" viewBox="0 0 20 11" aria-hidden="true"><rect x="0.75" y="0.75" width="15.5" height="9.5" rx="2" fill="none" stroke="currentColor" stroke-width="1.5"/><rect x="17.5" y="3.5" width="1.75" height="4" rx="0.8" fill="currentColor"/><rect x="2.5" y="2.5" width="${w}" height="6" rx="1" fill="currentColor"/></svg>${b.percent}%${b.charging ? '<svg width="8" height="11" viewBox="0 0 8 11" aria-label="charging"><path d="M4.8 0L0 6.2h3.2L2.6 11 8 4.6H4.6z" fill="currentColor"/></svg>' : ''}${hot ? ' ' + Math.round(b.temp) + '°' : ''}</span>`;
}

function weatherCardHTML() {
  const W = S.weather;
  const clock = new Date().toTimeString().slice(0, 5) + batteryHTML();
  if (!W) return `<button class="card wx" data-act="go:weather"><div class="row"><span class="line" style="color:var(--mute)">Weather loading…</span><span class="clock">${clock}</span></div></button>`;
  const days = W.week.map((d) => `<div><div style="background:${dayFill(d.delta)}">${d.day[0]}</div><div style="background:${rainFill(d.mm)}"></div></div>`).join('');
  return `<button class="card wx" data-act="go:weather" aria-label="Weather details">
    <div class="row"><span class="now">${esc(W.now)}</span><span class="line" style="color:${W.line_cold ? 'var(--cold)' : '#FFC56B'}">${esc(W.line)}</span><span class="clock">${clock}</span></div>
    <div class="row"><span class="k">TMRW</span><span class="pill" style="background:${RAIN_BG[W.tomorrow.severity]}">${esc(W.tomorrow.label)}</span><span style="font-size:14px;font-weight:600">${esc(W.tomorrow.hi)} / ${esc(W.tomorrow.lo)}</span></div>
    <div class="row"><span class="k">7 DAYS</span><div class="days">${days}</div><span class="trend">${esc(W.trend)}</span></div>
  </button>`;
}

function devicesHTML() {
  const rows = [];
  const pill = (c) => `border:1px solid ${c};background:${c}1F;color:${c}`;
  if (!warm()) {
    const f = S.floor;
    let st = 'Not responding', on = false;
    if (f) {
      on = f.power;
      st = !f.power ? 'Off' : `${S.position ? (S.floor_mode === 'on' ? 'On ' : 'Auto ') + S.floor_target + '°' : 'Set ' + f.setpoint + '°'} · ${f.heating ? 'heating' : 'idle'}`;
    }
    rows.push(`<div class="dev"><span class="dot" style="${on ? 'background:var(--amber);box-shadow:0 0 8px var(--amber)' : ''}"></span><div style="flex:1;min-width:0"><div class="n">Floor heating</div><div class="st" style="${on ? 'color:#E6C9A0' : ''}">${esc(st)}</div></div></div>`);
    rows.push(dieselRowHTML(pill));
  } else {
    const a = S.ac, on = a && a.power && a.mode === 'cool';
    rows.push(`<div class="dev"><span class="dot" style="${on ? 'background:var(--blue);box-shadow:0 0 8px var(--blue)' : ''}"></span><div style="flex:1;min-width:0"><div class="n">Air conditioner</div><div class="st">${a ? (on ? `Cool ${a.target}° · ${a.fan} fan` : 'Off') : 'Not responding'}</div></div></div>`);
  }
  const dry = S.dry_minutes_left;
  rows.push(`<div class="dev"><span class="dot" style="${dry ? 'background:var(--teal);box-shadow:0 0 8px var(--teal)' : ''}"></span><div style="flex:1;min-width:0"><div class="n">Dehumidifier</div><div class="st" style="${dry ? 'color:#A7EBDC' : ''}">${dry ? 'Dry · ' + fmtLeft(dry) + ' left' : 'Off'}</div></div>
    <button class="act" style="${pill('#6FE3C9')}" data-act="${dry ? 'dry:stop' : 'sheet:dry'}">${dry ? 'Stop' : 'Run'}</button></div>`);
  return `<section class="card devices" aria-label="Devices">${rows.join('')}</section>`;
}

function dieselText() {
  const D = S.diesel, st = D && D.status;
  if (!st) return '';
  const phase = D.phase === 'run' ? (D.until_off ? 'until you stop it' : `${D.minutes_left} min left`)
    : D.phase === 'clean_stop' ? `clean stop · ${D.minutes_left} min` : D.phase === 'cleanout' ? `clean-out · ${D.minutes_left} min` : '';
  if (!st.running) return 'Off' + (st.room_temp != null ? ` · ${Math.round(st.room_temp)}° at heater` : '');
  const what = st.step === 'running' ? `Level ${st.level}` : st.step[0].toUpperCase() + st.step.slice(1);
  return [what, phase].filter(Boolean).join(' · ');
}

function dieselRowHTML(pill) {
  const D = S.diesel;
  if (!dieselOn()) {
    // not linked: we can't see it, so never show a state (a grey dot would read as "off")
    const note = D ? (D.note || 'connecting…') : 'not linked';
    return `<div class="dev"><span class="dot" style="background:none;border:1.5px dashed #5A636D"></span><div style="flex:1;min-width:0"><div class="n">Diesel heater</div><div class="st" style="color:#C9A86B">State unknown · ${esc(note)}</div></div></div>`;
  }
  const st = D.status, on = st && st.running;
  const err = st && st.error;
  return `<div class="dev"><span class="dot" style="${on ? 'background:var(--orange);box-shadow:0 0 8px var(--orange)' : ''}"></span><div style="flex:1;min-width:0"><div class="n">Diesel heater</div><div class="st" style="${err ? 'color:#FF8A6A' : on ? 'color:#F2B48E' : ''}">${esc(err || dieselText())}</div></div>
    ${on && D.phase !== 'clean_stop' ? `<button class="act" style="${pill('#FF8A3D')}" data-act="sheet:stop">Stop</button>` : ''}</div>`;
}

function alertsHTML() {
  const list = [];
  if (ui.offline) list.push({ text: 'Camper not reachable', sub: 'Showing the last known state' });
  if (S.dry_run) list.push({ text: 'Test mode', sub: 'Commands are logged, not sent' });
  list.push(...S.alerts);
  return list.slice(0, 2).map((a) => `<div class="alert"><div><div class="t">${esc(a.text)}</div><div class="s">${esc(a.sub)}</div></div>${a.action ? `<button data-act="${a.id === 'cleanout' ? 'sheet:cleanout' : 'dismiss:' + esc(a.id)}">${esc(a.action)}</button>` : ''}</div>`).join('');
}

function homeHTML() {
  return `<div class="home">
    ${dialHTML()}
    <div class="col">
      ${weatherCardHTML()}
      <div class="lower">
        <div class="col">${devicesHTML()}<button class="adjust" data-act="go:adjust">${ICON_SLIDERS}<span>Adjust &amp; schedules</span></button></div>
        <div class="col">${alertsHTML()}</div>
      </div>
    </div>
  </div>`;
}

// ---------- adjust ----------

function seg(items, current, act, accent) {
  return `<div class="seg">${items.map(([id, label]) => {
    const on = id === current;
    return `<button data-act="${act}:${id}" aria-pressed="${on}" style="${on ? `background:${accent};color:#120D07` : ''}">${label}</button>`;
  }).join('')}</div>`;
}

function adjustHTML() {
  const tabs = [['floor', 'Floor heating'], ['diesel', 'Diesel'], ['ac', 'AC · Dry']]
    .map(([id, l]) => `<button class="${ui.tab === id ? 'on' : ''}" data-act="tab:${id}" role="tab" aria-selected="${ui.tab === id}">${l}</button>`).join('');
  let body = '';
  if (ui.tab === 'floor') {
    let inner = '';
    if (S.floor_mode === 'auto') {
      const stp = (k, l) => `<div class="stp"><div style="font-size:14px;font-weight:600;color:var(--ink2)">${l}</div><div class="v">${S.auto_targets[k]}°</div>
        <div class="pm"><button data-act="autot:${k}:-0.5" aria-label="Lower ${l}">−</button><button data-act="autot:${k}:0.5" aria-label="Raise ${l}">+</button></div></div>`;
      inner = `<div class="steppers">${stp('night', 'Night')}${stp('cloudy', 'Cloudy day')}${stp('sunny', 'Sunny day')}</div>
        <div class="note">Now aiming <b style="color:var(--ink)">${S.floor_target}°</b> (${S.auto_reason}). The floor stops once inside reaches it.</div>`;
    } else if (S.floor_mode === 'on') {
      inner = `<div class="onbig"><button class="round" data-act="ont:-0.5" aria-label="Lower">−</button>
        <div><div class="v">${S.on_target}°</div><div class="note" style="text-align:center">Runs until you switch it off</div></div>
        <button class="round" data-act="ont:0.5" aria-label="Raise">+</button></div>`;
    } else {
      inner = `<div class="onbig note">Floor heating stays off.</div>`;
    }
    const t = S.temps, diff = t.thermostat != null && t.ac != null ? t.thermostat - t.ac : null;
    const src = (id, l, v) => `<button class="src${S.control_by === id ? ' on' : ''}" data-act="ctl:${id}"><span>${l}</span><span>${deg(v)}</span></button>`;
    body = `<div class="body2">
      <section class="panel">${seg([['off', 'Off'], ['on', 'On'], ['auto', 'Auto']], S.floor_mode, 'fm', '#FFB547')}${inner}</section>
      <section class="panel"><div class="h">CONTROL HEATING BY</div>
        ${src('calibrated', 'Calibrated', t.calibrated)}${src('thermostat', 'Thermostat raw', t.thermostat)}${src('ac', 'AC sensor raw', t.ac)}
        <div class="warnnote" style="margin-top:auto">${diff == null ? 'One sensor is missing' : `Thermostat reads ${diff >= 0 ? '+' : ''}${diff.toFixed(1)}° vs AC sensor`}${S.floor ? `<br>Setpoint on the thermostat: ${S.floor.setpoint}°` : ''}</div>
      </section></div>`;
  } else if (ui.tab === 'diesel') {
    const D = S.diesel, st = D && D.status;
    if (!dieselOn()) {
      body = `<div class="body2"><section class="panel"><div style="font-size:17px;font-weight:600">Diesel heater not connected</div>
        <div class="note" style="font-size:14px;line-height:1.45">${esc((D && D.note) || 'not linked')}. The heater takes one Bluetooth connection at a time: close the AirHeaterBLE app and the heater web app on your phone.</div></section>
        <section class="panel"><div class="h">SAFETY RULES</div><div class="note" style="line-height:1.5">Runs only when you start it, with a timer<br>Clean stop: 10 min hot, then off<br>Weekly clean-out reminder</div></section></div>`;
    } else {
      const lvl = st && st.level ? st.level : D.low;
      const row = (k, v) => `<div style="display:flex;justify-content:space-between;font-size:14px"><span class="note">${k}</span><b>${v}</b></div>`;
      body = `<div class="body2">
        <section class="panel"><div class="h">LEVEL</div>
          <div class="onbig"><button class="round" data-act="dlev:-1" aria-label="Lower level">−</button>
            <div><div class="v" style="color:var(--orange)">${lvl}</div><div class="note" style="text-align:center">${esc(dieselText())}</div></div>
            <button class="round" data-act="dlev:1" aria-label="Raise level">+</button></div>
          ${st && st.running
            ? `<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px"><button class="src" style="justify-content:center;border-color:#FF8A3D" data-act="dstop:clean">Clean stop · 10 min hot</button><button class="src" style="justify-content:center" data-act="dstop:now">Stop now</button></div>`
            : `<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px">${[['low', D.low], ['mid', D.mid || 5], ['high', D.high]].map(([k, v]) => `<button class="src" style="justify-content:center;border-color:#FF8A3D;color:#FFB48A" data-act="srun:${k}">${k[0].toUpperCase() + k.slice(1)} · ${v}</button>`).join('')}</div>`}
        </section>
        <section class="panel"><div class="h">HEATER</div>
          ${row('Burner', st ? esc(st.step) : '–')}${row('At heater', st && st.room_temp != null ? st.room_temp + '°' : '–')}
          ${row('Heat exchanger', st && st.case_temp != null ? Math.round(st.case_temp) + '°' : '–')}${row('Supply', st && st.voltage != null ? st.voltage.toFixed(1) + ' V' : '–')}
          ${st && st.error ? `<div class="warnnote">${esc(st.error)}</div>` : ''}
          <button class="src" style="margin-top:auto;justify-content:center;background:#231A0F;border-color:#47331A;color:#F6D9AE" data-act="sheet:cleanout">Clean-out burn · 20 min</button>
        </section></div>`;
    }
  } else {
    const a = S.ac, pos = position(), dry = S.dry_minutes_left > 0;
    const cur = pos === 'cool' || pos === 'coolmax' ? pos : dry ? 'dry' : 'off';
    body = `<div class="body2">
      <section class="panel">${seg([['off', 'Off'], ['dry', 'Dry'], ['cool', 'Cool'], ['coolmax', 'Cool+']], cur, 'acm', cur === 'dry' ? '#6FE3C9' : '#5CC8FF')}
        <div class="onbig" style="flex-direction:column;gap:6px"><div class="v" style="color:${cur === 'off' ? '#A9B2BC' : cur === 'dry' ? 'var(--teal)' : 'var(--blue)'};font-size:52px">${a ? (a.power ? (a.mode === 'cool' ? a.target + '°' : a.mode === 'dry' ? 'Dry' : 'Fan') : 'Off') : '–'}</div>
        <div class="note">${a ? `AC sensor ${deg(a.temp)}${dry ? ' · ' + fmtLeft(S.dry_minutes_left) + ' left' : ''}` : 'AC not responding'}</div></div></section>
      <section class="panel"><div class="h">FAN IN DRY MODE</div>${seg([['low', 'Low'], ['high', 'High'], ['auto', 'Auto']], S.ac_fan || 'auto', 'acfan', '#5CC8FF')}
        <div class="h">NIGHT</div><button class="src${S.ac_quiet ? ' on' : ''}" data-act="quiet:x"><span>${S.ac_quiet ? 'Silent · display off' : 'Beeps · display on'}</span><span></span></button>
        ${a && a.filter_alert ? '<div class="warnnote" style="margin-top:auto">Filter needs cleaning</div>' : ''}</section></div>`;
  }
  return `<div class="screen"><div class="top"><button class="back" data-act="go:home" aria-label="Back">${ICON_BACK}</button><div class="seg tabs" role="tablist">${tabs}</div></div>${body}</div>`;
}

// ---------- weather ----------

function weatherHTML() {
  const W = S.weather, loc = S.location || [0, 0];
  const tabs = [['now', W ? W.detail_title : 'Today'], ['tomorrow', 'Tomorrow'], ['week', '7 days']]
    .map(([id, l]) => `<button class="${ui.wxView === id ? 'on' : ''}" data-act="wxv:${id}" role="tab">${l}</button>`).join('');
  const lat = loc[0], lon = loc[1];
  const mb = `https://www.meteoblue.com/en/weather/week/${Math.abs(lat)}${lat >= 0 ? 'N' : 'S'}${Math.abs(lon)}${lon >= 0 ? 'E' : 'W'}`;
  const rail = `<nav class="rail" aria-label="Source">
    <button class="${ui.wxSrc === 'camper' ? 'on' : ''}" data-act="wxs:camper"><b>Camper</b><span>Open-Meteo</span></button>
    <button class="${ui.wxSrc === 'windy' ? 'on' : ''}" data-act="wxs:windy"><b>Windy</b><span>wind, radar</span></button>
    <a href="${mb}" target="_blank" rel="noopener"><b>Meteoblue</b><span>meteogram ↗</span></a>
    <a href="https://www.aemet.es/en/eltiempo/prediccion/avisos" target="_blank" rel="noopener"><b>AEMET</b><span>warnings ↗</span></a>
  </nav>`;
  let content = '<div class="note">Weather not loaded yet.</div>';
  if (ui.wxSrc === 'windy') {
    const z = `https://embed.windy.com/embed2.html?lat=${lat}&lon=${lon}&detailLat=${lat}&detailLon=${lon}&zoom=7&level=surface&overlay=${ui.wxView === 'week' ? 'temp' : 'rain'}&product=ecmwf&menu=&message=true&marker=true&calendar=now&type=map&location=coordinates&metricWind=km%2Fh&metricTemp=%C2%B0C`;
    content = `<iframe src="${z}" title="Windy"></iframe>`;
  } else if (W && ui.wxView === 'now') {
    const hrs = W.detail_hours.map((h) => `<div class="hr" style="${h.frost ? 'background:#152435' : ''}"><div class="tv" style="${h.frost ? 'color:var(--cold)' : ''}">${esc(h.temp)}</div><div class="m">${h.mm ? h.mm + ' mm' : 'dry'}</div><div class="tm">${h.time}</div></div>`).join('');
    content = `<div style="font-size:17px;font-weight:600;line-height:1.35">${esc(W.line)}</div><div class="grid7">${hrs}</div>`;
  } else if (W && ui.wxView === 'tomorrow') {
    const T = W.tomorrow, max = Math.max(1, ...W.tomorrow_hours.map((h) => h.mm));
    const bars = W.tomorrow_hours.map((h) => `<div><div style="font-size:12px;font-weight:600;color:${h.mm >= 1 ? 'var(--cold)' : 'var(--mute)'}">${h.mm ? h.mm : '–'}</div>
      <div class="b" style="height:${Math.max(4, Math.round(h.mm / max * 100))}px;background:${h.mm >= 4 ? '#7C6CF2' : h.mm >= 1 ? '#5CA8F0' : h.mm > 0 ? '#3E5872' : '#232931'}"></div>
      <div style="font-size:13px;font-weight:600">${esc(h.temp)}</div><div style="font-size:12px;color:var(--mute)">${h.time}</div></div>`).join('');
    content = `<div style="display:flex;align-items:center;gap:10px"><span class="pill" style="background:${RAIN_BG[T.severity]};font-size:14px">${esc(T.label)}</span>
        <span style="font-family:var(--num);font-size:20px">${esc(T.hi)} <span style="color:var(--mute)">/ ${esc(T.lo)}</span></span>
        <span style="margin-left:auto;font-size:13px;color:#A9B2BC">${esc(T.condition)} · gusts ${T.gusts} km/h</span></div>
      <div style="font-size:15px;color:var(--ink2)">${T.rain_window ? `Rain <b style="color:var(--ink)">${esc(T.rain_window)}</b>${T.dry_after ? `. Dry from <b style="color:#7FE0A4">${esc(T.dry_after)}</b>` : ''}.` : 'No rain expected.'}</div>
      <div class="bars">${bars}</div>`;
  } else if (W) {
    const days = W.week.map((d) => `<div class="wk"><div style="font-size:13px;font-weight:600;color:var(--ink2)">${d.day}</div><div class="blk" style="background:${dayFill(d.delta)}">${esc(d.hi)}</div>
      <div style="font-size:13px;color:var(--mute)">${esc(d.lo)}</div><div style="width:100%;height:6px;border-radius:3px;background:${d.mm > 0 ? rainFill(d.mm) : '#1C2128'}"></div>
      <div style="font-size:12px;font-weight:600;color:${d.mm >= 10 ? '#B9AEFF' : d.mm >= 2 ? 'var(--cold)' : 'var(--mute)'}">${d.mm ? d.mm + ' mm' : 'dry'}</div></div>`).join('');
    content = `<div><div style="font-size:15px;font-weight:600"><span style="color:var(--cold)">${esc(W.trend)}</span>${W.trend_detail ? ' · ' + esc(W.trend_detail) : ''}</div>
      ${W.wettest ? `<div class="note" style="font-size:14px">Wettest: ${esc(W.wettest)}</div>` : ''}</div><div class="grid7">${days}</div>
      <div class="note" style="font-size:12px">Blue = colder than today, brown = warmer (3°+ counts)</div>`;
  }
  return `<div class="screen"><div class="top"><button class="back" data-act="go:home" aria-label="Back">${ICON_BACK}</button>
    <div class="seg tabs" role="tablist" style="flex:0 1 330px">${tabs}</div>
    <div style="margin-left:auto;text-align:right"><div style="font-family:var(--num);font-size:22px">${W ? esc(W.now) : ''}</div><div style="font-size:12px;color:var(--mute)">${W ? esc(W.now_condition) + ' · ' + W.updated : ''}</div></div></div>
    <div class="wxbody">${rail}<section class="panel">${content}</section></div></div>`;
}

// ---------- sheets ----------

function sheetHTML() {
  if (!ui.sheet) return '';
  let title = '', sub = '', opts = [], cancel = 'Cancel', pre = '';
  const opt = (label, s, act, c) => `<button class="opt" data-act="${act}" style="border:1px solid ${c};background:${c}1A"><b>${label}</b><span>${s}</span></button>`;
  if (ui.sheet === 'dry') {
    const now = new Date(), until = new Date(now);
    until.setHours(8, 0, 0, 0);
    if (until <= now) until.setDate(until.getDate() + 1);
    const overnight = Math.round((until - now) / 60000);
    title = 'Dehumidify with the AC';
    sub = 'Runs alongside heating. Check the AC water tank afterwards.';
    opts = [opt('1 h', '', 'dry:60', '#6FE3C9'), opt('3 h', '', 'dry:180', '#6FE3C9'), opt('Overnight', 'until 08:00', `dry:${overnight}`, '#6FE3C9')];
  } else if (ui.sheet === 'dieselrun') {
    const lv = ui.sheetLevel || 'low', c = lv === 'high' ? '#FF6A3D' : '#FF8A3D';
    const D = S.diesel, lvl = { low: D.low, mid: D.mid || 5, high: D.high };
    title = 'Run diesel heater';
    sub = `Level ${lvl[lv]}. Floor heating keeps running underneath; when the time is up it does a clean stop (10 min hot, then off).`;
    pre = `<div class="seg" style="grid-auto-columns:minmax(0,1fr)">${['low', 'mid', 'high'].map((k) => `<button data-act="dlvl:${k}" aria-pressed="${k === lv}" style="height:48px;${k === lv ? `background:${c};color:#120D07` : ''}">${k[0].toUpperCase() + k.slice(1)} · ${lvl[k]}</button>`).join('')}</div>`;
    opts = [opt('20 min', 'quick warm-up', 'drun:20', c), opt('1 h', '', 'drun:60', c), opt('2 h', '', 'drun:120', c), opt('Until off', 'you stop it', 'drun:0', c)];
  } else if (ui.sheet === 'stop') {
    title = 'Stop the diesel heater';
    sub = 'A clean stop burns hot for 10 min first, which keeps the glow plug free of soot.';
    opts = [opt('Clean stop', '10 min hot, then off', 'dstop:clean', '#FF8A3D'), opt('Stop now', 'fan cools it down', 'dstop:now', '#7D8792')];
  } else if (ui.sheet === 'cleanout') {
    title = 'Diesel clean-out burn';
    sub = '20 min at full power to burn off carbon, then off. It will get warm in here.';
    opts = [opt('Start now', '20 min at level 10', 'dclean:go', '#FF6A3D')];
  } else if (ui.sheet === 'diesel') {
    title = 'Diesel heater not linked yet';
    sub = 'This screen cannot see or control it yet, so use its own controller. Once linked, this asks how long to run and offers a clean stop.';
    cancel = 'OK';
  }
  return `<div class="scrim"><div class="sheet" role="dialog" aria-label="${esc(title)}"><div><h2>${esc(title)}</h2><p>${esc(sub)}</p></div>
    ${pre}${opts.length ? `<div class="opts" style="grid-template-columns:repeat(${opts.length},minmax(0,1fr))">${opts.join('')}</div>` : ''}
    <button class="ghost" data-act="sheet:">${cancel}</button></div></div>`;
}

// ---------- plumbing ----------

function render() {
  if (!S) return;
  const toast = ui.toast ? `<div class="scrim" style="background:transparent;align-items:flex-end;pointer-events:none"><div class="alert" style="margin-bottom:12px"><div class="t">${esc(ui.toast)}</div></div></div>` : '';
  const screen = ui.screen === 'adjust' ? adjustHTML() : ui.screen === 'weather' ? weatherHTML() : homeHTML();
  $stage.innerHTML = screen + sheetHTML() + toast;
}

function fit() {
  const portrait = innerHeight > innerWidth * 1.1;
  document.body.classList.toggle('portrait', portrait);
  if (portrait) { $stage.style.transform = ''; $stage.style.left = ''; $stage.style.top = ''; return; }
  const k = Math.min(innerWidth / 640, innerHeight / 360);
  $stage.style.transform = `scale(${k})`;
  $stage.style.left = `${(innerWidth - 640 * k) / 2}px`;
  $stage.style.top = `${(innerHeight - 360 * k) / 2}px`;
}

function toast(msg) {
  ui.toast = msg;
  render();
  setTimeout(() => { ui.toast = null; render(); }, 3500);
}

async function load() {
  try {
    const r = await fetch('api/state', { cache: 'no-store' });
    S = await r.json();
    ui.offline = false;
  } catch (e) {
    ui.offline = true;
  }
  // don't reload the Windy map every poll
  if (!(ui.screen === 'weather' && ui.wxSrc === 'windy')) render();
}

async function cmd(c) {
  try {
    const r = await fetch('api/cmd', { method: 'POST', body: JSON.stringify(c) });
    const j = await r.json();
    if (j.state) S = j.state;
    if (!j.ok) toast(j.error || 'That did not work');
  } catch (e) {
    toast('Camper not reachable');
  }
  render();
}

function handle(a, b, c) {
  switch (a) {
    case 'pos':
      if (b === 'diesel' || b === 'max') {
        if (!dieselOn()) { ui.sheet = 'diesel'; break; }
        ui.sheetLevel = b === 'max' ? 'high' : 'low'; ui.sheet = 'dieselrun'; break;
      }
      if (S.diesel && S.diesel.phase === 'run' && b !== S.position) { ui.nextPos = b; ui.sheet = 'stop'; break; }
      if (b !== S.position) { S.position = b; cmd({ action: 'position', value: b }); }
      return;
    case 'go': ui.screen = b; ui.sheet = null; break;
    case 'srun': ui.sheetLevel = b; ui.sheet = 'dieselrun'; break;
    case 'dlvl': ui.sheetLevel = b; break;
    case 'drun': ui.sheet = null; cmd({ action: 'diesel', level: ui.sheetLevel, minutes: +b }); return;
    case 'dstop': ui.sheet = null; cmd({ action: 'diesel_stop', clean: b === 'clean', next: ui.nextPos || 'floor' }); ui.nextPos = null; return;
    case 'dclean': ui.sheet = null; cmd({ action: 'diesel_cleanout' }); return;
    case 'dlev': {
      const cur = (S.diesel.status && S.diesel.status.level) || S.diesel.low;
      cmd({ action: 'diesel_level', value: Math.min(10, Math.max(1, cur + parseInt(b, 10))) });
      return;
    }
    case 'sheet': ui.sheet = b || null; break;
    case 'dry':
      ui.sheet = null;
      cmd({ action: 'dry', minutes: b === 'stop' ? 0 : +b });
      return;
    case 'dismiss':
      S.alerts = S.alerts.filter((x) => x.id !== b);
      cmd({ action: 'dismiss', id: b });
      return;
    case 'tab': ui.tab = b; break;
    case 'wxv': ui.wxView = b; break;
    case 'wxs': ui.wxSrc = b; break;
    case 'fm': S.floor_mode = b; cmd({ action: 'floor_mode', value: b }); return;
    case 'ont': S.on_target = Math.min(28, Math.max(5, S.on_target + parseFloat(b))); cmd({ action: 'on_target', value: S.on_target }); return;
    case 'autot': {
      const v = Math.min(28, Math.max(5, S.auto_targets[b] + parseFloat(c)));
      S.auto_targets[b] = v;
      cmd({ action: 'auto_target', key: b, value: v });
      return;
    }
    case 'ctl': S.control_by = b; cmd({ action: 'control_by', value: b }); return;
    case 'quiet': cmd({ action: 'ac_quiet', value: !S.ac_quiet }); return;
    case 'acfan': S.ac_fan = b; cmd({ action: 'ac_fan', value: b }); return;
    case 'acm':
      if (b === 'dry') { ui.sheet = 'dry'; break; }
      if (b === 'off') {
        if (S.dry_minutes_left) cmd({ action: 'dry', minutes: 0 });
        if (S.position === 'cool' || S.position === 'coolmax') cmd({ action: 'position', value: 'off' });
        return;
      }
      cmd({ action: 'position', value: b });
      return;
  }
  render();
}

$stage.addEventListener('click', (e) => {
  const el = e.target.closest('[data-act]');
  if (!el) return;
  const [a, b, c] = el.dataset.act.split(':');
  handle(a, b, c);
});
// ---------- kiosk behaviour ----------
// Idle: back to home after 90 s. Night (22-07): dim after 1 min idle; the first tap only wakes it.
// Fully Kiosk Browser, if used, gets real backlight control through its JS API.
let lastTouch = Date.now(), dimmed = false, swallowClick = false;
const night = () => { const h = new Date().getHours(); return h >= 22 || h < 7; };
function setDim(on) {
  if (on === dimmed) return;
  dimmed = on;
  document.body.classList.toggle('dim', on);
  // the Camper Android app turns the real backlight down; Fully Kiosk has its own API
  if (window.CamperNative) { try { CamperNative.setBrightness(on ? 0.02 : -1); } catch (e) { /* older app */ } }
  if (window.fully) { try { fully.setScreenBrightness(on ? 8 : 180); } catch (e) { /* not in Fully */ } }
}
setInterval(() => {
  const idle = Date.now() - lastTouch;
  if (idle > 90000 && (ui.screen !== 'home' || ui.sheet)) { ui.screen = 'home'; ui.sheet = null; render(); }
  setDim(night() && idle > 60000);
}, 5000);
document.addEventListener('pointerdown', (e) => {
  lastTouch = Date.now();
  if (dimmed) { setDim(false); swallowClick = true; e.preventDefault(); e.stopPropagation(); return; }
  // landscape = wall screen: go fullscreen on first touch (hides Android bars in a plain browser)
  if (!document.body.classList.contains('portrait') && !document.fullscreenElement && document.documentElement.requestFullscreen) {
    document.documentElement.requestFullscreen().catch(() => {});
  }
}, true);
document.addEventListener('click', (e) => { if (swallowClick) { swallowClick = false; e.preventDefault(); e.stopPropagation(); } }, true);
document.addEventListener('contextmenu', (e) => e.preventDefault());
// Android Back button in the Camper app: close a sheet, else go to the home screen
window.camperBack = () => { if (ui.sheet) ui.sheet = null; else ui.screen = 'home'; render(); };
async function keepAwake() { try { if ('wakeLock' in navigator) await navigator.wakeLock.request('screen'); } catch (e) { /* needs https or localhost */ } }
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') keepAwake(); });
keepAwake();

addEventListener('resize', fit);
fit();
load();
setInterval(load, 3000);
