import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const queryType = __ENV.QUERY_TYPE || 'single-filter';
const vus = Number(__ENV.VUS || 10);
const duration = __ENV.DURATION || '20s';
const durationSeconds = Number(__ENV.DURATION_SECONDS || 20);
const baseUrls = (__ENV.BASE_URLS || 'http://host.docker.internal:8081').split(';');

const operations = new Counter('shardkv_operations');
const errors = new Rate('shardkv_errors');
const operationDuration = new Trend('shardkv_operation_duration', true);

export const options = {
  vus,
  duration,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    shardkv_errors: [`rate<${__ENV.ERROR_RATE_LIMIT || '0.01'}`],
  },
};

export default function () {
  const filters = queryType === 'two-filter-and'
    ? { city: 'Bengaluru', role: 'SDE' }
    : { city: 'Bengaluru' };
  const response = http.post(
    `${baseUrls[(__ITER + __VU - 1) % baseUrls.length]}/query`,
    JSON.stringify({ filters }),
    {
      headers: { 'Content-Type': 'application/json' },
      timeout: '30s',
      tags: { name: '/query', operation: 'query', query_type: queryType },
    },
  );
  const success = check(response, {
    'query returned 200': (r) => r.status === 200,
    'query was complete': (r) => {
      try {
        return r.json('complete') === true;
      } catch (_) {
        return false;
      }
    },
  });

  operations.add(1, { query_type: queryType });
  errors.add(!success, { query_type: queryType });
  operationDuration.add(response.timings.duration, { query_type: queryType });
}

function values(data, name) {
  return data.metrics[name] ? data.metrics[name].values : {};
}

export function handleSummary(data) {
  const latency = values(data, 'shardkv_operation_duration');
  const errorMetric = values(data, 'shardkv_errors');
  const operationCount = values(data, 'shardkv_operations').count || 0;
  const summary = {
    schemaVersion: 1,
    timestamp: new Date().toISOString(),
    metadata: {
      experiment: 'distributed-query',
      topology: `${__ENV.NODE_COUNT}-node`,
      nodeCount: Number(__ENV.NODE_COUNT),
      replicationFactor: Number(__ENV.REPLICATION_FACTOR),
      consistency: 'N/A',
      workload: queryType,
      payloadBytes: Number(__ENV.PAYLOAD_BYTES),
      datasetSize: Number(__ENV.DATASET_SIZE),
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
