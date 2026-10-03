import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const workload = __ENV.WORKLOAD || 'mixed';
const vus = Number(__ENV.VUS || 50);
const duration = __ENV.DURATION || '20s';
const durationSeconds = Number(__ENV.DURATION_SECONDS || 20);
const datasetSize = Number(__ENV.DATASET_SIZE || 5000);
const payloadBytes = Number(__ENV.PAYLOAD_BYTES || 1024);
const consistency = __ENV.CONSISTENCY || 'ALL';
const baseUrls = (__ENV.BASE_URLS || 'http://host.docker.internal:8081').split(';');
const payload = JSON.stringify({ value: 'x'.repeat(payloadBytes) });

const operations = new Counter('shardkv_operations');
const errors = new Rate('shardkv_errors');
const operationDuration = new Trend('shardkv_operation_duration', true);

const ratios = {
  'get-heavy': { get: 90, put: 10, delete: 0 },
  'put-heavy': { get: 30, put: 70, delete: 0 },
  mixed: { get: 50, put: 40, delete: 10 },
};

if (!ratios[workload]) {
  throw new Error(`Unsupported WORKLOAD: ${workload}`);
}

export const options = {
  vus,
  duration,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    shardkv_errors: [`rate<${__ENV.ERROR_RATE_LIMIT || '0.01'}`],
  },
};

function key(prefix, index) {
  return `${prefix}-${String(index).padStart(7, '0')}`;
}

function request(operation, baseUrl, index) {
  const params = {
    timeout: '15s',
    tags: { name: '/kv/{key}', operation, consistency },
  };

  if (operation === 'get') {
    return http.get(
      `${baseUrl}/kv/${key('bench-key', index)}?consistency=${consistency}`,
      params,
    );
  }
  if (operation === 'put') {
    params.headers = { 'Content-Type': 'application/json' };
    return http.put(
      `${baseUrl}/kv/${key('bench-key', index)}?consistency=${consistency}`,
      payload,
      params,
    );
  }
  return http.del(
    `${baseUrl}/kv/${key('bench-delete-key', index)}?consistency=${consistency}`,
    null,
    params,
  );
}

export default function () {
  const selector = (__ITER + (__VU * 17)) % 100;
  const mix = ratios[workload];
  const operation = selector < mix.get
    ? 'get'
    : selector < mix.get + mix.put
      ? 'put'
      : 'delete';
  const index = ((__ITER * vus) + __VU - 1) % datasetSize;
  const baseUrl = baseUrls[(__ITER + __VU - 1) % baseUrls.length];
  const response = request(operation, baseUrl, index);
  const expectedStatus = operation === 'delete' ? 204 : 200;
  const success = check(response, {
    [`${operation} returned ${expectedStatus}`]: (r) => r.status === expectedStatus,
  });

  operations.add(1, { operation });
  errors.add(!success, { operation });
  operationDuration.add(response.timings.duration, { operation });
}

function values(data, name) {
  return data.metrics[name] ? data.metrics[name].values : {};
}

export function handleSummary(data) {
  const latency = values(data, 'shardkv_operation_duration');
  const errorMetric = values(data, 'shardkv_errors');
  const operationMetric = values(data, 'shardkv_operations');
  const operationCount = operationMetric.count || 0;
  const summary = {
    schemaVersion: 1,
    timestamp: new Date().toISOString(),
    metadata: {
      experiment: __ENV.EXPERIMENT,
      topology: `${__ENV.NODE_COUNT}-node`,
      nodeCount: Number(__ENV.NODE_COUNT),
      replicationFactor: Number(__ENV.REPLICATION_FACTOR),
      consistency,
      workload,
      payloadBytes,
      datasetSize,
      vus,
      warmupSeconds: Number(__ENV.WARMUP_SECONDS || 0),
      durationSeconds,
      runNumber: Number(__ENV.RUN_NUMBER || 1),
      k6Image: __ENV.K6_IMAGE,
    },
    metrics: {
      operations: operationCount,
      throughputOpsPerSecond: operationCount / durationSeconds,
      latencyMs: {
        p50: latency.med || 0,
        p95: latency['p(95)'] || 0,
        p99: latency['p(99)'] || 0,
        average: latency.avg || 0,
        maximum: latency.max || 0,
      },
      errorRate: errorMetric.rate || 0,
      httpRequests: values(data, 'http_reqs').count || 0,
    },
  };
  return {
    [__ENV.SUMMARY_PATH]: JSON.stringify(summary, null, 2),
  };
}
