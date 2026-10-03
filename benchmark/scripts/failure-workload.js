import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const vus = Number(__ENV.VUS || 30);
const duration = __ENV.DURATION || '60s';
const durationSeconds = Number(__ENV.DURATION_SECONDS || 60);
const datasetSize = Number(__ENV.DATASET_SIZE || 5000);
const baseUrls = (__ENV.BASE_URLS || 'http://host.docker.internal:8081;http://host.docker.internal:8082').split(';');

const operations = new Counter('shardkv_operations');
const errors = new Rate('shardkv_errors');
const operationDuration = new Trend('shardkv_operation_duration', true);
const quorumOperations = new Counter('shardkv_quorum_operations');
const quorumErrors = new Rate('shardkv_quorum_errors');
const quorumDuration = new Trend('shardkv_quorum_duration', true);
const allOperations = new Counter('shardkv_all_operations');
const allErrors = new Rate('shardkv_all_errors');
const allDuration = new Trend('shardkv_all_duration', true);

export const options = {
  vus,
  duration,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function values(data, name) {
  return data.metrics[name] ? data.metrics[name].values : {};
}

function latency(valuesObject) {
  return {
    p50: valuesObject.med || 0,
    p95: valuesObject['p(95)'] || 0,
    p99: valuesObject['p(99)'] || 0,
    average: valuesObject.avg || 0,
    maximum: valuesObject.max || 0,
  };
}

export default function () {
  const consistency = (__ITER + __VU) % 5 === 0 ? 'ALL' : 'QUORUM';
  const index = ((__ITER * vus) + __VU - 1) % datasetSize;
  const key = `bench-key-${String(index).padStart(7, '0')}`;
  const baseUrl = baseUrls[(__ITER + __VU - 1) % baseUrls.length];
  const response = http.get(
    `${baseUrl}/kv/${key}?consistency=${consistency}`,
    {
      timeout: '15s',
      tags: { name: '/kv/{key}', operation: 'get', consistency },
    },
  );
  const success = check(response, { 'read returned 200': (r) => r.status === 200 });

  operations.add(1, { consistency });
  errors.add(!success, { consistency });
  operationDuration.add(response.timings.duration, { consistency });
  if (consistency === 'QUORUM') {
    quorumOperations.add(1);
    quorumErrors.add(!success);
    quorumDuration.add(response.timings.duration);
  } else {
    allOperations.add(1);
    allErrors.add(!success);
    allDuration.add(response.timings.duration);
  }
}

export function handleSummary(data) {
  const operationCount = values(data, 'shardkv_operations').count || 0;
  const quorumCount = values(data, 'shardkv_quorum_operations').count || 0;
  const allCount = values(data, 'shardkv_all_operations').count || 0;
  const summary = {
    schemaVersion: 1,
    timestamp: new Date().toISOString(),
    metadata: {
      experiment: 'failure-under-load',
      topology: '3-node',
      nodeCount: 3,
      replicationFactor: 3,
      consistency: 'QUORUM+ALL',
      workload: 'read-failure-injection',
      payloadBytes: Number(__ENV.PAYLOAD_BYTES),
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
      latencyMs: latency(values(data, 'shardkv_operation_duration')),
      errorRate: values(data, 'shardkv_errors').rate || 0,
      httpRequests: values(data, 'http_reqs').count || 0,
    },
    consistencyBreakdown: {
      quorum: {
        operations: quorumCount,
        throughputOpsPerSecond: quorumCount / durationSeconds,
        errorRate: values(data, 'shardkv_quorum_errors').rate || 0,
        latencyMs: latency(values(data, 'shardkv_quorum_duration')),
      },
      all: {
        operations: allCount,
        throughputOpsPerSecond: allCount / durationSeconds,
        errorRate: values(data, 'shardkv_all_errors').rate || 0,
        latencyMs: latency(values(data, 'shardkv_all_duration')),
      },
    },
  };
  return {
    [__ENV.SUMMARY_PATH]: JSON.stringify(summary, null, 2),
  };
}
