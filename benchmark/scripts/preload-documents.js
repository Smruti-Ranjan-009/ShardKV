import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';

const datasetSize = Number(__ENV.DATASET_SIZE || 5000);
const preloadVus = Number(__ENV.PRELOAD_VUS || 40);
const payloadBytes = Number(__ENV.PAYLOAD_BYTES || 1024);
const consistency = __ENV.CONSISTENCY || 'ALL';
const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8081';
const payload = 'd'.repeat(payloadBytes);

const preloadOperations = new Counter('shardkv_preload_operations');
const preloadErrors = new Rate('shardkv_preload_errors');

export const options = {
  scenarios: {
    preloadDocuments: {
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

export default function () {
  const index = exec.scenario.iterationInTest;
  const key = `bench-doc-${String(index).padStart(7, '0')}`;
  const document = JSON.stringify({
    fields: {
      city: index % 2 === 0 ? 'Bengaluru' : 'Hyderabad',
      role: index % 4 < 2 ? 'SDE' : 'ML',
      experience: index % 20,
      active: index % 3 !== 0,
      payload,
    },
  });
  const response = http.put(
    `${baseUrl}/documents/${key}?consistency=${consistency}`,
    document,
    {
      headers: { 'Content-Type': 'application/json' },
      timeout: '60s',
      tags: { name: '/documents/{key}', operation: 'preload-document' },
    },
  );
  const success = check(response, { 'document preload returned 200': (r) => r.status === 200 });
  preloadOperations.add(1);
  preloadErrors.add(!success);
}
