import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';

const datasetSize = Number(__ENV.DATASET_SIZE || 5000);
const preloadVus = Number(__ENV.PRELOAD_VUS || 40);
const payloadBytes = Number(__ENV.PAYLOAD_BYTES || 1024);
const consistency = __ENV.CONSISTENCY || 'ALL';
const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8081';
const payload = 'x'.repeat(payloadBytes);

const preloadOperations = new Counter('shardkv_preload_operations');
const preloadErrors = new Rate('shardkv_preload_errors');

export const options = {
  scenarios: {
    preload: {
      executor: 'shared-iterations',
      vus: preloadVus,
      iterations: datasetSize,
      maxDuration: '15m',
    },
  },
  thresholds: {
    shardkv_preload_errors: ['rate==0'],
  },
};

function key(prefix, index) {
  return `${prefix}-${String(index).padStart(7, '0')}`;
}

export default function () {
  const index = exec.scenario.iterationInTest;
  const body = JSON.stringify({ value: payload });
  const params = {
    headers: { 'Content-Type': 'application/json' },
    timeout: '15s',
    tags: { operation: 'preload' },
  };

  const responses = http.batch([
    ['PUT', `${baseUrl}/kv/${key('bench-key', index)}?consistency=${consistency}`, body, params],
    ['PUT', `${baseUrl}/kv/${key('bench-delete-key', index)}?consistency=${consistency}`, body, params],
  ]);

  for (const response of responses) {
    const success = check(response, { 'preload returned 200': (r) => r.status === 200 });
    preloadOperations.add(1);
    preloadErrors.add(!success);
  }
}
