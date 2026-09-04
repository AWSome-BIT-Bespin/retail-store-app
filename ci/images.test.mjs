import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./images.mjs', import.meta.url));
const sha = 'a'.repeat(40);
const digest = `sha256:${'b'.repeat(64)}`;
const base = {
  SERVICE: 'cart',
  GITHUB_SHA: sha,
  GITHUB_RUN_ID: '12345',
  GITHUB_RUN_ATTEMPT: '1',
  GITHUB_REPOSITORY: 'AWSome-BIT-Bespin/retail-store-app',
  GITHUB_REF: 'refs/heads/main',
  GITHUB_EVENT_NAME: 'push',
  PUBLISH_IMAGES: '',
  AWS_ROLE_ARN: 'arn:aws:iam::350606136784:role/ci-image-publisher',
  GCP_WORKLOAD_IDENTITY_PROVIDER: 'projects/123456789/locations/global/workloadIdentityPools/ci-pool/providers/github',
  GCP_SERVICE_ACCOUNT: 'ci-publisher@kdt4-3.iam.gserviceaccount.com',
  ECR_MANIFEST_JSON: JSON.stringify({ digest }),
  GAR_MANIFEST_JSON: JSON.stringify({ digest }),
  GITHUB_OUTPUT: '',
  GITHUB_STEP_SUMMARY: '',
};

function run(command, overrides = {}) {
  return spawnSync(process.execPath, [script, command], {
    env: { ...process.env, ...base, ...overrides },
    encoding: 'utf8',
    timeout: 10000,
  });
}

function success(command, overrides = {}) {
  const result = run(command, overrides);
  assert.equal(result.status, 0, result.stderr);
  return JSON.parse(result.stdout);
}

function failure(command, overrides, message) {
  const result = run(command, overrides);
  assert.notEqual(result.status, 0, 'invalid input must fail');
  assert.match(result.stderr, message);
}

for (const service of ['cart', 'catalog', 'checkout', 'orders', 'ui']) {
  test(`metadata maps ${service} to both approved registries`, () => {
    const actual = success('metadata', { SERVICE: service });
    const tag = `sha-${sha}-run-12345-1`;
    assert.deepEqual(actual, {
      service,
      tag,
      local_image: `retail-${service}:${tag}`,
      ecr_image: `350606136784.dkr.ecr.ap-northeast-2.amazonaws.com/retail-${service}:${tag}`,
      gar_image: `asia-northeast3-docker.pkg.dev/kdt4-3/retail-store/retail-${service}:${tag}`,
    });
  });
}

test('different runs and reruns never reuse a tag', () => {
  const first = success('metadata').tag;
  assert.notEqual(first, success('metadata', { GITHUB_RUN_ID: '12346' }).tag);
  assert.notEqual(first, success('metadata', { GITHUB_RUN_ATTEMPT: '2' }).tag);
  assert.ok(!['latest', 'test', 'v0.0.1', 'v0.1.0', 'v0.1.2'].includes(first));
});

for (const value of ['', 'ui-backup', '../orders', 'cart\ninjected=value']) {
  test(`rejects unsupported service ${JSON.stringify(value)}`, () => {
    failure('metadata', { SERVICE: value }, /SERVICE/);
  });
}

for (const value of ['', 'main', 'a'.repeat(39), 'g'.repeat(40), `${sha}\n`]) {
  test(`rejects invalid commit ${JSON.stringify(value)}`, () => {
    failure('metadata', { GITHUB_SHA: value }, /GITHUB_SHA/);
  });
}

for (const key of ['GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT']) {
  for (const value of ['', '0', '-1', '1\n']) {
    test(`rejects invalid ${key} ${JSON.stringify(value)}`, () => {
      failure('metadata', { [key]: value }, new RegExp(key));
    });
  }
}

test('accepts correctly scoped authentication configuration', () => {
  assert.deepEqual(success('check-auth'), { ok: true });
});

test('accepts explicit main-branch manual publication', () => {
  assert.deepEqual(success('check-auth', {
    GITHUB_EVENT_NAME: 'workflow_dispatch', PUBLISH_IMAGES: 'true',
  }), { ok: true });
});

for (const overrides of [
  { GITHUB_REPOSITORY: 'someone/retail-store-app' },
  { GITHUB_REF: 'refs/heads/feature' },
  { GITHUB_EVENT_NAME: 'pull_request' },
  { GITHUB_EVENT_NAME: 'pull_request_target' },
  { GITHUB_EVENT_NAME: 'workflow_dispatch', PUBLISH_IMAGES: 'false' },
]) {
  test(`rejects untrusted publication context ${JSON.stringify(overrides)}`, () => {
    failure('check-auth', overrides, /Publishing is only allowed/);
  });
}

for (const key of ['AWS_ROLE_ARN', 'GCP_WORKLOAD_IDENTITY_PROVIDER', 'GCP_SERVICE_ACCOUNT']) {
  test(`missing ${key} produces a specific setup error`, () => {
    failure('check-auth', { [key]: '' }, new RegExp(key));
  });
}

test('rejects an AWS role in a different account', () => {
  failure('check-auth', {
    AWS_ROLE_ARN: 'arn:aws:iam::999999999999:role/ci-image-publisher',
  }, /AWS_ROLE_ARN/);
});

test('rejects a malformed workload identity provider', () => {
  failure('check-auth', { GCP_WORKLOAD_IDENTITY_PROVIDER: 'kdt4-3/github' }, /GCP_WORKLOAD_IDENTITY_PROVIDER/);
});

test('rejects a non-service-account email without echoing its value', () => {
  const result = run('check-auth', { GCP_SERVICE_ACCOUNT: 'do-not-echo-this-sensitive-value' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /GCP_SERVICE_ACCOUNT/);
  assert.ok(!result.stderr.includes('do-not-echo-this-sensitive-value'));
});

test('equal registry digests produce immutable pull references', () => {
  const result = success('verify');
  assert.equal(result.digest, digest);
  assert.equal(result.ecr_pull, `350606136784.dkr.ecr.ap-northeast-2.amazonaws.com/retail-cart@${digest}`);
  assert.equal(result.gar_pull, `asia-northeast3-docker.pkg.dev/kdt4-3/retail-store/retail-cart@${digest}`);
});

test('unequal digests fail instead of reporting a successful dual publication', () => {
  failure('verify', { GAR_MANIFEST_JSON: JSON.stringify({ digest: `sha256:${'c'.repeat(64)}` }) }, /digests differ/);
});

test('missing registry digest fails verification', () => {
  failure('verify', { GAR_MANIFEST_JSON: '{}' }, /GAR_MANIFEST_JSON/);
});

test('malformed registry JSON fails verification', () => {
  failure('verify', { ECR_MANIFEST_JSON: 'not-json' }, /ECR_MANIFEST_JSON/);
});

test('build-only summary explicitly says the image was not published', () => {
  const result = success('summary-build');
  assert.equal(result.published, false);
  assert.equal(result.service, 'cart');
});

test('unknown CLI command fails', () => {
  failure('unexpected', {}, /Unknown command/);
});
