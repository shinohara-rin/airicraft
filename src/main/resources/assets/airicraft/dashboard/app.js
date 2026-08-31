const state = {
  token: new URLSearchParams(location.hash.slice(1)).get('token') || '',
  observations: [],
  bySequence: new Map(),
  observationCharacters: new Map(),
  loadedCharacters: 0,
  metadata: null,
  partialHistory: false,
  live: true,
  selectedSequence: 0,
  selectedObservation: null,
  view: 'overview',
  search: '',
  streamAbort: null,
  replay: false,
};

const CLIENT_HISTORY_CHARACTER_BUDGET = 4 * 1024 * 1024;
const INITIAL_SEQUENCE_WINDOW = 500;
const el = id => document.getElementById(id);
const fmt = new Intl.NumberFormat();
const timeFmt = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3 });

function authHeaders() { return { Authorization: `Bearer ${state.token}` }; }
async function api(path) {
  const response = await fetch(path, { headers: authHeaders(), cache: 'no-store' });
  if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
  return response;
}

function safe(value, fallback = '—') {
  return value === undefined || value === null || value === '' ? fallback : value;
}
function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>'"]/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', "'":'&#39;', '"':'&quot;' }[c]));
}
function pretty(value) { return JSON.stringify(value, null, 2); }
function bytes(value) {
  if (!Number.isFinite(value)) return '—';
  const units = ['B', 'KB', 'MB', 'GB']; let n = value; let i = 0;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(i ? 1 : 0)} ${units[i]}`;
}
function duration(ms) {
  if (!Number.isFinite(ms) || ms < 0) return 'pending';
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(2)} s`;
}

function addObservations(items, shouldRender = true) {
  let changed = false;
  for (const item of items || []) {
    if (state.bySequence.has(item.sequence)) continue;
    const characters = JSON.stringify(item).length;
    state.bySequence.set(item.sequence, item);
    state.observationCharacters.set(item.sequence, characters);
    state.loadedCharacters += characters;
    state.observations.push(item);
    changed = true;
  }
  if (!changed) return;
  state.observations.sort((a, b) => a.sequence - b.sequence);
  trimClientHistory();
  if (state.live) state.selectedSequence = state.observations.at(-1)?.sequence || 0;
  if (!shouldRender) return;
  updateChrome();
  render();
}

function trimClientHistory() {
  let dropCount = 0;
  while (state.loadedCharacters > CLIENT_HISTORY_CHARACTER_BUDGET && dropCount < state.observations.length - 1) {
    const item = state.observations[dropCount++];
    state.loadedCharacters -= state.observationCharacters.get(item.sequence) || 0;
    state.observationCharacters.delete(item.sequence);
    state.bySequence.delete(item.sequence);
  }
  if (!dropCount) return;
  state.partialHistory = true;
  state.observations.splice(0, dropCount);
  if (state.selectedObservation && !state.bySequence.has(state.selectedObservation.sequence)) {
    state.selectedObservation = null;
    el('inspector-content').innerHTML = '<p class="muted">The selected observation moved outside the recent browser window.</p>';
  }
}

function resetObservations() {
  state.observations = [];
  state.bySequence.clear();
  state.observationCharacters.clear();
  state.loadedCharacters = 0;
  state.selectedObservation = null;
  state.selectedSequence = 0;
}

function updateMetadata(data) {
  const metadata = { ...(data || {}) };
  delete metadata.observations;
  state.metadata = { ...(state.metadata || {}), ...metadata };
  updateChrome();
}

function updateChrome() {
  const latest = state.observations.at(-1);
  const meta = state.metadata || {};
  el('tick').textContent = fmt.format(latest?.tick ?? meta.latestTick ?? 0);
  el('session').textContent = String(meta.sessionId || latest?.sessionId || '—').slice(0, 8);
  el('memory').textContent = `${bytes(meta.retainedBytes)} retained · ~${bytes(state.loadedCharacters)} loaded`;
  el('llm-count').textContent = state.observations.filter(o => o.type === 'llm_call').length;
  el('event-count').textContent = state.observations.filter(o => o.type === 'semantic_event' || o.type === 'debug_timeline').length;
  el('log-count').textContent = state.observations.filter(o => o.type === 'log').length;
  const cursor = el('time-cursor');
  cursor.min = state.observations[0]?.sequence || 0;
  cursor.max = latest?.sequence || 0;
  cursor.value = state.selectedSequence || cursor.max;
  const selected = observationAtCursor();
  el('cursor-label').textContent = state.live
    ? 'Following the latest observation'
    : selected ? `${timeFmt.format(selected.capturedAtMs)} · tick ${fmt.format(selected.tick)} · #${selected.sequence}` : 'Historical cursor';
  el('return-live').classList.toggle('active', state.live);
}

function observationAtCursor() {
  if (!state.observations.length) return null;
  let result = state.observations[0];
  for (const item of state.observations) {
    if (item.sequence > state.selectedSequence) break;
    result = item;
  }
  return result;
}

function snapshotAtCursor() {
  let result = null;
  for (const item of state.observations) {
    if (item.sequence > state.selectedSequence) break;
    if (item.type === 'runtime_snapshot') result = item;
  }
  return result;
}

function matches(item) {
  if (!state.search) return true;
  return JSON.stringify(item).toLowerCase().includes(state.search);
}

function render() {
  const snapshot = snapshotAtCursor();
  if (!snapshot && state.view !== 'timeline' && state.view !== 'logs' && state.view !== 'raw' && state.view !== 'transcript') {
    el('content').innerHTML = `<div class="empty-state"><div class="loader"></div><h2>Waiting for the first runtime snapshot</h2><p>Transitions and transcripts will appear as soon as Airicraft emits them.</p></div>`;
    return;
  }
  ({ overview: renderOverview, transcript: renderTranscript, timeline: renderTimeline, runtime: renderRuntime, logs: renderLogs, raw: renderRaw })[state.view](snapshot);
}

function renderOverview(snapshot) {
  const p = snapshot.payload || {};
  const agent = p.agent || {};
  const planner = p.planner || {};
  const task = p.task || {};
  const action = p.actionGraph || {};
  const world = p.world || {};
  const player = world.player || {};
  const lastEvents = state.observations.filter(o => o.sequence <= state.selectedSequence && ['semantic_event','debug_timeline','observation_gap'].includes(o.type) && matches(o)).slice(-12).reverse();
  const calls = currentLlmCalls().slice(-4).reverse();
  const frame = state.observations.filter(o => o.sequence <= state.selectedSequence && o.type === 'visual_frame').at(-1);
  const dropped = state.metadata?.droppedByType || {};
  const hasDrops = Object.values(dropped).some(Number);
  const warnings = [
    hasDrops ? `Server history has evicted observations: ${escapeHtml(JSON.stringify(dropped))}` : '',
    state.partialHistory ? 'Showing a recent browser window to stay responsive. Save session exports the full retained history.' : '',
  ].filter(Boolean);
  el('content').innerHTML = `
    ${warnings.map(warning => `<div class="warning">${warning}</div>`).join('')}
    <div class="grid summary-grid" style="margin-top:${warnings.length ? '12px' : '0'}">
      ${statCard('Planner', safe(planner.phase || planner.sessionPhase || (p.degraded ? 'DEGRADED' : 'READY')), `enabled ${p.plannerEnabled}`, 'cyan')}
      ${statCard('Active task', safe(task.status || task.state || agent.task?.status, 'IDLE'), safe(task.mission?.summary || task.summary || task.mission?.goal, 'No active mission'), 'blue')}
      ${statCard('Action graph', `${safe(action.nonterminalCount, 0)} active`, safe(action.foregroundExecutionId, 'No foreground graph'), 'amber')}
      ${statCard('World', world.loaded ? safe(world.dimension, 'loaded') : 'NOT LOADED', world.loaded ? `${Number(player.x || 0).toFixed(1)}, ${Number(player.y || 0).toFixed(1)}, ${Number(player.z || 0).toFixed(1)}` : 'Waiting for a world', 'purple')}
    </div>
    <div class="grid two-col" style="margin-top:12px">
      <section class="card">
        <div class="card-head"><h2>Causal timeline</h2><small>latest transitions</small></div>
        <div class="timeline">${lastEvents.length ? lastEvents.map(timelineRow).join('') : '<p class="muted card-body">No transitions in the retained window.</p>'}</div>
      </section>
      <div class="grid">
        ${frame ? `<section class="card"><div class="card-head"><h2>Visual context</h2><small>tick ${fmt.format(frame.tick)} · higher-cost capture</small></div><img class="visual-frame selectable" data-sequence="${frame.sequence}" src="data:image/${escapeHtml(frame.payload.format)};base64,${frame.payload.imageBase64}" alt="Minecraft frame at tick ${frame.tick}"></section>` : ''}
        <section class="card">
          <div class="card-head"><h2>Embodied state</h2><small>tick ${fmt.format(snapshot.tick)}</small></div>
          <div class="card-body">${kv({
            'session mode': agent.session?.mode,
            'runtime initialized': agent.initialized,
            'active job': p.activeJob?.status || p.activeJob?.jobId,
            'task execution': p.taskExecution?.state || p.taskExecution?.phase,
            'mission': p.missionExecution?.state || p.missionExecution?.status,
            'survival reflex': p.reflex?.state,
            'behavior tree': p.behaviorTree?.status || p.behaviorTree?.state,
            'health / food / air': world.loaded ? `${player.health}/${player.maxHealth} · ${player.food} · ${player.air}` : null,
          })}</div>
        </section>
        <section class="card">
          <div class="card-head"><h2>Recent LLM calls</h2><small>${calls.length} shown</small></div>
          <div class="card-body transcript">${calls.length ? calls.map(callSummary).join('') : '<p class="muted">No LLM calls recorded.</p>'}</div>
        </section>
      </div>
    </div>`;
  bindSelectable();
}

function statCard(label, value, note, accent) {
  return `<section class="card stat accent-${accent}"><label>${escapeHtml(label)}</label><strong>${escapeHtml(value)}</strong><small>${escapeHtml(note)}</small></section>`;
}
function kv(entries) {
  return `<dl class="kv">${Object.entries(entries).map(([k,v]) => `<dt>${escapeHtml(k)}</dt><dd>${escapeHtml(safe(v))}</dd>`).join('')}</dl>`;
}

function renderTranscript() {
  const calls = currentLlmCalls().filter(matches).reverse();
  el('content').innerHTML = `<section class="card"><div class="card-head"><h2>Full LLM transcript</h2><small>${calls.length} lifecycle records · raw envelopes retained</small></div><div class="card-body transcript">${calls.length ? calls.map(renderCall).join('') : '<p class="muted">No matching LLM calls.</p>'}</div></section>`;
  bindSelectable();
}
function currentLlmCalls() {
  const latestById = new Map();
  for (const item of state.observations) {
    if (item.sequence > state.selectedSequence || item.type !== 'llm_call') continue;
    latestById.set(item.payload?.sequenceId ?? item.sequence, item);
  }
  return [...latestById.values()];
}
function callSummary(item) {
  const p = item.payload || {};
  return `<div class="call-header selectable" data-sequence="${item.sequence}"><span class="badge llm_call">${escapeHtml(p.status)}</span><strong>${escapeHtml(safe(p.model || p.requestKind, 'LLM call'))}</strong><small>${duration((p.completedAtMs || Date.now()) - p.requestedAtMs)}</small></div>`;
}
function renderCall(item) {
  const p = item.payload || {};
  let request = null;
  try { request = JSON.parse(p.requestBody || 'null'); } catch {}
  const messages = request?.messages || [];
  return `<article class="call">
    <div class="call-header selectable" data-sequence="${item.sequence}">
      <span class="badge llm_call">${escapeHtml(p.status)}</span>
      <strong>${escapeHtml(safe(p.model || p.providerName, p.requestKind))}</strong>
      <small>#${p.sequenceId} · ${duration((p.completedAtMs || Date.now()) - p.requestedAtMs)} · ${escapeHtml(safe(p.usage?.totalTokens, '?'))} tok</small>
    </div>
    <div class="call-body">
      ${messages.map(messageHtml).join('') || '<p class="muted">Request body is not a chat envelope; use raw details below.</p>'}
      ${p.parsedResponse ? `<div class="message assistant"><label>parsed response</label><pre>${escapeHtml(typeof p.parsedResponse === 'string' ? p.parsedResponse : pretty(p.parsedResponse))}</pre></div>` : ''}
      ${p.failureMessage ? `<div class="message"><label class="error">${escapeHtml(p.failureType)}</label><pre>${escapeHtml(p.failureMessage)}</pre></div>` : ''}
    </div>
    <details><summary>Exact request envelope</summary><div class="card-body"><pre class="json">${escapeHtml(pretty(request ?? p.requestBody))}</pre></div></details>
    <details><summary>Exact raw response</summary><div class="card-body"><pre class="json">${escapeHtml(p.rawResponseBody || 'No raw response yet')}</pre></div></details>
  </article>`;
}
function messageHtml(message) {
  const role = message.role || 'unknown';
  const content = typeof message.content === 'string' ? message.content : pretty(message.content);
  const extras = message.tool_calls ? `\n\ntool_calls:\n${pretty(message.tool_calls)}` : '';
  return `<div class="message ${escapeHtml(role)}"><label>${escapeHtml(role)}</label><pre>${escapeHtml(content + extras)}</pre></div>`;
}

function renderTimeline() {
  const items = state.observations.filter(o => o.sequence <= state.selectedSequence && o.type !== 'runtime_snapshot' && o.type !== 'log' && matches(o)).slice().reverse();
  el('content').innerHTML = `<section class="card"><div class="card-head"><h2>Causal timeline</h2><small>${items.length} observations</small></div><div class="timeline">${items.length ? items.map(timelineRow).join('') : '<p class="muted card-body">No matching observations.</p>'}</div></section>`;
  bindSelectable();
}
function timelineRow(item) {
  const p = item.payload || {};
  const summary = p.summary || p.type || p.action || p.message || p.reason || item.type;
  return `<div class="timeline-row selectable ${state.selectedObservation?.sequence === item.sequence ? 'selected' : ''}" data-sequence="${item.sequence}"><time>${timeFmt.format(item.capturedAtMs)}</time><span class="badge ${item.type}">${escapeHtml(item.type.replaceAll('_',' '))}</span><p>${escapeHtml(summary)}</p><span class="sequence">#${item.sequence}</span></div>`;
}

function renderRuntime(snapshot) {
  const p = snapshot.payload || {};
  const sections = ['agent','planner','activeGoal','activeJob','task','taskExecution','missionExecution','reflex','actionGraph','behaviorTree','eventPipeline','dialogueState','conversationSources','world','observability'];
  el('content').innerHTML = `<div class="grid two-col">${sections.map(key => `<section class="card"><div class="card-head"><h2>${escapeHtml(key.replace(/([A-Z])/g,' $1'))}</h2><small>snapshot #${snapshot.sequence}</small></div><div class="card-body"><pre class="json">${escapeHtml(pretty(p[key]))}</pre></div></section>`).join('')}</div>`;
}

function renderLogs() {
  const logs = state.observations.filter(o => o.sequence <= state.selectedSequence && o.type === 'log' && matches(o)).slice().reverse();
  el('content').innerHTML = `<section class="card"><div class="card-head"><h2>Minecraft / Airicraft logs</h2><small>${logs.length} retained lines</small></div>${logs.length ? logs.map(o => `<div class="log-line selectable" data-sequence="${o.sequence}"><time>${timeFmt.format(o.capturedAtMs)}</time><span>${escapeHtml(o.payload?.message)}</span></div>`).join('') : '<p class="muted card-body">No matching log lines.</p>'}</section>`;
  bindSelectable();
}

function renderRaw(snapshot) {
  const selected = state.selectedObservation || observationAtCursor() || snapshot;
  el('content').innerHTML = `<section class="card"><div class="card-head"><h2>Raw observation</h2><small>${selected ? `#${selected.sequence} · ${selected.type}` : 'none selected'}</small></div><div class="card-body"><pre class="json">${escapeHtml(pretty(selected))}</pre></div></section>`;
}

function bindSelectable() {
  document.querySelectorAll('.selectable').forEach(node => node.addEventListener('click', () => selectObservation(Number(node.dataset.sequence))));
}
function selectObservation(sequence) {
  const item = state.bySequence.get(sequence);
  if (!item) return;
  state.selectedObservation = item;
  el('inspector-content').innerHTML = `<div class="section-label">${escapeHtml(item.type)} · #${item.sequence}</div>${kv({ session: item.sessionId, tick: item.tick, captured: new Date(item.capturedAtMs).toISOString() })}<div class="section-label">Exact payload</div><pre class="json">${escapeHtml(pretty(item.payload))}</pre>`;
  document.querySelector('.workspace').classList.remove('inspector-collapsed');
  el('inspector').classList.remove('collapsed');
  render();
}

async function loadInitial() {
  if (!state.token) throw new Error('Dashboard token is missing from the URL. Open the URL printed by Airicraft.');
  const bootstrap = await (await api('/api/bootstrap')).json();
  updateMetadata(bootstrap);
  const oldestCursor = Math.max(0, (bootstrap.oldestSequence || 1) - 1);
  let cursor = Math.max(oldestCursor, bootstrap.latestSequence - INITIAL_SEQUENCE_WINDOW);
  state.partialHistory = cursor > oldestCursor;
  while (cursor < bootstrap.latestSequence) {
    const batch = await (await api(`/api/observations?since=${cursor}&limit=1000`)).json();
    updateMetadata(batch);
    addObservations(batch.observations, false);
    const next = batch.observations?.at(-1)?.sequence;
    if (!next || next <= cursor) break;
    cursor = next;
  }
  updateChrome();
  render();
  connected(true);
  stream(cursor);
}

async function stream(since) {
  state.streamAbort?.abort();
  state.streamAbort = new AbortController();
  try {
    const response = await fetch(`/api/stream?since=${since}`, { headers: authHeaders(), signal: state.streamAbort.signal, cache: 'no-store' });
    if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const frames = buffer.split('\n\n');
      buffer = frames.pop();
      for (const frame of frames) {
        const line = frame.split('\n').find(part => part.startsWith('data: '));
        if (!line) continue;
        const batch = JSON.parse(line.slice(6));
        updateMetadata(batch);
        addObservations(batch.observations);
      }
    }
  } catch (error) {
    if (error.name === 'AbortError' || state.replay) return;
    connected(false, error.message);
    setTimeout(() => stream(state.observations.at(-1)?.sequence || since), 1500);
  }
}

function connected(isLive, error = '') {
  el('connection-dot').className = `dot ${isLive ? 'live' : 'offline'}`;
  el('connection-label').textContent = isLive ? (state.replay ? 'Replay' : 'Connected') : 'Reconnecting';
  if (error) toast(error);
}

async function exportSession() {
  if (state.replay) return toast('This is already a saved session.');
  try {
    const response = await api('/api/export');
    const blob = await response.blob();
    const disposition = response.headers.get('content-disposition') || '';
    const name = disposition.match(/filename="([^"]+)"/)?.[1] || 'airicraft-debug.jsonl';
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob); link.download = name; link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    toast(`Saved ${name}`);
  } catch (error) { toast(error.message); }
}

async function openSession(file) {
  state.streamAbort?.abort();
  state.replay = true; state.live = true; state.metadata = null; state.partialHistory = false;
  resetObservations();
  for (const line of (await file.text()).split(/\r?\n/)) {
    if (!line) continue;
    const record = JSON.parse(line);
    if (record.recordType === 'manifest') {
      updateMetadata(record);
    }
    else if (record.recordType === 'observation') {
      const observation = { ...record };
      delete observation.recordType;
      addObservations([observation], false);
    }
  }
  updateChrome(); render(); connected(true); toast(`Opened ${file.name}`);
}

function toast(message) {
  el('toast').textContent = message;
  el('toast').classList.add('show');
  clearTimeout(toast.timer); toast.timer = setTimeout(() => el('toast').classList.remove('show'), 3200);
}

document.querySelectorAll('#stream-nav button').forEach(button => button.addEventListener('click', () => {
  document.querySelectorAll('#stream-nav button').forEach(node => node.classList.toggle('active', node === button));
  state.view = button.dataset.view; render();
}));
el('search').addEventListener('input', event => { state.search = event.target.value.trim().toLowerCase(); render(); });
el('time-cursor').addEventListener('input', event => { state.live = false; state.selectedSequence = Number(event.target.value); updateChrome(); render(); });
el('return-live').addEventListener('click', () => { state.live = true; state.selectedSequence = state.observations.at(-1)?.sequence || 0; updateChrome(); render(); });
el('export').addEventListener('click', exportSession);
el('session-file').addEventListener('change', event => event.target.files[0] && openSession(event.target.files[0]).catch(error => toast(error.message)));
el('close-inspector').addEventListener('click', () => { el('inspector').classList.add('collapsed'); document.querySelector('.workspace').classList.add('inspector-collapsed'); });

loadInitial().catch(error => { connected(false, error.message); el('content').innerHTML = `<div class="empty-state"><h2 class="error">Dashboard connection failed</h2><p>${escapeHtml(error.message)}</p></div>`; });
