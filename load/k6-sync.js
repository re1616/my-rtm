// QA-6 load test (k6): WebSocket connections held open + sync batches at a fixed rate.
// Target for staging: WS_CONNECTIONS=10000, SYNC_RATE=500 (batches/s), p95 ≤ 2 s.
// CI runs a smaller smoke profile against a server on the runner (see .github/workflows/load.yml).
//
//   k6 run -e BASE_URL=https://staging.example -e WS_CONNECTIONS=10000 -e SYNC_RATE=500 load/k6-sync.js
import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const WS_URL = BASE.replace(/^http/, 'ws') + '/v1/ws';
const USERS = Number(__ENV.USERS || 50);
const WS_CONNECTIONS = Number(__ENV.WS_CONNECTIONS || 200);
const SYNC_RATE = Number(__ENV.SYNC_RATE || 50);
const DURATION = __ENV.DURATION || '1m';
const OPS_PER_BATCH = Number(__ENV.OPS_PER_BATCH || 5);

const syncUpdates = new Counter('ws_sync_updates');
const syncRejected = new Counter('sync_ops_not_ok');

export const options = {
  setupTimeout: '5m',
  scenarios: {
    sockets: {
      executor: 'per-vu-iterations',
      exec: 'socket',
      vus: WS_CONNECTIONS,
      iterations: 1,
      maxDuration: '10m',
    },
    sync: {
      executor: 'constant-arrival-rate',
      exec: 'syncBatch',
      rate: SYNC_RATE,
      timeUnit: '1s',
      duration: DURATION,
      startTime: '15s', // let the sockets connect first
      preAllocatedVUs: Math.max(10, SYNC_RATE),
      maxVUs: Math.max(50, SYNC_RATE * 4),
    },
  },
  thresholds: {
    'http_req_duration{scenario:sync}': ['p(95)<2000'],
    'ws_connecting': ['p(95)<2000'],
    'checks': ['rate>0.99'],
    'sync_ops_not_ok': ['count<1'],
  },
};

const json = { headers: { 'Content-Type': 'application/json', 'Taskly-Sync-Version': '2' } };

export function setup() {
  const users = [];
  for (let i = 0; i < USERS; i++) {
    const email = `k6-${Date.now()}-${i}-${Math.random().toString(16).slice(2)}@taskly.test`;
    const res = http.post(`${BASE}/v1/auth/register`, JSON.stringify({ email, password: 'LoadTest-123' }), json);
    check(res, { 'registered': (r) => r.status === 200 || r.status === 201 });
    const body = res.json();
    users.push({ token: body.accessToken, userId: body.userId });
  }
  return { users };
}

function userFor(data, n) {
  return data.users[n % data.users.length];
}

// Each VU holds one authenticated socket for the whole test and counts change notifications
export function socket(data) {
  const u = userFor(data, __VU);
  const holdMs = (parseDuration(DURATION) + 20) * 1000;
  const res = ws.connect(WS_URL, {}, (s) => {
    s.on('open', () => s.send(JSON.stringify({ type: 'auth', payload: JSON.stringify({ token: u.token }) })));
    s.on('message', (m) => {
      const msg = JSON.parse(m);
      if (msg.type === 'sync_update') syncUpdates.add(1);
      if (msg.type === 'error') check(msg, { 'ws auth ok': () => false });
    });
    s.setTimeout(() => s.close(), holdMs);
  });
  check(res, { 'ws upgraded': (r) => r && r.status === 101 });
}

// One sync request with a few operations: create, update, complete
export function syncBatch(data) {
  const u = userFor(data, __ITER + __VU);
  const ops = [];
  for (let i = 0; i < OPS_PER_BATCH; i++) {
    const id = uuidv4();
    ops.push({ opId: uuidv4(), scope: `user:${u.userId}`, entity: 'task', entityId: id, type: 'create', fields: { name: `k6 ${id}`, listId: 'sys-inbox' } });
    if (i % 2 === 0) ops.push({ opId: uuidv4(), scope: `user:${u.userId}`, entity: 'task', entityId: id, type: 'complete', fields: { completedAt: new Date().toISOString() } });
  }
  const res = http.post(`${BASE}/v1/sync`, JSON.stringify({ ops }), {
    headers: { ...json.headers, Authorization: `Bearer ${u.token}` },
  });
  const ok = check(res, { 'sync 200': (r) => r.status === 200 });
  if (ok) {
    for (const r of res.json('results') || []) if (r.status !== 'ok') syncRejected.add(1);
  }
}

function parseDuration(d) {
  const m = /^(\d+)(s|m|h)$/.exec(d);
  if (!m) return 60;
  return Number(m[1]) * { s: 1, m: 60, h: 3600 }[m[2]];
}
