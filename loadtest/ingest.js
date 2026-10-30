// Constant-arrival-rate load on the ingest endpoint.
//   k6 run -e RATE=200 -e DURATION=2m loadtest/ingest.js
// Each request uses a fresh idempotency key; DUPLICATE_RATIO of requests reuse an earlier key to exercise dedup.
import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'

const BASE = __ENV.BASE_URL || 'http://localhost:8080'
const RATE = Number(__ENV.RATE || 100)
const DURATION = __ENV.DURATION || '1m'
const DUPLICATE_RATIO = Number(__ENV.DUPLICATE_RATIO || 0.05)

const replayed = new Counter('idempotent_replays')

export const options = {
  scenarios: {
    ingest: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 400,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<250'],
  },
}

const SAMPLES = [
  { tenant: 'nyc311', category: 'Noise - Residential', title: 'Loud Music/Party', attributes: { agency: 'NYPD' } },
  { tenant: 'nyc311', category: 'Street Condition', title: 'Pothole', attributes: { agency: 'DOT' } },
  { tenant: 'nyc311', category: 'HEAT/HOT WATER', title: 'Entire Building', attributes: { agency: 'HPD' } },
  { tenant: 'nyc311', category: 'Illegal Parking', title: 'Blocked Hydrant', attributes: { agency: 'NYPD' } },
  { tenant: 'cfpb', category: 'Credit reporting or other personal consumer reports', title: 'Incorrect information on your report', attributes: { state: 'CA' } },
  { tenant: 'cfpb', category: 'Debt collection', title: 'Attempts to collect debt not owed', attributes: { state: 'TX' } },
]

export default function () {
  const sample = SAMPLES[Math.floor(Math.random() * SAMPLES.length)]
  const duplicate = __ITER > 10 && Math.random() < DUPLICATE_RATIO
  const iteration = duplicate ? Math.floor(Math.random() * __ITER) : __ITER
  const key = `k6-${__VU}-${iteration}`
  const body = JSON.stringify({ externalId: key, title: sample.title, category: sample.category, attributes: sample.attributes })
  const response = http.post(`${BASE}/api/tenants/${sample.tenant}/issues`, body, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `${sample.tenant}-${key}` },
    tags: { name: 'POST /issues' },
  })
  if (response.status === 200) replayed.add(1)
  // A reused key with a different random sample body is a legitimate 422.
  check(response, { 'accepted': (r) => r.status === 201 || r.status === 200 || r.status === 422 })
}
