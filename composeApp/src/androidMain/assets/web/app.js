// MoneyTracker web dashboard. Served by the phone on the local network; talks only to the phone (same origin).
// Plain JavaScript with no libraries, so nothing is ever loaded from the internet.
'use strict';
(function () {
  const app = document.getElementById('app');
  const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
  const MON = MONTHS.map(m => m.slice(0, 3));
  const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const PALETTE = ['#0F5E4E', '#E8A54B', '#1D5FD0', '#C8373A', '#7A5AC8', '#2E9E8F', '#B8557A', '#8A8F3C'];
  const PAGE = 300;

  const S = {
    meta: null, password: false, tab: 'transactions',
    f: { period: 'month', ym: 0, from: '', to: '', type: 'all', account: '', category: '', tags: [], q: '' },
    aKind: 'expense',
    data: null, dataKey: '', summary: null, summaryKey: '', accounts: null,
    shown: PAGE, dialog: null, exportOpen: false, toastTimer: 0,
  };

  // ───────────────────────── DOM helpers

  function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    if (props) for (const k in props) {
      const v = props[k];
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'style') Object.assign(el.style, v);
      else if (k === 'value') el.value = v;
      else if (k === 'checked' || k === 'disabled' || k === 'selected') el[k] = !!v;
      else el.setAttribute(k, v === true ? '' : v);
    }
    add(el, kids);
    return el;
  }
  function add(el, kids) {
    for (const c of kids) {
      if (c === null || c === undefined || c === false) continue;
      if (Array.isArray(c)) add(el, c);
      else el.appendChild(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
    }
  }
  const NS = 'http://www.w3.org/2000/svg';
  function svg(tag, attrs, ...kids) {
    const el = document.createElementNS(NS, tag);
    for (const k in attrs || {}) el.setAttribute(k, attrs[k]);
    kids.forEach(c => c && el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c));
    return el;
  }
  function icon(d) {
    return svg('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '2', 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' },
      ...d.split('|').map(p => svg('path', { d: p })));
  }
  const I = {
    left: 'm15 6-6 6 6 6', right: 'm9 6 6 6-6 6', plus: 'M12 5v14|M5 12h14', x: 'M6 6l12 12|M18 6 6 18',
    edit: 'M4 20h4L19 9l-4-4L4 16z|m13.5 6.5 4 4', trash: 'M4 7h16|M10 11v6|M14 11v6|M6 7l1 13h10l1-13|M9 7V4h6v3',
    search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z|m20 20-3.5-3.5', lock: 'M6 11h12v9H6z|M8 11V8a4 4 0 0 1 8 0v3',
    download: 'M12 4v11|m7 10 5 5 5-5|M5 20h14',
  };

  // ───────────────────────── formatting

  const pad = n => String(n).padStart(2, '0');
  function mainCur() { return S.meta.currency; }
  function curOf(code) { return (code && S.meta.currencies.find(c => c.code === code)) || null; }
  function fmt(minor, cur, signed) {
    cur = cur || mainCur();
    const d = cur.decimals, f = Math.pow(10, d), neg = minor < 0, a = Math.abs(minor);
    let s = String(Math.floor(a / f)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    if (d > 0) s += '.' + String(a % f).padStart(d, '0');
    const sg = neg ? '−' : (signed && minor > 0 ? '+' : '');
    return cur.after ? sg + s + ' ' + cur.symbol : sg + cur.symbol + ' ' + s;
  }
  function toInput(minor, d) {
    if (!d) return String(minor);
    const f = Math.pow(10, d);
    const frac = String(minor % f).padStart(d, '0').replace(/0+$/, '');
    return Math.floor(minor / f) + (frac ? '.' + frac : '');
  }
  function parseDate(s) { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); }
  function iso(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  function shortDate(s) { const d = parseDate(s); return d.getDate() + ' ' + MON[d.getMonth()]; }
  function longDate(s) { const d = parseDate(s); return DAYS[d.getDay()] + ', ' + d.getDate() + ' ' + MON[d.getMonth()] + ' ' + d.getFullYear(); }
  const ymY = ym => Math.floor(ym / 100), ymM = ym => ym % 100;
  function ymAdd(ym, n) { const i = ymY(ym) * 12 + ymM(ym) - 1 + n; return Math.floor(i / 12) * 100 + (i % 12) + 1; }

  // ───────────────────────── lookups

  const acc = id => S.meta.accounts.find(a => a.id === id);
  const accName = id => (acc(id) || {}).name || 'Deleted account';
  const cat = id => S.meta.categories.find(c => c.id === id);
  function catPath(id) {
    if (!id) return '';
    const c = cat(id); if (!c) return 'Deleted category';
    const p = c.parentId && cat(c.parentId);
    return p ? p.name + ' › ' + c.name : c.name;
  }
  const mainCatId = id => { const c = cat(id); return c && c.parentId ? c.parentId : id; };
  const tagName = id => (S.meta.tags.find(t => t.id === id) || {}).name || '';

  // ───────────────────────── API

  class Stopped extends Error {}
  class SignedOut extends Error {}

  async function api(path, opt) {
    opt = opt || {};
    const o = { method: opt.method || 'GET', headers: {}, credentials: 'same-origin', cache: 'no-store' };
    if (o.method !== 'GET') o.headers['X-Requested-With'] = 'MoneyTracker';
    if (opt.body !== undefined) { o.headers['Content-Type'] = 'application/json'; o.body = JSON.stringify(opt.body); }
    let r;
    try { r = await fetch(path, o); } catch (e) { stopped(); throw new Stopped(); }
    let j = null;
    try { j = await r.json(); } catch (e) { /* not JSON */ }
    if (r.status === 401 && !opt.quiet) { signedOut(); throw new SignedOut(); }
    if (!r.ok) { const e = new Error((j && j.error) || 'Something went wrong on the phone.'); e.status = r.status; e.data = j; throw e; }
    return j;
  }
  function quietly(p) { return p.catch(e => { if (!(e instanceof Stopped) && !(e instanceof SignedOut)) toast(e.message); }); }

  // ───────────────────────── start, sign-in, stopped

  async function boot() {
    let s;
    try { s = await api('/api/session', { quiet: true }); } catch (e) { if (!(e instanceof Stopped)) stopped(); return; }
    S.password = !!s.password;
    if (!s.signedIn) return login(s.wait || 0);
    await enter();
  }

  async function enter() {
    S.meta = await api('/api/meta');
    if (!S.f.ym) S.f.ym = S.meta.month;
    S.tab = tabFromHash();
    render();
  }

  function login(wait) {
    let tries = null;
    const input = h('input', { type: 'password', autocomplete: 'current-password', 'aria-label': 'Password', placeholder: 'Password', required: true });
    const err = h('p', { role: 'alert', class: 'small', style: { color: 'var(--danger)' } });
    const btn = h('button', { class: 'btn primary', type: 'submit', text: 'Open' });
    function lock(sec) {
      if (sec <= 0) { btn.disabled = false; err.textContent = tries ? err.textContent : ''; return; }
      btn.disabled = true;
      err.textContent = 'Too many tries. Wait ' + sec + ' s, then try again.';
      setTimeout(() => lock(sec - 1), 1000);
    }
    const form = h('form', { class: 'card login', onsubmit: async ev => {
      ev.preventDefault();
      if (!input.value) return;
      btn.disabled = true;
      try {
        await api('/api/login', { method: 'POST', body: { password: input.value }, quiet: true });
        await enter();
      } catch (e) {
        if (e instanceof Stopped) return;
        btn.disabled = false;
        tries = true;
        input.classList.add('bad');
        input.select();
        if (e.data && e.data.wait) lock(e.data.wait); else err.textContent = e.message;
      }
    } },
      h('img', { src: '/icon.svg', alt: '' }),
      h('h1', { text: 'MoneyTracker' }),
      h('p', { text: 'This dashboard comes from a phone on your Wi-Fi. Enter the password set on that phone.' }),
      input, err, btn,
      h('p', { class: 'small', text: 'Local network only · after 5 wrong tries, wait a minute · you’re signed out after 30 minutes idle.' }));
    app.replaceChildren(h('div', { class: 'center' }, form));
    input.focus();
    if (wait > 0) lock(wait);
  }

  function stopped() {
    closeDialog();
    app.replaceChildren(h('div', { class: 'center' }, h('div', { class: 'card login' },
      h('img', { src: '/icon.svg', alt: '' }),
      h('h1', { text: 'The dashboard has stopped' }),
      h('p', { text: 'The phone stopped serving this page, or it can’t be reached. Nothing was lost: every change you made is saved on the phone.' }),
      h('ul', null,
        h('li', { text: 'Stopped on the phone, or after its auto-stop time with no visits' }),
        h('li', { text: 'The phone left this Wi-Fi network, or its screen is off for long on a strict battery setting' })),
      h('p', { text: 'To continue, start it again on the phone (Web dashboard in More or Settings), then try again.' }),
      h('button', { class: 'btn primary', text: 'Try again', onclick: () => location.reload() }))));
  }

  function signedOut() {
    closeDialog();
    if (S.password) return login(0);
    app.replaceChildren(h('div', { class: 'center' }, h('div', { class: 'card login' },
      h('img', { src: '/icon.svg', alt: '' }),
      h('h1', { text: 'Signed out' }),
      h('p', { text: 'This computer was signed out, from here or from the phone.' }),
      h('button', { class: 'btn primary', text: 'Open again', onclick: () => location.reload() }))));
  }

  async function signOut() {
    try { await api('/api/logout', { method: 'POST', body: {}, quiet: true }); } catch (e) { /* signed out either way */ }
    signedOut();
  }

  // ───────────────────────── layout

  function tabFromHash() {
    const t = location.hash.replace('#', '');
    return ['transactions', 'analytics', 'accounts'].includes(t) ? t : 'transactions';
  }
  window.addEventListener('hashchange', () => { if (S.meta) { S.tab = tabFromHash(); render(); } });

  function go(tab) { if (location.hash !== '#' + tab) location.hash = tab; else { S.tab = tab; render(); } }

  function header() {
    const tab = (id, label) => h('a', { href: '#' + id, 'aria-current': S.tab === id ? 'page' : null, text: label });
    return h('header', { class: 'top' }, h('div', { class: 'top-in' },
      h('span', { class: 'brand' }, h('img', { src: '/icon.svg', alt: '' }), 'MoneyTracker'),
      h('nav', { class: 'tabs', 'aria-label': 'Sections' }, tab('transactions', 'Transactions'), tab('analytics', 'Analytics'), tab('accounts', 'Accounts')),
      h('span', { class: 'phone', title: 'The phone serving this page' }, h('span', { class: 'dot' }), 'Phone · ' + location.hostname),
      h('button', { class: 'btn small', text: 'Sign out', onclick: signOut })));
  }

  let results = null;   // the part redrawn while typing a search
  function render() {
    document.title = { transactions: 'Transactions', analytics: 'Analytics', accounts: 'Accounts' }[S.tab] + ' · MoneyTracker';
    const main = h('main');
    results = h('div', { class: 'results' });
    if (S.tab === 'accounts') main.append(accountsView());
    else { main.append(periodBar(), filterBar(), results); }
    app.replaceChildren(header(), main);
    if (S.tab !== 'accounts') load();
  }

  // ───────────────────────── period and filters (shared by Transactions and Analytics)

  function periodLabel() {
    const f = S.f, y = ymY(f.ym), m = ymM(f.ym);
    if (f.period === 'month') return MONTHS[m - 1] + ' ' + y;
    if (f.period === '3m') { const s = ymAdd(f.ym, -2); return MON[ymM(s) - 1] + (ymY(s) !== y ? ' ' + ymY(s) : '') + ' – ' + MON[m - 1] + ' ' + y; }
    if (f.period === 'year') return String(y);
    return f.from && f.to ? shortDate(f.from) + ' – ' + shortDate(f.to) + ' ' + f.to.slice(0, 4) : 'Pick dates';
  }
  function step(n) {
    const f = S.f;
    f.ym = ymAdd(f.ym, n * (f.period === 'year' ? 12 : f.period === '3m' ? 3 : 1));
    S.shown = PAGE;
    render();
  }

  function periodBar() {
    const f = S.f;
    const custom = f.period === 'custom';
    const range = h('select', { 'aria-label': 'Date range', onchange: e => {
      f.period = e.target.value;
      if (f.period === 'custom' && !f.from) {
        const d = S.data;
        f.from = d ? d.from : S.meta.today.slice(0, 8) + '01';
        f.to = d ? d.to : S.meta.today;
      }
      S.shown = PAGE; render();
    } }, ...[['month', 'Month'], ['3m', 'Last 3 months'], ['year', 'Year'], ['custom', 'Custom dates…']]
      .map(([v, l]) => h('option', { value: v, selected: f.period === v, text: l })));
    const dates = custom ? [
      h('label', { class: 'field' }, 'From', h('input', { type: 'date', value: f.from, onchange: e => { f.from = e.target.value; render(); } })),
      h('label', { class: 'field' }, 'To', h('input', { type: 'date', value: f.to, onchange: e => { f.to = e.target.value; render(); } })),
    ] : [];
    const search = h('input', { type: 'search', placeholder: 'Search note, category, account or #tag', 'aria-label': 'Search entries', value: f.q,
      oninput: e => { f.q = e.target.value; S.shown = PAGE; drawResults(); } });
    search.id = 'search';
    return h('div', { class: 'bar' },
      h('div', { class: 'period' },
        h('button', { 'aria-label': 'Previous', disabled: custom, onclick: () => step(-1) }, icon(I.left)),
        h('span', { text: periodLabel() }),
        h('button', { 'aria-label': 'Next', disabled: custom, onclick: () => step(1) }, icon(I.right))),
      h('label', { class: 'field' }, 'Range', range),
      ...dates,
      h('label', { class: 'field search' }, icon(I.search), search),
      S.tab === 'transactions' ? h('button', { class: 'btn primary', onclick: () => openDialog(null), title: 'Add entry (N)' }, icon(I.plus), 'Add entry') : null);
  }

  function categoryOptions(kinds, selected, withAll) {
    const opts = withAll ? [h('option', { value: '', text: 'All categories' })] : [];
    const showSubs = S.meta.subcategories || (selected && cat(selected) && cat(selected).parentId);
    for (const k of kinds) {
      const mains = S.meta.categories.filter(c => c.kind === k && !c.parentId);
      if (!mains.length) continue;
      const g = h('optgroup', { label: k === 'INCOME' ? 'Income' : 'Expense' });
      for (const c of mains) {
        g.append(h('option', { value: c.id, selected: selected === c.id, text: c.name }));
        if (showSubs || withAll) S.meta.categories.filter(s => s.parentId === c.id)
          .forEach(s => g.append(h('option', { value: s.id, selected: selected === s.id, text: '   ' + c.name + ' › ' + s.name })));
      }
      opts.push(g);
    }
    return opts;
  }

  function accountOptions(selected, withAll) {
    const opts = withAll ? [h('option', { value: '', text: 'All accounts' })] : [];
    for (const g of S.meta.groups) {
      const list = S.meta.accounts.filter(a => a.groupId === g.id && (!a.hidden || a.id === selected));
      if (!list.length) continue;
      opts.push(h('optgroup', { label: g.name }, ...list.map(a => h('option', { value: a.id, selected: selected === a.id, text: a.name + (a.currency ? ' (' + a.currency + ')' : '') }))));
    }
    return opts;
  }

  function filterBar() {
    const f = S.f;
    const types = S.tab === 'analytics'
      ? [['expense', 'Expenses'], ['income', 'Income']]
      : [['all', 'All'], ['in', 'Income'], ['out', 'Expenses'], ['move', 'Transfers']];
    const current = S.tab === 'analytics' ? S.aKind : f.type;
    const seg = h('div', { class: 'seg', role: 'radiogroup', 'aria-label': 'Type' }, ...types.map(([k, l]) =>
      h('button', { role: 'radio', 'aria-checked': String(current === k), text: l, onclick: () => {
        if (S.tab === 'analytics') S.aKind = k; else f.type = k;
        S.shown = PAGE; render();
      } })));
    const pick = (label, opts, key) => h('label', { class: 'field' }, label,
      h('select', { 'aria-label': label, onchange: e => { f[key] = e.target.value; S.shown = PAGE; render(); } }, ...opts));
    const used = new Set();
    (S.data ? S.data.rows : []).forEach(r => r.tags.forEach(t => used.add(t)));
    f.tags.forEach(t => used.add(t));
    const tags = S.meta.tags.filter(t => used.has(t.id));
    const any = (S.tab !== 'analytics' && f.type !== 'all') || f.account || f.category || f.tags.length || f.q;
    return h('section', { class: 'card filters', 'aria-label': 'Filters' },
      seg,
      pick('Account', accountOptions(f.account, true), 'account'),
      pick('Category', categoryOptions(['EXPENSE', 'INCOME'], f.category, true), 'category'),
      tags.length ? h('span', { class: 'sep' }) : null,
      tags.length ? h('div', { class: 'chips', 'aria-label': 'Tags' }, ...tags.map(t => {
        const on = f.tags.includes(t.id);
        return h('button', { class: 'chip', 'aria-pressed': String(on), text: '#' + t.name, onclick: () => {
          f.tags = on ? f.tags.filter(x => x !== t.id) : f.tags.concat(t.id); S.shown = PAGE; render();
        } });
      })) : null,
      h('span', { class: 'grow' }),
      any ? h('button', { class: 'btn ghost small', text: 'Clear filters', onclick: () => {
        Object.assign(f, { type: 'all', account: '', category: '', tags: [], q: '' }); S.shown = PAGE; render();
      } }) : null);
  }

  function query() {
    const f = S.f;
    let q = 'period=' + f.period + '&ym=' + f.ym;
    if (f.period === 'custom') q += '&from=' + encodeURIComponent(f.from) + '&to=' + encodeURIComponent(f.to);
    return q;
  }

  async function load(silent) {
    const key = query();
    const wantSummary = S.tab === 'analytics';
    const sKey = 'ym=' + S.f.ym;
    if (!silent && S.dataKey === key && (!wantSummary || S.summaryKey === sKey)) return drawResults();
    if (!silent && S.dataKey !== key) results.replaceChildren(h('p', { class: 'empty', text: 'Loading…' }));
    try {
      const [d, s] = await Promise.all([
        api('/api/txns?' + key),
        wantSummary ? api('/api/summary?' + sKey) : Promise.resolve(S.summary),
      ]);
      const changed = JSON.stringify(d) !== JSON.stringify(S.data) || (wantSummary && JSON.stringify(s) !== JSON.stringify(S.summary));
      S.data = d; S.dataKey = key;
      if (wantSummary) { S.summary = s; S.summaryKey = sKey; }
      if (!silent || changed) drawResults();
    } catch (e) {
      if (e instanceof Stopped || e instanceof SignedOut) return;
      results.replaceChildren(h('p', { class: 'empty', text: e.message }));
    }
  }

  function filtered() {
    const f = S.f, rows = S.data ? S.data.rows : [];
    const q = f.q.trim().toLowerCase();
    const tagOnly = q.startsWith('#');
    const words = q.replace(/^#/, '').trim();
    return rows.filter(r => {
      if (S.tab === 'transactions' && f.type !== 'all') {
        const k = r.kind;
        if (f.type === 'in' && !(k === 'INCOME' || k === 'ADJUST_UP')) return false;
        if (f.type === 'out' && !((k === 'EXPENSE' && !r.transfer) || k === 'ADJUST_DOWN')) return false;
        if (f.type === 'move' && !(k === 'TRANSFER_OUT' || (k === 'EXPENSE' && r.transfer))) return false;
      }
      if (f.account && r.account !== f.account && r.to !== f.account) return false;
      if (f.category && r.category !== f.category && mainCatId(r.category) !== f.category) return false;
      if (f.tags.length && !r.tags.some(t => f.tags.includes(t))) return false;
      if (words) {
        const tagText = r.tags.map(tagName).join(' ');
        const hay = tagOnly ? tagText : [r.note, r.memo, catPath(r.category), accName(r.account), r.to ? accName(r.to) : '', tagText,
          toInput(Math.abs(r.entered), (curOf(r.currency) || mainCur()).decimals)].join(' ');
        if (!hay.toLowerCase().includes(words)) return false;
      }
      return true;
    });
  }

  function totals(rows) {
    let inc = 0, exp = 0;
    rows.forEach(r => { if (r.flow === 'income') inc += r.amount; else if (r.flow === 'expense') exp += r.amount; });
    return { inc, exp };
  }

  function drawResults() {
    if (!results || !S.data) return;
    if (S.tab === 'analytics') results.replaceChildren(analyticsView());
    else results.replaceChildren(transactionsView());
  }

  // ───────────────────────── Transactions

  function signedAmount(r) {
    switch (r.kind) {
      case 'INCOME': case 'ADJUST_UP': return { v: r.amount, cls: 'income', signed: true };
      case 'EXPENSE': return r.transfer ? { v: -r.amount, cls: '', signed: true } : { v: -r.amount, cls: r.amount < 0 ? 'income' : 'expense', signed: true };
      case 'ADJUST_DOWN': return { v: -r.amount, cls: 'expense', signed: true };
      default: return { v: r.amount, cls: '', signed: false };
    }
  }

  function transactionsView() {
    const rows = filtered();
    const t = totals(rows);
    const stat = (k, v, cls) => h('div', { class: 'card stat' }, h('span', { class: 'k', text: k }), h('span', { class: 'v ' + (cls || ''), text: v }));
    const stats = h('section', { class: 'stats', 'aria-label': 'Totals' },
      stat('Income', fmt(t.inc), 'income'), stat('Expenses', fmt(t.exp), 'expense'), stat('Net', fmt(t.inc - t.exp)), stat('Entries shown', String(rows.length)));

    const body = h('tbody');
    let day = null;
    rows.slice(0, S.shown).forEach(r => {
      if (r.date !== day) {
        day = r.date;
        const dt = totals(rows.filter(x => x.date === day));
        body.append(h('tr', { class: 'day' }, h('td', { colspan: '4', text: longDate(day) }),
          h('td', { class: 'num', colspan: '2' },
            dt.inc ? h('span', { class: 'income', text: fmt(dt.inc) }) : null, dt.inc && dt.exp ? '   ' : null,
            dt.exp ? h('span', { class: 'expense', text: fmt(dt.exp) }) : null)));
      }
      const a = signedAmount(r);
      const title = r.note || (r.kind === 'TRANSFER_OUT' ? 'Transfer' : catPath(r.category) || 'No note');
      const where = r.kind === 'TRANSFER_OUT' ? accName(r.account) + ' → ' + accName(r.to) : accName(r.account);
      const catText = r.kind === 'TRANSFER_OUT' ? 'Transfer' : (r.transfer ? 'Transfer fee' : catPath(r.category) || 'Uncategorized');
      const cur = curOf(r.currency);
      body.append(h('tr', { ondblclick: () => !r.lock && openDialog(r) },
        h('td', { class: 'note' }, h('div', { class: 't' }, title,
          r.repeat ? h('span', { class: 'badge', text: 'Repeat' }) : null,
          r.installment ? h('span', { class: 'badge', text: r.installment }) : null),
          r.memo ? h('div', { class: 'm', text: r.memo }) : null),
        h('td', { class: 'cat', text: catText }),
        h('td', { class: 'acct', text: where }),
        h('td', null, ...r.tags.map(id => h('span', { class: 'tag', text: '#' + tagName(id) }))),
        h('td', { class: 'num amt ' + a.cls }, fmt(a.v, null, a.signed),
          cur ? h('span', { class: 'orig', text: fmt(Math.abs(r.entered), cur) }) : null),
        h('td', { class: 'act' }, r.lock
          ? h('span', { class: 'icon-btn', title: r.lock, 'aria-label': r.lock }, icon(I.lock))
          : [h('button', { class: 'icon-btn', 'aria-label': 'Edit ' + title, title: 'Edit', onclick: () => openDialog(r) }, icon(I.edit)),
             h('button', { class: 'icon-btn danger', 'aria-label': 'Delete ' + title, title: 'Delete', onclick: () => remove(r) }, icon(I.trash))])));
    });
    const table = h('section', { class: 'card', 'aria-label': 'Entries' },
      rows.length ? h('div', { class: 'table-wrap' }, h('table', null,
        h('thead', null, h('tr', null, h('th', { scope: 'col', text: 'Note' }), h('th', { scope: 'col', class: 'cat', text: 'Category' }),
          h('th', { scope: 'col', class: 'acct', text: 'Account' }), h('th', { scope: 'col', text: 'Tags' }), h('th', { scope: 'col', class: 'num', text: 'Amount' }),
          h('th', { scope: 'col' }, h('span', { class: 'hidden', text: 'Actions' })))),
        body)) : h('p', { class: 'empty', text: S.data.rows.length ? 'No entries match these filters.' : 'No entries in this period.' }),
      rows.length > S.shown ? h('div', { class: 'more' }, h('button', { class: 'btn', text: 'Show ' + Math.min(PAGE, rows.length - S.shown) + ' more',
        onclick: () => { S.shown += PAGE; drawResults(); } })) : null);
    return h('div', { class: 'results' , style: { display: 'flex', flexDirection: 'column', gap: '14px' } }, stats, table,
      h('p', { class: 'foot', text: 'Changes save on the phone straight away, and the phone app shows them too. Press N to add an entry, / to search. Instalment plans and balance adjustments are edited on the phone.' }));
  }

  async function remove(r) {
    try {
      const res = await api('/api/txns/' + encodeURIComponent(r.id), { method: 'DELETE' });
      closeDialog();
      toast('Entry deleted', 'Undo', async () => {
        await quietly(api('/api/undo/' + res.undo, { method: 'POST', body: {} }).then(() => { toast('Entry restored'); return refresh(); }));
      });
      await refresh();
    } catch (e) { if (!(e instanceof Stopped) && !(e instanceof SignedOut)) toast(e.message); }
  }

  async function refresh() {
    S.dataKey = ''; S.summaryKey = '';
    if (S.tab === 'accounts') { S.accounts = null; render(); } else await load();
  }

  // ───────────────────────── add / edit dialog

  let lastAccount = null;
  try { lastAccount = localStorage.getItem('mt.lastAccount'); } catch (e) { /* storage blocked */ }

  function openDialog(r) {
    const visible = S.meta.accounts.filter(a => !a.hidden);
    const fallback = (lastAccount && acc(lastAccount) && !acc(lastAccount).hidden ? lastAccount : null) || (S.f.account) || (visible[0] || {}).id || '';
    const form = r ? {
      id: r.id,
      type: r.kind === 'TRANSFER_OUT' ? 'TRANSFER' : r.kind === 'INCOME' ? 'INCOME' : r.amount < 0 ? 'REFUND' : 'EXPENSE',
      date: r.date, account: r.account, to: r.to || '', category: r.category || '', note: r.note, memo: r.memo,
      tags: r.tags.map(tagName).filter(Boolean), currency: r.currency,
      amount: toInput(Math.abs(r.entered), (curOf(r.currency) || mainCur()).decimals),
    } : {
      id: null, type: 'EXPENSE', date: defaultDate(), account: fallback, to: '', category: S.f.category && cat(S.f.category) && cat(S.f.category).kind === 'EXPENSE' ? S.f.category : '',
      note: '', memo: '', tags: [], currency: null, amount: '',
    };
    S.dialog = { form, row: r };
    drawDialog();
  }

  function defaultDate() {
    const t = S.meta.today;
    if (S.data && (t < S.data.from || t > S.data.to)) return S.data.to < t ? S.data.to : S.data.from;
    return t;
  }

  let overlay = null;
  function closeDialog() { if (overlay) { overlay.remove(); overlay = null; } S.dialog = null; }

  function drawDialog() {
    const d = S.dialog, f = d.form;
    const move = f.type === 'TRANSFER';
    const err = h('p', { class: 'form-err', role: 'alert' });
    const cur = curOf(f.currency) || (f.id ? null : curOf((acc(f.account) || {}).currency));
    const kinds = h('div', { class: 'seg kinds', role: 'radiogroup', 'aria-label': 'Type' }, ...[['EXPENSE', 'Expense'], ['INCOME', 'Income'], ['TRANSFER', 'Transfer'], ['REFUND', 'Refund']]
      .map(([k, l]) => h('button', { type: 'button', role: 'radio', class: 'k-' + k, 'aria-checked': String(f.type === k), text: l, onclick: () => {
        const was = f.type; f.type = k;
        const wantKind = k === 'INCOME' ? 'INCOME' : 'EXPENSE';
        if (f.category && (!cat(f.category) || cat(f.category).kind !== wantKind)) f.category = '';
        if (was !== k) drawDialog();
      } })));
    const field = (label, el, wide) => h('label', { class: 'f' + (wide ? ' wide' : '') }, label, el);
    const bind = (el, key) => { el.addEventListener('input', () => { f[key] = el.value; }); el.addEventListener('change', () => { f[key] = el.value; }); return el; };
    const amount = bind(h('input', { class: 'amount-in', inputmode: 'decimal', autocomplete: 'off', value: f.amount, placeholder: '0.00' }), 'amount');
    const accountSel = h('select', { onchange: e => { f.account = e.target.value; if (!f.id) drawDialog(); } }, ...accountOptions(f.account, false));
    const toSel = bind(h('select', null, h('option', { value: '', text: 'Choose…' }), ...accountOptions(f.to, false)), 'to');
    const catSel = bind(h('select', null, h('option', { value: '', text: 'No category' }), ...categoryOptions([f.type === 'INCOME' ? 'INCOME' : 'EXPENSE'], f.category, false)), 'category');

    const tagList = h('div', { class: 'chips' });
    const tagInput = h('input', { placeholder: 'Add a tag and press Enter', list: 'mt-tags', autocomplete: 'off' });
    function drawTags() {
      tagList.replaceChildren(...f.tags.map(n => h('button', { type: 'button', class: 'chip', 'aria-pressed': 'true', title: 'Remove', text: '#' + n + '  ×',
        onclick: () => { f.tags = f.tags.filter(x => x !== n); drawTags(); } })));
    }
    function addTag() {
      const n = tagInput.value.replace(/^#/, '').replace(/,/g, ' ').trim();
      if (n && !f.tags.some(x => x.toLowerCase() === n.toLowerCase())) f.tags.push(n);
      tagInput.value = '';
      drawTags();
    }
    tagInput.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); e.stopPropagation(); if (tagInput.value.trim()) addTag(); else save(); } });
    tagInput.addEventListener('change', () => { if (tagInput.value.trim()) addTag(); });
    drawTags();

    const notes = [...new Set((S.data ? S.data.rows : []).map(r => r.note).filter(Boolean))].slice(0, 200);
    const saveBtn = h('button', { class: 'btn primary', type: 'submit', text: f.id ? 'Save changes' : 'Save' });

    async function save() {
      if (tagInput.value.trim()) addTag();
      f.amount = amount.value;
      err.textContent = '';
      saveBtn.disabled = true;
      const body = { type: f.type, date: f.date, amount: f.amount, account: f.account, to: move ? f.to : '', category: move ? '' : f.category, note: f.note, memo: f.memo, tags: f.tags };
      try {
        if (f.id) await api('/api/txns/' + encodeURIComponent(f.id), { method: 'PUT', body });
        else await api('/api/txns', { method: 'POST', body });
        try { localStorage.setItem('mt.lastAccount', f.account); lastAccount = f.account; } catch (e) { /* storage blocked */ }
        closeDialog();
        toast(f.id ? 'Changes saved' : 'Entry added');
        await refresh();
      } catch (e) {
        if (e instanceof Stopped || e instanceof SignedOut) return;
        saveBtn.disabled = false;
        err.textContent = e.message;
      }
    }

    const dialog = h('form', { class: 'dialog', role: 'dialog', 'aria-modal': 'true', 'aria-label': f.id ? 'Edit entry' : 'Add entry', onsubmit: e => { e.preventDefault(); save(); } },
      h('div', { class: 'dialog-head' }, h('h2', { text: f.id ? 'Edit entry' : 'Add entry' }),
        h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Close', onclick: closeDialog }, icon(I.x))),
      kinds,
      h('div', { class: 'form' },
        field('Date', bind(h('input', { type: 'date', value: f.date, required: true }), 'date')),
        field('Amount' + (cur ? ' (' + cur.code + ')' : ' (' + mainCur().code + ')'), amount),
        field(move ? 'From' : 'Account', accountSel),
        move ? field('To', toSel) : field('Category', catSel),
        field('Note', bind(h('input', { value: f.note, placeholder: 'What was it for?', list: 'mt-notes', maxlength: '500' }), 'note'), true),
        field('Description', bind(h('textarea', { maxlength: '2000', placeholder: 'Optional details' }, f.memo), 'memo'), true),
        h('div', { class: 'f wide' }, h('label', { class: 'f' }, 'Tags', tagInput), tagList)),
      h('datalist', { id: 'mt-notes' }, ...notes.map(n => h('option', { value: n }))),
      h('datalist', { id: 'mt-tags' }, ...S.meta.tags.map(t => h('option', { value: t.name }))),
      f.id && d.row && d.row.repeat ? h('p', { class: 'hint', text: 'This entry was added by a repeat. Changing it changes only this one.' }) : null,
      err,
      h('div', { class: 'dialog-actions' },
        f.id ? h('button', { type: 'button', class: 'btn danger', text: 'Delete', onclick: () => remove(d.row) }) : null,
        h('span', { class: 'grow' }),
        h('button', { type: 'button', class: 'btn', text: 'Cancel', onclick: closeDialog }),
        saveBtn),
      h('span', { class: 'hint', text: 'Enter saves · Esc closes · same categories, accounts and tags as the phone.' }));
    const focusAmount = !overlay;
    if (overlay) overlay.remove();
    overlay = h('div', { class: 'overlay', onmousedown: e => { if (e.target === overlay) closeDialog(); } }, dialog);
    document.body.append(overlay);
    if (focusAmount) amount.focus();
  }

  // ───────────────────────── Analytics

  function analyticsView() {
    const rows = filtered();
    const t = totals(rows);
    const kind = S.aKind;
    const sum = S.summary;
    const today = S.meta.today;
    const from = S.data.from, to = S.data.to;
    const end = today < to ? today : to;
    const days = today < from ? 0 : Math.round((parseDate(end) - parseDate(from)) / 864e5) + 1;
    const totalBudget = S.f.period === 'month' && sum && sum.budgets.find(b => !b.category);
    const stat = (k, v, s, cls) => h('div', { class: 'card stat' }, h('span', { class: 'k', text: k }), h('span', { class: 'v ' + (cls || ''), text: v }), s ? h('span', { class: 's', text: s }) : null);
    const saved = t.inc > 0 ? Math.round((t.inc - t.exp) / t.inc * 100) : null;
    const stats = h('section', { class: 'stats', 'aria-label': 'Totals' },
      stat('Income', fmt(t.inc), null, 'income'),
      stat('Expenses', fmt(t.exp), null, 'expense'),
      stat('Net', fmt(t.inc - t.exp), saved !== null ? 'You kept ' + saved + '% of income' : null),
      stat('Daily average spend', days > 0 ? fmt(Math.round(t.exp / days)) : '—', days > 0 ? days + (days === 1 ? ' day' : ' days') + (to > today ? ' so far' : '') : 'Not started yet'),
      totalBudget ? stat('Budget left', fmt(totalBudget.budget - totalBudget.spent), 'of ' + fmt(totalBudget.budget), totalBudget.spent > totalBudget.budget ? 'expense' : '') : null);

    // Breakdown by main category.
    const flowRows = rows.filter(r => r.flow === kind);
    const groups = new Map();
    flowRows.forEach(r => {
      let key, label, filter = null;
      if (r.kind === 'TRANSFER_OUT') { key = 'move:' + r.to; label = 'Transfers to ' + accName(r.to); }
      else if (!r.category) { key = 'none'; label = 'Uncategorized'; }
      else { key = mainCatId(r.category); label = (cat(key) || {}).name || 'Deleted category'; filter = cat(key) && cat(key).kind !== 'SYSTEM' ? key : null; }
      const g = groups.get(key) || { label, filter, v: 0, n: 0 };
      g.v += r.amount; g.n++;
      groups.set(key, g);
    });
    let list = [...groups.values()].filter(g => g.v > 0).sort((a, b) => b.v - a.v);
    if (list.length > 8) {
      const rest = list.slice(7);
      list = list.slice(0, 7).concat({ label: 'Other', filter: null, v: rest.reduce((a, g) => a + g.v, 0), n: rest.reduce((a, g) => a + g.n, 0) });
    }
    const total = list.reduce((a, g) => a + g.v, 0);
    const C = 2 * Math.PI * 64;
    const donut = svg('svg', { width: '180', height: '180', viewBox: '0 0 180 180', role: 'img', 'aria-label': list.map(g => g.label + ' ' + Math.round(g.v / total * 100) + '%').join(', ') || 'No data' });
    donut.append(svg('circle', { cx: '90', cy: '90', r: '64', fill: 'none', stroke: 'var(--line)', 'stroke-width': '26' }));
    let accLen = 0;
    list.forEach((g, i) => {
      const len = g.v / total * C;
      donut.append(svg('circle', { cx: '90', cy: '90', r: '64', fill: 'none', stroke: PALETTE[i % PALETTE.length], 'stroke-width': '26',
        'stroke-dasharray': len.toFixed(2) + ' ' + (C - len).toFixed(2), 'stroke-dashoffset': (-accLen).toFixed(2), transform: 'rotate(-90 90 90)' }));
      accLen += len;
    });
    donut.append(svg('text', { x: '90', y: '88', 'text-anchor': 'middle', 'font-size': '15', 'font-weight': '600', fill: 'currentColor' }, fmt(total).replace(/\.\d+$/, '').replace(/\.\d+ /, ' ')));
    donut.append(svg('text', { x: '90', y: '108', 'text-anchor': 'middle', 'font-size': '12', fill: 'var(--muted)' }, kind === 'expense' ? 'expenses' : 'income'));
    const toTxns = filterId => { if (filterId) S.f.category = filterId; S.f.type = kind === 'expense' ? 'out' : 'in'; go('transactions'); };
    const breakdown = h('section', { class: 'card panel', 'aria-label': kind === 'expense' ? 'Spending by category' : 'Income by category' },
      h('h2', { text: kind === 'expense' ? 'Spending by category' : 'Income by category' }),
      list.length ? h('div', { class: 'donut-row' }, donut, h('ul', { class: 'legend' }, ...list.map((g, i) => h('li', null,
        h('button', { disabled: !g.filter, title: g.filter ? 'See these entries' : null, onclick: () => toTxns(g.filter) },
          h('span', { class: 'sw', style: { background: PALETTE[i % PALETTE.length] } }),
          h('span', { class: 'name', text: g.label }),
          h('span', { class: 'pct', text: Math.round(g.v / total * 100) + '%' }),
          h('span', { class: 'amt', text: fmt(g.v) })))))) : h('p', { class: 'empty', text: 'Nothing here for these filters.' }),
      list.length ? h('span', { class: 'foot', text: 'Click a category to see its entries in Transactions.' }) : null);

    // Six months, not filtered.
    let trend = null;
    if (sum) {
      const max = Math.max(1, ...sum.trend.map(m => Math.max(m.income, m.expense)));
      const cols = 'repeat(' + sum.trend.length + ', minmax(0, 1fr))';
      const kept = sum.trend.filter(m => m.income > 0);
      const avg = kept.length ? Math.round(kept.reduce((a, m) => a + (m.income - m.expense) / m.income, 0) / kept.length * 100) : null;
      trend = h('section', { class: 'card panel', 'aria-label': 'Six-month trend' },
        h('h2', null, h('span', { class: 'grow', text: 'Income vs expenses, 6 months' }),
          h('span', { class: 'key' }, h('i', { style: { background: 'var(--income)' } }), 'Income'),
          h('span', { class: 'key' }, h('i', { style: { background: 'var(--warn)' } }), 'Expenses')),
        h('div', { class: 'bars', style: { gridTemplateColumns: cols } }, ...sum.trend.map(m => h('div', { class: 'col' },
          h('span', { class: 'b in', title: MONTHS[ymM(m.ym) - 1] + ': income ' + fmt(m.income), style: { height: (m.income / max * 100).toFixed(1) + '%' } }),
          h('span', { class: 'b out', title: MONTHS[ymM(m.ym) - 1] + ': expenses ' + fmt(m.expense), style: { height: (m.expense / max * 100).toFixed(1) + '%' } })))),
        h('div', { class: 'bars-labels', style: { gridTemplateColumns: cols } }, ...sum.trend.map(m => h('span', { text: MON[ymM(m.ym) - 1] }))),
        h('span', { class: 'foot', text: 'All accounts, ending ' + MONTHS[ymM(S.f.ym) - 1] + ' ' + ymY(S.f.ym) + '; filters above don’t apply.' + (avg !== null ? ' You kept ' + avg + '% of income on average.' : '') }));
    }

    let budgets = null;
    if (sum && sum.budgets.length) {
      budgets = h('section', { class: 'card panel', 'aria-label': 'Budgets' },
        h('h2', { text: 'Budgets, ' + MONTHS[ymM(sum.ym) - 1] }),
        ...sum.budgets.map(b => {
          const r = b.budget > 0 ? b.spent / b.budget : 0;
          return h('div', { class: 'budget' },
            h('div', { class: 'line' }, h('span', { text: b.name }), h('span', { class: 'muted', text: fmt(b.spent) + ' of ' + fmt(b.budget) })),
            h('div', { class: 'meter' }, h('div', { class: r > 1 ? 'over' : r > 0.8 ? 'warn' : '', style: { width: Math.min(100, r * 100).toFixed(0) + '%' } })));
        }));
    }

    // Top categories at their own level (sub-categories when used).
    const subs = new Map();
    flowRows.filter(r => r.category && r.kind !== 'TRANSFER_OUT').forEach(r => {
      const g = subs.get(r.category) || { id: r.category, v: 0, n: 0 };
      g.v += r.amount; g.n++; subs.set(r.category, g);
    });
    const tops = [...subs.values()].filter(g => g.v > 0).sort((a, b) => b.v - a.v).slice(0, 8);
    const top = h('section', { class: 'card panel top-list', 'aria-label': 'Top categories' },
      h('h2', { text: kind === 'expense' ? 'Where most of it went' : 'Where most of it came from' }),
      tops.length ? h('div', null, ...tops.map(g => h('button', { onclick: () => toTxns(cat(g.id) && cat(g.id).kind !== 'SYSTEM' ? g.id : null) },
        h('span', { class: 'name', text: catPath(g.id) }), h('span', { class: 'muted small', text: g.n + (g.n === 1 ? ' entry' : ' entries') }),
        h('span', { class: 'amt', text: fmt(g.v) })))) : h('p', { class: 'empty', text: 'Nothing here for these filters.' }));

    return h('div', { style: { display: 'flex', flexDirection: 'column', gap: '14px' } }, stats,
      h('div', { class: 'grid2' }, breakdown, trend), h('div', { class: 'grid2' }, budgets, top));
  }

  // ───────────────────────── Accounts

  function accountsView() {
    const wrap = h('div', { style: { display: 'flex', flexDirection: 'column', gap: '14px' } });
    const draw = () => {
      const a = S.accounts;
      const exportPanel = S.exportOpen ? exportView() : null;
      const head = h('div', { class: 'bar' },
        h('h1', { class: 'page', text: 'Accounts' }),
        h('span', { class: 'muted grow', text: 'Balances as of today, ' + longDate(a.today) }),
        h('button', { class: 'btn', 'aria-expanded': String(S.exportOpen), onclick: () => { S.exportOpen = !S.exportOpen; draw(); } }, icon(I.download), 'Export CSV'));
      const stat = (k, v, cls) => h('div', { class: 'card stat' }, h('span', { class: 'k', text: k }), h('span', { class: 'v ' + (cls || ''), text: v }));
      const groups = a.groups.map(g => h('section', { class: 'card group', 'aria-label': g.name },
        h('div', { class: 'group-head' }, h('h2', { text: g.name }),
          g.debit ? h('span', { class: 'muted small', text: 'Spends from the linked bank account' }) : h('span', { class: 'total ' + (g.total < 0 ? 'expense' : 'income'), text: fmt(g.total) })),
        ...g.accounts.map(x => {
          const cur = curOf(x.currency);
          let sub = x.memo;
          if (x.card && x.card.due > 0) sub = 'Due now ' + fmt(x.card.due) + (x.card.payDay ? ' · pay by the ' + ordinal(x.card.payDay) : '');
          else if (x.card) sub = 'Nothing due' + (x.card.closeDay ? ' · statement closes on the ' + ordinal(x.card.closeDay) : '');
          if (!x.inTotals) sub = (sub ? sub + ' · ' : '') + 'not in totals';
          return h('button', { class: 'acc', title: 'See this account’s entries', onclick: () => { S.f.account = x.id; S.f.type = 'all'; go('transactions'); } },
            h('span', { class: 'nm' }, h('span', { text: x.name }), sub ? h('span', { text: sub }) : null),
            h('span', { class: 'bal ' + (x.balance < 0 ? 'expense' : ''), text: fmt(x.balance, cur) }));
        })));
      wrap.replaceChildren(...[head, exportPanel].filter(Boolean),
        h('section', { class: 'stats', 'aria-label': 'Net worth' }, stat('Assets', fmt(a.assets), 'income'), stat('Liabilities', fmt(a.liabilities), 'expense'), stat('Net worth', fmt(a.net))),
        h('div', { class: 'acc-grid' }, ...groups),
        h('p', { class: 'foot', text: 'Click an account to see its entries in Transactions. Accounts are added and edited on the phone.' }));
    };
    if (S.accounts) draw(); else wrap.append(h('p', { class: 'empty', text: 'Loading…' }));
    api('/api/accounts').then(a => {
      const changed = JSON.stringify(a) !== JSON.stringify(S.accounts);
      S.accounts = a;
      if (changed && S.tab === 'accounts') draw();
    }).catch(e => { if (!(e instanceof Stopped) && !(e instanceof SignedOut)) wrap.replaceChildren(h('p', { class: 'empty', text: e.message })); });
    return wrap;
  }

  function ordinal(n) { const s = ['th', 'st', 'nd', 'rd'], v = n % 100; return n + (s[(v - 20) % 10] || s[v] || s[0]); }

  function exportView() {
    const ex = S.export || (S.export = { from: (S.data && S.data.from) || S.meta.today.slice(0, 8) + '01', to: (S.data && S.data.to) || S.meta.today, account: '' });
    const link = h('a', { class: 'btn primary', download: '', text: 'Download CSV' });
    const update = () => { link.href = '/api/export.csv?period=custom&from=' + ex.from + '&to=' + ex.to + '&account=' + encodeURIComponent(ex.account); };
    update();
    return h('section', { class: 'card export', 'aria-label': 'Export' },
      h('label', { class: 'field' }, 'From', h('input', { type: 'date', value: ex.from, onchange: e => { ex.from = e.target.value; update(); } })),
      h('label', { class: 'field' }, 'To', h('input', { type: 'date', value: ex.to, onchange: e => { ex.to = e.target.value; update(); } })),
      h('label', { class: 'field' }, 'Account', h('select', { onchange: e => { ex.account = e.target.value; update(); } }, ...accountOptions(ex.account, true))),
      link,
      h('span', { class: 'foot', text: 'The file is made on the phone and downloaded by this browser. It opens in Excel, Numbers or Google Sheets, and MoneyTracker can import it again.' }));
  }

  // ───────────────────────── toast, keys, auto-refresh

  let toastEl = null;
  function toast(text, action, onAction) {
    if (toastEl) toastEl.remove();
    clearTimeout(S.toastTimer);
    toastEl = h('div', { class: 'toast', role: 'status' }, h('span', { text }),
      action ? h('button', { text: action, onclick: () => { toastEl.remove(); toastEl = null; onAction(); } }) : null);
    document.body.append(toastEl);
    S.toastTimer = setTimeout(() => { if (toastEl) { toastEl.remove(); toastEl = null; } }, action ? 10000 : 3000);
  }

  document.addEventListener('keydown', e => {
    if (!S.meta) return;
    if (e.key === 'Escape' && S.dialog) { e.preventDefault(); closeDialog(); return; }
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test((e.target && e.target.tagName) || '');
    if (typing || S.dialog || e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.key === 'n' || e.key === 'N') { e.preventDefault(); if (S.tab !== 'transactions') go('transactions'); openDialog(null); }
    else if (e.key === '/') { const s = document.getElementById('search'); if (s) { e.preventDefault(); s.focus(); } }
  });

  // Changes made on the phone show up here within a few seconds.
  setInterval(() => {
    if (!S.meta || document.visibilityState !== 'visible' || S.dialog) return;
    if (S.tab === 'accounts') { if (!S.exportOpen) quietly(api('/api/accounts').then(a => { if (JSON.stringify(a) !== JSON.stringify(S.accounts)) { S.accounts = a; render(); } })); }
    else load(true);
  }, 15000);
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible' && S.meta && !S.dialog && S.tab !== 'accounts') load(true); });

  boot();
})();
