import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  compareVersions,
  createReleasePlan,
  parseVersions,
  services,
  servicesFromPaths,
} from './releases.mjs';

const expected = {
  cart: 'v0.0.2',
  catalog: 'v0.0.2',
  checkout: 'v0.0.3',
  orders: 'v0.1.1',
  ui: 'v0.1.6',
};

const validYaml = `images:
  cart: v0.0.2
  catalog: v0.0.2
  checkout: v0.0.3
  orders: v0.1.1
  ui: v0.1.6
`;

function versions(overrides = {}) {
  return { ...expected, ...overrides };
}

test('parses the exact five-service image manifest', () => {
  assert.deepEqual(parseVersions(validYaml), expected);
  assert.deepEqual(services, ['cart', 'catalog', 'checkout', 'orders', 'ui']);
});

test('the repository manifest contains the approved first release versions', () => {
  const manifest = readFileSync(new URL('../versions.yaml', import.meta.url), 'utf8');
  assert.deepEqual(parseVersions(manifest), expected);
});

for (const [name, source] of [
  ['missing service', validYaml.replace('  ui: v0.1.6\n', '')],
  ['unsupported service', `${validYaml}  payments: v0.0.1\n`],
  ['duplicate service', `${validYaml}  cart: v0.0.3\n`],
  ['unprefixed version', validYaml.replace('v0.0.2', '0.0.2')],
  ['leading zero', validYaml.replace('v0.0.2', 'v00.0.2')],
  ['prerelease suffix', validYaml.replace('v0.0.2', 'v0.0.2-rc.1')],
]) {
  test(`rejects ${name}`, () => {
    assert.throws(() => parseVersions(source), /versions\.yaml/);
  });
}

test('compares major, minor, and patch versions numerically', () => {
  assert.equal(compareVersions('v1.0.0', 'v0.99.99'), 1);
  assert.equal(compareVersions('v0.10.0', 'v0.9.99'), 1);
  assert.equal(compareVersions('v0.0.3', 'v0.0.3'), 0);
  assert.equal(compareVersions('v0.0.2', 'v0.0.3'), -1);
});

test('maps application paths to services but excludes charts and ui-backup', () => {
  assert.deepEqual(servicesFromPaths([
    'src/cart/src/main/App.java',
    'src/catalog/Dockerfile',
    'src/cart/chart/values.yaml',
    'src/ui-backup/Dockerfile',
    'docs/ci-image-build.md',
  ]), ['cart', 'catalog']);
});

test('normalizes Windows paths when selecting services', () => {
  assert.deepEqual(servicesFromPaths(['src\\orders\\Dockerfile']), ['orders']);
});

test('rejects a service source change without a version increase', () => {
  const base = versions({ cart: 'v0.0.1' });
  assert.throws(() => createReleasePlan({
    base,
    current: base,
    paths: ['src/cart/Dockerfile'],
  }), /cart source changed without a version increase/);
});

test('selects only services whose versions increased', () => {
  const plan = createReleasePlan({
    base: versions({ cart: 'v0.0.1', orders: 'v1.0.0' }),
    current: versions({ cart: 'v0.0.2', orders: 'v1.1.0' }),
    paths: ['versions.yaml', 'src/cart/Dockerfile', 'src/orders/pom.xml'],
  });
  assert.deepEqual(plan.release, [
    { service: 'cart', version: 'v0.0.2' },
    { service: 'orders', version: 'v1.1.0' },
  ]);
  assert.deepEqual(plan.build, [{ service: 'cart' }, { service: 'orders' }]);
});

test('rejects a decreased version even without a source change', () => {
  assert.throws(() => createReleasePlan({
    base: versions({ checkout: 'v0.0.4' }),
    current: versions({ checkout: 'v0.0.3' }),
    paths: ['versions.yaml'],
  }), /checkout version must increase/);
});

test('the first manifest releases all five services', () => {
  const plan = createReleasePlan({ base: null, current: expected, paths: ['versions.yaml'] });
  assert.deepEqual(plan.release.map(item => item.service), services);
  assert.deepEqual(plan.build.map(item => item.service), services);
});

test('a version-only increase is an explicit single-service release request', () => {
  const plan = createReleasePlan({
    base: versions({ catalog: 'v0.0.1' }),
    current: expected,
    paths: ['versions.yaml'],
  });
  assert.deepEqual(plan.release, [{ service: 'catalog', version: 'v0.0.2' }]);
  assert.deepEqual(plan.build, [{ service: 'catalog' }]);
});

test('a chart-only change does not rebuild or release an application image', () => {
  const plan = createReleasePlan({
    base: expected,
    current: expected,
    paths: ['src/cart/chart/values.yaml'],
  });
  assert.deepEqual(plan, { release: [], build: [] });
});

test('CI changes build all services without creating releases', () => {
  const plan = createReleasePlan({
    base: expected,
    current: expected,
    paths: ['ci/images.mjs'],
  });
  assert.deepEqual(plan.release, []);
  assert.deepEqual(plan.build.map(item => item.service), services);
});

test('a forced build-only run builds all services without creating releases', () => {
  const plan = createReleasePlan({
    base: expected,
    current: expected,
    paths: [],
    forceBuildAll: true,
  });
  assert.deepEqual(plan.release, []);
  assert.deepEqual(plan.build.map(item => item.service), services);
});
