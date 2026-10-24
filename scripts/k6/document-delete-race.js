import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const unexpectedErrors = new Rate('unexpected_errors');

const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1:9090/api/ragent').replace(/\/$/, '');
const USERNAME = __ENV.RAGENT_USERNAME || 'admin';
const PASSWORD = __ENV.RAGENT_PASSWORD || 'admin';
const KB_ID = __ENV.RAGENT_KB_ID || '2059591404023242752';
const VUS = Number(__ENV.VUS || 5);
const ITERATIONS = Number(__ENV.ITERATIONS || 10);
const API_P95_THRESHOLD_MS = Number(__ENV.API_P95_THRESHOLD_MS || 15000);

export const options = {
  scenarios: {
    document_delete_race: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: __ENV.MAX_DURATION || '3m',
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    unexpected_errors: ['rate==0'],
    'http_req_duration{type:api}': [`p(95)<${API_P95_THRESHOLD_MS}`],
  },
};

function parseJson(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

function businessSuccess(res) {
  const body = parseJson(res);
  return res.status === 200 && body && body.code === '0';
}

function expectedClientFailure(res, expectedMessages) {
  const body = parseJson(res);
  if (res.status !== 200 || !body || body.code !== 'A000001') {
    return false;
  }
  return expectedMessages.some((message) => String(body.message || '').includes(message));
}

function record(name, condition, res) {
  const ok = Boolean(condition);
  unexpectedErrors.add(!ok);
  check(res, { [name]: () => ok });
  if (!ok) {
    const body = res && res.body ? String(res.body).slice(0, 500) : '<empty>';
    console.error(`${name} failed: status=${res ? res.status : '<none>'} body=${body}`);
  }
  return ok;
}

function authHeaders(token) {
  return {
    Authorization: token,
  };
}

export function setup() {
  const loginRes = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { type: 'api', name: 'login' },
    },
  );

  const loginBody = parseJson(loginRes);
  const token = loginBody && loginBody.data && loginBody.data.token;
  if (!record('login succeeds', loginRes.status === 200 && loginBody && loginBody.code === '0' && token, loginRes)) {
    throw new Error('login failed');
  }

  return { token };
}

export default function (data) {
  const headers = authHeaders(data.token);
  const docName = `k6-delete-race-${__VU}-${__ITER}-${Date.now()}.txt`;
  const content = [
    'This document is created by k6 for the document delete/chunk race test.',
    `vu=${__VU}`,
    `iter=${__ITER}`,
    `time=${new Date().toISOString()}`,
    'The expected behavior is that chunk and delete serialize through document status CAS.',
  ].join('\n');

  const uploadRes = http.post(
    `${BASE_URL}/knowledge-base/${KB_ID}/docs/upload`,
    {
      sourceType: 'file',
      processMode: 'chunk',
      chunkStrategy: 'fixed_size',
      chunkConfig: JSON.stringify({ chunkSize: 128, overlapSize: 0 }),
      file: http.file(content, docName, 'text/plain'),
    },
    {
      headers,
      tags: { type: 'api', name: 'upload_doc' },
      timeout: '20s',
    },
  );

  const uploadBody = parseJson(uploadRes);
  const docId = uploadBody && uploadBody.data && uploadBody.data.id;
  if (!record('upload succeeds', businessSuccess(uploadRes) && docId, uploadRes)) {
    return;
  }

  const responses = http.batch([
    [
      'POST',
      `${BASE_URL}/knowledge-base/docs/${docId}/chunk`,
      null,
      { headers, tags: { type: 'api', name: 'start_chunk' }, timeout: '20s' },
    ],
    [
      'DELETE',
      `${BASE_URL}/knowledge-base/docs/${docId}`,
      null,
      { headers, tags: { type: 'api', name: 'delete_doc' }, timeout: '20s' },
    ],
  ]);

  const chunkRes = responses[0];
  const deleteRes = responses[1];
  record(
    'chunk returns success or expected conflict',
    businessSuccess(chunkRes)
      || expectedClientFailure(chunkRes, ['文档分块操作正在进行中', '文档不存在']),
    chunkRes,
  );
  record(
    'delete returns success or expected conflict',
    businessSuccess(deleteRes)
      || expectedClientFailure(deleteRes, ['文档正在分块或已被处理，无法删除', '文档不存在']),
    deleteRes,
  );

  const getRes = http.get(
    `${BASE_URL}/knowledge-base/docs/${docId}`,
    { headers, tags: { type: 'api', name: 'get_doc_after_race' }, timeout: '20s' },
  );
  record(
    'final document state is queryable or deleted',
    businessSuccess(getRes) || expectedClientFailure(getRes, ['文档不存在']),
    getRes,
  );

  cleanupDoc(docId, headers);
  sleep(0.2);
}

function cleanupDoc(docId, headers) {
  for (let attempt = 0; attempt < 5; attempt++) {
    const deleteRes = http.del(
      `${BASE_URL}/knowledge-base/docs/${docId}`,
      null,
      { headers, tags: { type: 'api', name: 'cleanup_doc' }, timeout: '20s' },
    );
    if (businessSuccess(deleteRes) || expectedClientFailure(deleteRes, ['文档不存在'])) {
      return;
    }
    if (!expectedClientFailure(deleteRes, ['文档正在分块或已被处理，无法删除'])) {
      record('cleanup returns success or expected conflict', false, deleteRes);
      return;
    }
    sleep(1);
  }
}
