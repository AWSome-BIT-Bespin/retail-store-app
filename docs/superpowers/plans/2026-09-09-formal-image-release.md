# Formal Image Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Manage five service image versions in `versions.yaml` and publish only newly versioned services to ECR and GAR with the same immutable-by-policy SemVer tag and verified digest.

**Architecture:** Add a focused Node.js release planner that validates the constrained YAML manifest, compares Git revisions, and emits dynamic GitHub Actions matrices. Keep image metadata, authentication checks, registry collision checks, digest verification, and summaries in `ci/images.mjs`; keep Docker construction in the existing composite action. Modify the existing workflow rather than adding a second publisher so each selected service is built once and the same local image is pushed to both registries.

**Tech Stack:** GitHub Actions, Node.js 20 built-in modules and `node:test`, Docker Buildx, AWS OIDC/ECR, Google Workload Identity Federation/Artifact Registry, YAML with a deliberately constrained schema.

---

## File map

| Path | Responsibility |
| --- | --- |
| `versions.yaml` | Human-edited source of truth for the five formal image versions. |
| `ci/releases.mjs` | Parse versions, compare SemVer, map changed paths to services, and emit workflow matrices. |
| `ci/releases.test.mjs` | Unit tests for version and change planning rules. |
| `ci/images.mjs` | Produce build/publish references, validate trusted publication, reject occupied tags, verify digests, and write summaries. |
| `ci/images.test.mjs` | Unit tests for image metadata, tag collision handling, authentication scope, and summaries. |
| `.github/actions/build-image/action.yml` | Build one service once with an optional formal tag supplied by the workflow. |
| `.github/workflows/build-images.yml` | Orchestrate planning, build-only PR checks, OIDC/WIF authentication, preflight checks, dual push, and verification. |
| `ci/workflow.test.mjs` | Static safety tests for permissions, triggers, dynamic matrices, ordering, and absence of deployment commands. |
| `docs/ci-image-build.md` | Team procedure for choosing a version, opening a PR, verifying publication, and handling partial failures. |

The approved design is `docs/superpowers/specs/2026-09-09-formal-image-release-design.md`.

> **Execution note (2026-09-09):** The user limited local verification to one final attempt. The implementation and tests were prepared without running the intermediate Red/Green commands below; the complete suite, Git-aware planner check, and diff checks are consolidated into one final verification command. A failure is reported before any retry.

### Task 1: Add the version manifest and constrained parser

**Files:**
- Create: `versions.yaml`
- Create: `ci/releases.mjs`
- Create: `ci/releases.test.mjs`

- [ ] **Step 1: Write parser and SemVer tests first**

Create `ci/releases.test.mjs` with these initial tests:

```javascript
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { compareVersions, parseVersions, services } from './releases.mjs';

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

test('parses the exact five-service image manifest', () => {
  assert.deepEqual(parseVersions(validYaml), expected);
  assert.deepEqual(services, ['cart', 'catalog', 'checkout', 'orders', 'ui']);
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
```

- [ ] **Step 2: Run the test and verify the missing module failure**

Run from the repository root:

```powershell
node --test ci/releases.test.mjs
```

Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `ci/releases.mjs`.

- [ ] **Step 3: Implement the parser and comparison functions**

Create `ci/releases.mjs` with this foundation:

```javascript
import { appendFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const services = Object.freeze(['cart', 'catalog', 'checkout', 'orders', 'ui']);
const versionPattern = /^v(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})$/;

function versionParts(version) {
  const match = versionPattern.exec(version);
  if (!match) throw new Error(`Invalid versions.yaml version: ${version}`);
  return match.slice(1).map(Number);
}

export function compareVersions(left, right) {
  const a = versionParts(left);
  const b = versionParts(right);
  for (let index = 0; index < 3; index += 1) {
    if (a[index] !== b[index]) return a[index] > b[index] ? 1 : -1;
  }
  return 0;
}

export function parseVersions(text) {
  if (typeof text !== 'string' || text.includes('\0')) {
    throw new Error('Invalid versions.yaml content.');
  }
  const lines = text.replaceAll('\r\n', '\n').split('\n')
    .filter(line => line.trim() && !line.trimStart().startsWith('#'));
  if (lines.shift() !== 'images:') throw new Error('versions.yaml must start with images:.');

  const result = {};
  for (const line of lines) {
    const match = /^  ([a-z][a-z0-9-]*): (v[0-9]+\.[0-9]+\.[0-9]+)$/.exec(line);
    if (!match) throw new Error(`Invalid versions.yaml entry: ${line}`);
    const [, service, version] = match;
    if (!services.includes(service)) throw new Error(`Unsupported versions.yaml service: ${service}`);
    if (Object.hasOwn(result, service)) throw new Error(`Duplicate versions.yaml service: ${service}`);
    versionParts(version);
    result[service] = version;
  }

  for (const service of services) {
    if (!Object.hasOwn(result, service)) throw new Error(`Missing versions.yaml service: ${service}`);
  }
  if (Object.keys(result).length !== services.length) {
    throw new Error('versions.yaml must contain exactly five services.');
  }
  return result;
}
```

The imports used by the CLI in Task 2 are added now so the module has one final import block.

- [ ] **Step 4: Add the approved first release manifest**

Create `versions.yaml` exactly as follows:

```yaml
images:
  cart: v0.0.2
  catalog: v0.0.2
  checkout: v0.0.3
  orders: v0.1.1
  ui: v0.1.6
```

- [ ] **Step 5: Run the focused test**

```powershell
node --test ci/releases.test.mjs
```

Expected: all parser and comparison tests PASS.

- [ ] **Step 6: Commit the parser and manifest**

```powershell
git add versions.yaml ci/releases.mjs ci/releases.test.mjs
git commit -m "feat: add formal image version manifest"
```

### Task 2: Plan releases from Git changes

**Files:**
- Modify: `ci/releases.mjs`
- Modify: `ci/releases.test.mjs`

- [ ] **Step 1: Add failing path and plan tests**

Append these tests to `ci/releases.test.mjs`:

```javascript
import { createReleasePlan, servicesFromPaths } from './releases.mjs';

function versions(overrides = {}) {
  return { ...expected, ...overrides };
}

test('maps application paths to services but excludes charts and ui-backup', () => {
  assert.deepEqual(servicesFromPaths([
    'src/cart/src/main/App.java',
    'src/catalog/Dockerfile',
    'src/cart/chart/values.yaml',
    'src/ui-backup/Dockerfile',
    'docs/ci-image-build.md',
  ]), ['cart', 'catalog']);
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
```

Consolidate the two imports from `./releases.mjs` into one import statement so the test file remains valid ESM.

- [ ] **Step 2: Run the new tests and verify missing exports**

```powershell
node --test ci/releases.test.mjs
```

Expected: FAIL because `createReleasePlan` and `servicesFromPaths` are not exported.

- [ ] **Step 3: Implement pure change planning**

Add these functions below `parseVersions` in `ci/releases.mjs`:

```javascript
function normalizePath(path) {
  return path.replaceAll('\\', '/').replace(/^\.\//, '');
}

export function servicesFromPaths(paths) {
  const changed = new Set();
  for (const rawPath of paths) {
    const path = normalizePath(rawPath);
    for (const service of services) {
      const prefix = `src/${service}/`;
      if (path.startsWith(prefix) && !path.startsWith(`${prefix}chart/`)) changed.add(service);
    }
  }
  return services.filter(service => changed.has(service));
}

function ciChanged(paths) {
  return paths.map(normalizePath).some(path => path.startsWith('ci/') || path.startsWith('.github/'));
}

export function createReleasePlan({ base, current, paths, forceBuildAll = false }) {
  const releaseServices = base === null
    ? [...services]
    : services.filter(service => current[service] !== base[service]);

  if (base !== null) {
    for (const service of releaseServices) {
      if (compareVersions(current[service], base[service]) <= 0) {
        throw new Error(`${service} version must increase: ${base[service]} -> ${current[service]}`);
      }
    }
  }

  for (const service of servicesFromPaths(paths)) {
    if (!releaseServices.includes(service)) {
      throw new Error(`${service} source changed without a version increase.`);
    }
  }

  const buildServices = forceBuildAll || ciChanged(paths) ? services : releaseServices;
  return {
    release: releaseServices.map(service => ({ service, version: current[service] })),
    build: buildServices.map(service => ({ service })),
  };
}
```

- [ ] **Step 4: Implement the Git-aware CLI and workflow outputs**

Append this CLI code to `ci/releases.mjs`:

```javascript
function git(args) {
  return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

function commit(name, value) {
  if (!/^[a-f0-9]{40}$/.test(value)) throw new Error(`Missing or invalid ${name}.`);
  return value;
}

function readVersionsAt(revision) {
  return parseVersions(git(['show', `${revision}:versions.yaml`]));
}

function emit(name, value) {
  if (process.env.GITHUB_OUTPUT) {
    appendFileSync(process.env.GITHUB_OUTPUT, `${name}=${value}\n`, 'utf8');
  }
}

function executePlan() {
  const head = commit('HEAD_SHA', process.env.HEAD_SHA || process.env.GITHUB_SHA);
  const suppliedBase = process.env.BASE_SHA || '';
  const baseValue = suppliedBase || git(['rev-parse', `${head}^`]);
  const base = /^0{40}$/.test(baseValue)
    ? null
    : commit('BASE_SHA', baseValue);
  const paths = base === null
    ? git(['ls-tree', '-r', '--name-only', head]).split('\n').filter(Boolean)
    : git(['diff', '--name-only', `${base}...${head}`, '--']).split('\n').filter(Boolean);
  const baseHasManifest = base !== null
    && git(['ls-tree', '--name-only', base, '--', 'versions.yaml']) === 'versions.yaml';
  const current = readVersionsAt(head);
  const previous = baseHasManifest ? readVersionsAt(base) : null;
  const plan = createReleasePlan({
    base: previous,
    current,
    paths,
    forceBuildAll: process.env.FORCE_BUILD_ALL === 'true',
  });

  emit('release_matrix', JSON.stringify(plan.release));
  emit('release_count', String(plan.release.length));
  emit('build_matrix', JSON.stringify(plan.build));
  emit('build_count', String(plan.build.length));
  return { base, head, paths, ...plan };
}

const invokedDirectly = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  try {
    if (process.argv[2] !== 'plan') throw new Error('Unknown release command.');
    console.log(JSON.stringify(executePlan()));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
```

- [ ] **Step 5: Run all release planner tests**

```powershell
node --test ci/releases.test.mjs
```

Expected: all tests PASS, including source/version coupling, initial release, and CI-only behavior.

- [ ] **Step 6: Commit release planning**

```powershell
git add ci/releases.mjs ci/releases.test.mjs
git commit -m "feat: plan versioned service releases"
```

### Task 3: Produce formal image references and fail closed on occupied tags

**Files:**
- Modify: `ci/images.mjs`
- Modify: `ci/images.test.mjs`

- [ ] **Step 1: Change test defaults to a formal version and add collision tests**

In the `base` object in `ci/images.test.mjs`, add:

```javascript
IMAGE_TAG: 'v0.0.2',
ECR_LOOKUP_JSON: JSON.stringify({
  imageCount: 0,
  failureCodes: ['ImageNotFound'],
}),
GAR_HTTP_STATUS: '404',
ECR_PUSH_OUTCOME: '',
GAR_PUSH_OUTCOME: '',
```

Replace the metadata expectation loop so it expects the formal tag:

```javascript
for (const service of ['cart', 'catalog', 'checkout', 'orders', 'ui']) {
  test(`metadata maps ${service} and the formal tag to both registries`, () => {
    const actual = success('metadata', { SERVICE: service });
    const image = `retail-${service}:v0.0.2`;
    assert.deepEqual(actual, {
      service,
      tag: 'v0.0.2',
      local_image: image,
      ecr_image: `350606136784.dkr.ecr.ap-northeast-2.amazonaws.com/${image}`,
      gar_image: `asia-northeast3-docker.pkg.dev/kdt4-3/retail-store/${image}`,
    });
  });
}
```

Replace the old unique published-tag test and add these cases:

```javascript
test('build-only metadata uses a non-published run-unique local tag', () => {
  const first = success('metadata', { IMAGE_TAG: '' }).tag;
  const rerun = success('metadata', { IMAGE_TAG: '', GITHUB_RUN_ATTEMPT: '2' }).tag;
  assert.equal(first, `build-${sha}-run-12345-1`);
  assert.notEqual(first, rerun);
});

for (const value of ['latest', 'test', 'sha-abc', 'v01.0.0', 'v1.0', 'v1.0.0\n']) {
  test(`rejects invalid formal image tag ${JSON.stringify(value)}`, () => {
    failure('metadata', { IMAGE_TAG: value }, /IMAGE_TAG/);
  });
}

test('accepts when the formal tag is absent from both registries', () => {
  assert.deepEqual(success('assert-tag-absent'), { ecr: 'absent', gar: 'absent' });
});

test('rejects an existing ECR tag before any push', () => {
  failure('assert-tag-absent', {
    ECR_LOOKUP_JSON: JSON.stringify({ imageCount: 1, failureCodes: [] }),
  }, /already exists in ECR/);
});

test('rejects an existing GAR tag before any push', () => {
  failure('assert-tag-absent', { GAR_HTTP_STATUS: '200' }, /already exists in GAR/);
});

test('fails closed when ECR cannot confirm absence', () => {
  failure('assert-tag-absent', {
    ECR_LOOKUP_JSON: JSON.stringify({ imageCount: 0, failureCodes: ['AccessDenied'] }),
  }, /Unable to confirm ECR tag absence/);
});

test('fails closed when GAR cannot confirm absence', () => {
  failure('assert-tag-absent', { GAR_HTTP_STATUS: '500' }, /Unable to confirm GAR tag absence/);
});

test('incomplete summary reports individual push outcomes', () => {
  const result = success('summary-incomplete', {
    ECR_PUSH_OUTCOME: 'success',
    GAR_PUSH_OUTCOME: 'failure',
  });
  assert.deepEqual(result, { service: 'cart', tag: 'v0.0.2', ecr: 'success', gar: 'failure' });
});
```

Replace the existing build-only summary test with this version so it exercises the empty-tag path:

```javascript
test('build-only summary explicitly says the image was not published', () => {
  const result = success('summary-build', { IMAGE_TAG: '' });
  assert.equal(result.published, false);
  assert.equal(result.service, 'cart');
});
```

The existing `check-auth` success cases automatically use the formal `IMAGE_TAG` added to `base`. Add this explicit missing-tag case:

```javascript
test('trusted publication still requires a formal image tag', () => {
  failure('check-auth', { IMAGE_TAG: '' }, /IMAGE_TAG/);
});
```

- [ ] **Step 2: Run the image tests and verify expected failures**

```powershell
node --test ci/images.test.mjs
```

Expected: FAIL because formal tag validation and the two new commands are not implemented.

- [ ] **Step 3: Replace SHA publication metadata with optional formal metadata**

In `ci/images.mjs`, add the formal version pattern beside the existing constants and replace `metadata()` with:

```javascript
const versionTagPattern = /^v(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})$/;

function metadata() {
  const service = setting('SERVICE');
  if (!services.includes(service)) throw new Error('Unsupported SERVICE.');
  const sha = setting('GITHUB_SHA', /^[a-f0-9]{40}$/);
  const run = setting('GITHUB_RUN_ID', /^[1-9][0-9]{0,19}$/);
  const attempt = setting('GITHUB_RUN_ATTEMPT', /^[1-9][0-9]{0,9}$/);
  const requested = process.env.IMAGE_TAG ?? '';
  if (requested && !versionTagPattern.test(requested)) throw new Error('Missing or invalid IMAGE_TAG.');
  const tag = requested || `build-${sha}-run-${run}-${attempt}`;
  const image = `retail-${service}:${tag}`;
  return {
    service,
    tag,
    local_image: image,
    ecr_image: `${ecr}/${image}`,
    gar_image: `${gar}/${image}`,
  };
}
```

In `checkAuth()`, require a formal tag after validating the trusted event:

```javascript
setting('IMAGE_TAG', versionTagPattern);
```

- [ ] **Step 4: Add fail-closed tag absence parsing**

Add these functions above `summary()` in `ci/images.mjs`:

```javascript
function jsonSetting(name) {
  try {
    return JSON.parse(process.env[name] ?? '');
  } catch {
    throw new Error(`Invalid ${name}.`);
  }
}

function assertTagAbsent() {
  setting('IMAGE_TAG', versionTagPattern);
  const ecrLookup = jsonSetting('ECR_LOOKUP_JSON');
  const imageCount = Number.isInteger(ecrLookup.imageCount) ? ecrLookup.imageCount : -1;
  const failureCodes = Array.isArray(ecrLookup.failureCodes) ? ecrLookup.failureCodes : [];
  if (imageCount > 0) throw new Error('Formal image tag already exists in ECR.');
  if (imageCount !== 0 || !failureCodes.includes('ImageNotFound')) {
    throw new Error('Unable to confirm ECR tag absence.');
  }

  const garStatus = process.env.GAR_HTTP_STATUS ?? '';
  if (garStatus === '200') throw new Error('Formal image tag already exists in GAR.');
  if (garStatus !== '404') throw new Error('Unable to confirm GAR tag absence.');
  return { ecr: 'absent', gar: 'absent' };
}

function outcome(name) {
  const value = process.env[name] || 'not-run';
  return ['success', 'failure', 'cancelled', 'skipped', 'not-run'].includes(value) ? value : 'unknown';
}

function summaryIncomplete() {
  const info = metadata();
  const result = {
    service: info.service,
    tag: info.tag,
    ecr: outcome('ECR_PUSH_OUTCOME'),
    gar: outcome('GAR_PUSH_OUTCOME'),
  };
  summary([
    `### ${info.service} ${info.tag}: publication incomplete`, '',
    `- ECR push step: \`${result.ecr}\``,
    `- GAR push step: \`${result.gar}\``, '',
    'Do not treat this run as a verified release.',
    'A tag may remain in one registry. No tag was deleted or overwritten automatically.',
    'Inspect both registries before choosing a recovery action.', '',
  ]);
  return result;
}
```

Extend `execute(command)` with these branches before the unknown-command check:

```javascript
if (command === 'assert-tag-absent') return assertTagAbsent();
if (command === 'summary-incomplete') return summaryIncomplete();
```

After these early branches, retain this exact allowed-command guard before calling `metadata()` for the original commands:

```javascript
if (!['metadata', 'summary-build', 'verify'].includes(command)) throw new Error('Unknown command.');
const info = metadata();
```

- [ ] **Step 5: Update success summaries to identify a formal release**

In the `verify` summary, use this heading and keep both tag and digest references:

```javascript
summary([
  `### ${info.service} ${info.tag}: formal release published`, '',
  '| Registry | Image |', '| --- | --- |',
  `| ECR | \`${info.ecr_image}\` |`,
  `| Artifact Registry | \`${info.gar_image}\` |`, '',
  `Both registry manifest digests: \`${result.digest}\``,
  `Source commit: \`${process.env.GITHUB_SHA}\``, '',
  'Immutable pull references:', '', '```text', result.ecr_pull, result.gar_pull, '```', '',
  'No GitOps configuration or cluster was changed.', '',
]);
```

- [ ] **Step 6: Run image tests**

```powershell
node --test ci/images.test.mjs
```

Expected: all image metadata, authentication, collision, digest, and summary tests PASS.

- [ ] **Step 7: Commit formal image rules**

```powershell
git add ci/images.mjs ci/images.test.mjs
git commit -m "feat: enforce formal image release tags"
```

### Task 4: Pass formal tags through the composite build action

**Files:**
- Modify: `.github/actions/build-image/action.yml`
- Modify: `ci/workflow.test.mjs`

- [ ] **Step 1: Add a failing composite-action contract test**

Append to `ci/workflow.test.mjs`:

```javascript
test('the build action accepts an optional formal tag and exports it', () => {
  assert.match(build, /\n  tag:\n\s+description:/);
  assert.match(build, /IMAGE_TAG: \$\{\{ inputs\.tag \}\}/);
  assert.match(build, /\n  tag:\n\s+description:.*\n\s+value: \$\{\{ steps\.metadata\.outputs\.tag \}\}/);
});
```

- [ ] **Step 2: Run the workflow test and verify failure**

```powershell
node --test ci/workflow.test.mjs
```

Expected: FAIL because the action has no `tag` input or output.

- [ ] **Step 3: Add the optional tag interface**

In `.github/actions/build-image/action.yml`, add this input after `service`:

```yaml
  tag:
    description: Formal vMAJOR.MINOR.PATCH tag for publication; empty for build-only validation.
    required: false
    default: ''
```

Add this output after `gar-image`:

```yaml
  tag:
    description: Validated formal or build-only local tag.
    value: ${{ steps.metadata.outputs.tag }}
```

Rename the metadata step to `Create validated image references` and add the input to its environment:

```yaml
      env:
        SERVICE: ${{ inputs.service }}
        IMAGE_TAG: ${{ inputs.tag }}
```

Do not change the Docker context, platform, OCI source/revision labels, cache, `load: true`, or `push: false` settings.

- [ ] **Step 4: Run focused tests**

```powershell
node --test ci/images.test.mjs ci/workflow.test.mjs
```

Expected: all tests PASS.

- [ ] **Step 5: Commit the action interface**

```powershell
git add .github/actions/build-image/action.yml ci/workflow.test.mjs
git commit -m "feat: pass release tags to image builds"
```

### Task 5: Orchestrate dynamic build and publication matrices

**Files:**
- Modify: `.github/workflows/build-images.yml`
- Modify: `ci/workflow.test.mjs`

- [ ] **Step 1: Replace obsolete static-matrix assertions with failing release-flow assertions**

Remove the test named `all five service builds run without matrix fail-fast cancellation` and append these tests to `ci/workflow.test.mjs`:

```javascript
test('versions.yaml triggers PR and main release planning', () => {
  assert.equal((workflow.match(/- 'versions\.yaml'/g) ?? []).length, 2);
});

test('the plan job emits dynamic build and release matrices', () => {
  assert.match(workflow, /release_matrix: \$\{\{ steps\.release_plan\.outputs\.release_matrix \}\}/);
  assert.match(workflow, /build_matrix: \$\{\{ steps\.release_plan\.outputs\.build_matrix \}\}/);
  assert.match(workflow, /node ci\/releases\.mjs plan/);
  assert.match(workflow, /node --test ci\/images\.test\.mjs ci\/releases\.test\.mjs ci\/workflow\.test\.mjs/);
});

test('build-only and publish jobs use planned matrices without fail-fast cancellation', () => {
  assert.equal((workflow.match(/fail-fast: false/g) ?? []).length, 2);
  assert.match(workflow, /include: \$\{\{ fromJSON\(needs\.plan\.outputs\.build_matrix\) \}\}/);
  assert.match(workflow, /include: \$\{\{ fromJSON\(needs\.plan\.outputs\.release_matrix\) \}\}/);
});

test('formal publication passes the planned version to build and verification', () => {
  const publish = workflow.split('\n  publish:\n')[1] ?? '';
  assert.match(publish, /tag: \$\{\{ matrix\.version \}\}/);
  assert.match(publish, /IMAGE_TAG: \$\{\{ matrix\.version \}\}/);
  assert.ok(!publish.includes('sha-${{'));
});

test('tag absence is confirmed before separate ECR and GAR pushes', () => {
  const publish = workflow.split('\n  publish:\n')[1] ?? '';
  const preflight = publish.indexOf('node ci/images.mjs assert-tag-absent');
  const ecrPush = publish.indexOf('id: push_ecr');
  const garPush = publish.indexOf('id: push_gar');
  assert.ok(preflight >= 0);
  assert.ok(preflight < ecrPush);
  assert.ok(ecrPush < garPush);
  assert.match(publish, /aws ecr batch-get-image/);
  assert.match(publish, /artifactregistry\.googleapis\.com\/v1\//);
});

test('an incomplete publication reports both push step outcomes', () => {
  assert.match(workflow, /ECR_PUSH_OUTCOME: \$\{\{ steps\.push_ecr\.outcome \}\}/);
  assert.match(workflow, /GAR_PUSH_OUTCOME: \$\{\{ steps\.push_gar\.outcome \}\}/);
  assert.match(workflow, /node ci\/images\.mjs summary-incomplete/);
});
```

Keep these existing test cases unchanged: `workflow defaults to read-only permissions`, `only the original repository main branch can enter the publishing job`, `manual runs default to no publication`, `external actions are pinned to full commit SHAs`, `build runs before cloud authentication and does not publish itself`, `publishing verifies registry digests and never edits deployments`, `ECR policy only grants image push/read operations on the five existing repositories`, and `AWS trust is restricted to the inspected GitHub repository identity and main`.

- [ ] **Step 2: Run the workflow tests and verify failure**

```powershell
node --test ci/workflow.test.mjs
```

Expected: FAIL on the new trigger, matrix, formal tag, preflight, and incomplete-summary assertions.

- [ ] **Step 3: Add the version manifest to automatic paths**

Under both `push.paths` and `pull_request.paths`, add:

```yaml
      - 'versions.yaml'
```

Keep all five service paths, `ci/**`, the composite action path, and this workflow path.

- [ ] **Step 4: Replace `checks` with a planning job**

Use this job definition:

```yaml
  plan:
    name: Validate and plan image changes
    runs-on: ubuntu-24.04
    timeout-minutes: 5
    outputs:
      release_matrix: ${{ steps.release_plan.outputs.release_matrix }}
      release_count: ${{ steps.release_plan.outputs.release_count }}
      build_matrix: ${{ steps.release_plan.outputs.build_matrix }}
      build_count: ${{ steps.release_plan.outputs.build_count }}
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false
          fetch-depth: 0
      - name: Test image release and workflow safety rules
        run: node --test ci/images.test.mjs ci/releases.test.mjs ci/workflow.test.mjs
      - name: Reject publication from an unsupported branch or repository
        if: >-
          github.event_name == 'workflow_dispatch' && inputs.publish_images &&
          (github.ref != 'refs/heads/main' || github.repository != 'AWSome-BIT-Bespin/retail-store-app')
        run: |
          echo '::error::Publishing requires the original repository main branch. Select build-only for other branches.'
          exit 1
      - name: Validate versions and create service matrices
        id: release_plan
        env:
          BASE_SHA: ${{ github.event_name == 'pull_request' && github.event.pull_request.base.sha || github.event.before }}
          HEAD_SHA: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || github.sha }}
          FORCE_BUILD_ALL: ${{ github.event_name == 'workflow_dispatch' && !inputs.publish_images }}
        run: node ci/releases.mjs plan
```

- [ ] **Step 5: Replace the build-only job with the planned matrix**

Use this job definition:

```yaml
  build-check:
    name: Build only / ${{ matrix.service }}
    needs: plan
    if: >-
      needs.plan.result == 'success' && needs.plan.outputs.build_count != '0' &&
      (github.event_name == 'pull_request' ||
      (github.event_name == 'workflow_dispatch' && !inputs.publish_images) ||
      (github.event_name == 'push' && needs.plan.outputs.release_count == '0') ||
      github.repository != 'AWSome-BIT-Bespin/retail-store-app')
    runs-on: ubuntu-24.04
    timeout-minutes: 45
    strategy:
      fail-fast: false
      matrix:
        include: ${{ fromJSON(needs.plan.outputs.build_matrix) }}
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false
      - id: image
        uses: ./.github/actions/build-image
        with:
          service: ${{ matrix.service }}
      - name: Report build-only result
        env:
          SERVICE: ${{ matrix.service }}
        run: node ci/images.mjs summary-build
```

- [ ] **Step 6: Change the publish job condition and matrix**

Set `needs`, `if`, and `strategy` to:

```yaml
    needs: plan
    if: >-
      needs.plan.result == 'success' && needs.plan.outputs.release_count != '0' &&
      github.repository == 'AWSome-BIT-Bespin/retail-store-app' &&
      github.ref == 'refs/heads/main' &&
      (github.event_name == 'push' ||
      (github.event_name == 'workflow_dispatch' && inputs.publish_images))
    runs-on: ubuntu-24.04
    timeout-minutes: 45
    permissions:
      contents: read
      id-token: write
    strategy:
      fail-fast: false
      matrix:
        include: ${{ fromJSON(needs.plan.outputs.release_matrix) }}
```

Pass the formal version into both the authentication check and the build action:

```yaml
      - name: Validate publication scope and required identity settings
        env:
          PUBLISH_IMAGES: ${{ inputs.publish_images }}
          IMAGE_TAG: ${{ matrix.version }}
          AWS_ROLE_ARN: ${{ vars.AWS_ROLE_ARN }}
          GCP_WORKLOAD_IDENTITY_PROVIDER: ${{ vars.GCP_WORKLOAD_IDENTITY_PROVIDER }}
          GCP_SERVICE_ACCOUNT: ${{ vars.GCP_SERVICE_ACCOUNT }}
        run: node ci/images.mjs check-auth
      - name: Build the image once
        id: image
        uses: ./.github/actions/build-image
        with:
          service: ${{ matrix.service }}
          tag: ${{ matrix.version }}
```

Keep these exact authentication and login steps after the build:

```yaml
      - name: Authenticate to AWS with OIDC
        uses: aws-actions/configure-aws-credentials@cbe3b392738ccf3f987d68400dafcf4b0624a56c # v6.2.4
        with:
          role-to-assume: ${{ vars.AWS_ROLE_ARN }}
          aws-region: ap-northeast-2
          allowed-account-ids: '350606136784'
          role-session-name: gha-${{ github.run_id }}-${{ github.run_attempt }}-${{ matrix.service }}
      - name: Log in to ECR
        uses: aws-actions/amazon-ecr-login@03f1aad4c6c7ffd436567f42f9384779290529bd # v2.1.7
        with:
          registries: '350606136784'
          mask-password: 'true'
      - name: Authenticate to Google Cloud with Workload Identity
        id: google
        uses: google-github-actions/auth@7c6bc770dae815cd3e89ee6cdf493a5fab2cc093 # v3
        with:
          project_id: kdt4-3
          workload_identity_provider: ${{ vars.GCP_WORKLOAD_IDENTITY_PROVIDER }}
          service_account: ${{ vars.GCP_SERVICE_ACCOUNT }}
          token_format: access_token
          create_credentials_file: false
          export_environment_variables: false
      - name: Log in to Artifact Registry
        uses: docker/login-action@dbcb813823bdd20940b903addbd779551569679f # v4.6.0
        with:
          registry: asia-northeast3-docker.pkg.dev
          username: oauth2accesstoken
          password: ${{ steps.google.outputs.access_token }}
```

- [ ] **Step 7: Add fail-closed ECR and GAR tag preflight**

Insert this step after both Registry login steps and before any `docker tag` or `docker push`:

```yaml
      - name: Confirm the formal tag is unused in both registries
        env:
          SERVICE: ${{ matrix.service }}
          IMAGE_TAG: ${{ matrix.version }}
          GAR_ACCESS_TOKEN: ${{ steps.google.outputs.access_token }}
        run: |
          set -euo pipefail
          ecr_lookup=$(aws ecr batch-get-image \
            --repository-name "retail-${SERVICE}" \
            --image-ids "imageTag=${IMAGE_TAG}" \
            --query '{imageCount:length(images),failureCodes:failures[].failureCode}' \
            --output json)
          gar_url="https://artifactregistry.googleapis.com/v1/projects/kdt4-3/locations/asia-northeast3/repositories/retail-store/packages/retail-${SERVICE}/tags/${IMAGE_TAG}"
          gar_status=$(curl --silent --show-error \
            --output "${RUNNER_TEMP}/gar-tag-${SERVICE}.json" \
            --write-out '%{http_code}' \
            --header "Authorization: Bearer ${GAR_ACCESS_TOKEN}" \
            "$gar_url")
          ECR_LOOKUP_JSON="$ecr_lookup" GAR_HTTP_STATUS="$gar_status" node ci/images.mjs assert-tag-absent
```

`ecr:BatchGetImage` is already present in `ci/iam/aws-ecr-publish-policy.json`. The GCP Artifact Registry Writer role includes tag read permissions needed for the GET request. A network, authentication, authorization, or non-404 API failure exits instead of being interpreted as an unused tag.

- [ ] **Step 8: Split tagging and pushes so partial progress is visible**

Replace the combined push step with:

```yaml
      - name: Apply the same formal tag to both registry references
        env:
          LOCAL_IMAGE: ${{ steps.image.outputs.local-image }}
          ECR_IMAGE: ${{ steps.image.outputs.ecr-image }}
          GAR_IMAGE: ${{ steps.image.outputs.gar-image }}
        run: |
          set -euo pipefail
          docker tag "$LOCAL_IMAGE" "$ECR_IMAGE"
          docker tag "$LOCAL_IMAGE" "$GAR_IMAGE"
      - name: Push the formal image to ECR
        id: push_ecr
        env:
          ECR_IMAGE: ${{ steps.image.outputs.ecr-image }}
        run: docker push "$ECR_IMAGE"
      - name: Push the formal image to GAR
        id: push_gar
        env:
          GAR_IMAGE: ${{ steps.image.outputs.gar-image }}
        run: docker push "$GAR_IMAGE"
```

Keep ECR first because it is the project's primary cloud. Do not add an automatic delete, overwrite, or copy-on-failure step.

- [ ] **Step 9: Pass the formal tag to verification and incomplete summaries**

Set the verification environment to:

```yaml
        env:
          SERVICE: ${{ matrix.service }}
          IMAGE_TAG: ${{ matrix.version }}
          ECR_IMAGE: ${{ steps.image.outputs.ecr-image }}
          GAR_IMAGE: ${{ steps.image.outputs.gar-image }}
```

Replace the old incomplete-publication shell summary with:

```yaml
      - name: Report an incomplete publication
        if: failure()
        env:
          SERVICE: ${{ matrix.service }}
          IMAGE_TAG: ${{ matrix.version }}
          ECR_PUSH_OUTCOME: ${{ steps.push_ecr.outcome }}
          GAR_PUSH_OUTCOME: ${{ steps.push_gar.outcome }}
        run: node ci/images.mjs summary-incomplete
```

- [ ] **Step 10: Run all Node tests**

```powershell
node --test ci/images.test.mjs ci/releases.test.mjs ci/workflow.test.mjs
```

Expected: all tests PASS with zero failures.

- [ ] **Step 11: Inspect the generated workflow diff for security ordering**

```powershell
git diff -- .github/workflows/build-images.yml
rg -n "id-token|Build the image once|Authenticate to AWS|Confirm the formal tag|push_ecr|push_gar|images.mjs verify|summary-incomplete" .github/workflows/build-images.yml
```

Expected order: build, AWS/GCP authentication, collision preflight, ECR push, GAR push, digest verification. `id-token: write` appears only in the publish job.

- [ ] **Step 12: Commit workflow orchestration**

```powershell
git add .github/workflows/build-images.yml ci/workflow.test.mjs
git commit -m "feat: publish versioned images to both registries"
```

### Task 6: Rewrite the team operating guide for formal releases

**Files:**
- Modify: `docs/ci-image-build.md`

- [ ] **Step 1: Replace the stale tag and setup status**

Document these facts explicitly:

```markdown
## 정식 버전 원장

저장소 루트의 `versions.yaml`이 이미지 정식 버전의 원장이다. 팀원은 변경한 서비스의 값만 `vMAJOR.MINOR.PATCH` 형식으로 이전보다 높게 수정한다. Major, Minor, Patch 중 무엇을 올릴지는 변경 영향에 따라 사람이 결정한다.

서비스 소스 변경 후 버전을 올리지 않으면 PR 검사가 실패한다. 버전만 올리는 것은 명시적인 재빌드·릴리스 요청으로 허용한다. Helm Chart는 이미지 버전과 별도로 검토하므로 `src/<서비스>/chart/**` 변경은 이 규칙에서 제외한다.
```

Replace the SHA publication tag section with:

```markdown
## 이미지 태그와 결과 확인

정식 게시 태그는 `versions.yaml`에 기록한 `vMAJOR.MINOR.PATCH` 하나만 사용한다. 새 `sha-*`, `test`, `latest` 태그는 만들지 않는다. 소스 커밋은 이미지의 `org.opencontainers.image.revision` OCI 라벨과 GitHub Actions Summary에서 확인한다.

게시 전 ECR과 GAR 양쪽에서 같은 태그가 없는지 확인한다. 한쪽에라도 존재하거나 존재 여부를 확실히 확인할 수 없으면 덮어쓰지 않고 실패한다.
```

Replace the obsolete statement that cloud identities and repository variables have not been created with this text:

```markdown
AWS OIDC 역할, GCP Workload Identity Federation, GitHub Actions 변수 3개가 구성되어 있으며 기존 양쪽 Registry 게시에서 인증과 digest 일치를 확인했다. 저장소에는 장기 AWS Access Key나 GCP 서비스 계정 JSON을 추가하지 않는다. 식별자인 `AWS_ROLE_ARN`, `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_SERVICE_ACCOUNT`는 GitHub Actions Repository Variables로 유지한다.
```

- [ ] **Step 2: Add the exact team release procedure**

Add this procedure:

```markdown
## 팀원 정식 릴리스 절차

1. 작업 브랜치에서 변경할 서비스 코드를 수정한다.
2. 같은 커밋 또는 PR에서 `versions.yaml`의 해당 서비스 버전을 이전보다 높게 수정한다.
3. `main` 대상 PR을 만들고 `Validate and plan image changes`와 선택된 `Build only` 작업을 확인한다.
4. 팀 운영 기준에 따라 사람이 `Merge pull request`를 누른다.
5. `main`의 `Build and publish / <서비스>` 작업에서 ECR·GAR 게시와 digest 일치를 확인한다.
6. Summary의 정식 태그 주소와 digest를 팀 배포 담당자에게 전달한다.

현재 저장소에는 PR과 리뷰를 강제하는 Ruleset이 없다. 따라서 위 절차는 팀 운영 규칙이며 `main` 직접 Push를 기술적으로 차단하지 않는다.
```

Replace the failure table with this exact table and note:

```markdown
## 실패 대응

| 실패 단계 | 확인할 내용 |
| --- | --- |
| 버전 계획 | `versions.yaml` 구조, SemVer 형식, 이전 버전과 변경 서비스의 대응 |
| Docker 빌드 | 선택된 서비스의 Dockerfile, 의존성 다운로드, 빌드 로그 |
| AWS 인증 | audience/subject, 역할 ARN, OIDC Provider와 신뢰 정책 |
| GCP 인증 | Provider 리소스 이름, ID/ref 조건, 서비스 계정 impersonation 권한 |
| 기존 태그 검사 | ECR `BatchGetImage` 결과와 GAR Tags API의 HTTP 상태 |
| ECR Push | ECR 저장소 존재 여부와 업로드 역할 권한 |
| GAR Push | Artifact Registry 저장소 존재 여부와 Writer 권한 |
| digest 검증 | 양쪽 Push 완료 여부와 실제 manifest digest 차이 |

한쪽 Push만 성공하면 정식 태그가 그 Registry에 남을 수 있다. 워크플로는 이를 자동 삭제하거나 덮어쓰거나 반대쪽으로 복제하지 않는다. Actions Summary에서 ECR·GAR Push 단계 결과를 확인하고 두 Registry의 실제 태그 상태를 검사한 뒤 복구 방법을 사람이 결정한다.
```

- [ ] **Step 3: Update local verification commands**

Use this exact command block:

```powershell
node --test ci/images.test.mjs ci/releases.test.mjs ci/workflow.test.mjs
git diff --check
```

State that these tests perform no cloud authentication, image push, GitOps write, or cluster deployment.

- [ ] **Step 4: Check the document for obsolete SHA publication claims**

```powershell
rg -n "sha-<|새 차수의 고유 태그|다섯 서비스 빌드 후 양쪽 업로드|아직 만들지" docs/ci-image-build.md
```

Expected: no matches. References explaining old historical tags may remain only when explicitly labeled as historical behavior.

- [ ] **Step 5: Commit the operating guide**

```powershell
git add docs/ci-image-build.md
git commit -m "docs: explain formal image release process"
```

### Task 7: Run repository-wide release verification

**Files:**
- Verify: `versions.yaml`
- Verify: `ci/releases.mjs`
- Verify: `ci/releases.test.mjs`
- Verify: `ci/images.mjs`
- Verify: `ci/images.test.mjs`
- Verify: `ci/workflow.test.mjs`
- Verify: `.github/actions/build-image/action.yml`
- Verify: `.github/workflows/build-images.yml`
- Verify: `docs/ci-image-build.md`

- [ ] **Step 1: Run the complete local CI suite**

```powershell
node --test ci/images.test.mjs ci/releases.test.mjs ci/workflow.test.mjs
```

Expected: exit code `0`, zero failed tests, and no skipped safety tests.

- [ ] **Step 2: Exercise the Git-aware planner against `origin/main`**

```powershell
$releaseBase = git rev-parse origin/main
$releaseHead = git rev-parse HEAD
$releaseOutput = Join-Path $env:TEMP "retail-release-plan-$PID.txt"
try {
  $env:BASE_SHA = $releaseBase
  $env:HEAD_SHA = $releaseHead
  $env:GITHUB_OUTPUT = $releaseOutput
  node ci/releases.mjs plan
  Get-Content -LiteralPath $releaseOutput
} finally {
  Remove-Item -LiteralPath $releaseOutput -ErrorAction SilentlyContinue
  Remove-Item Env:BASE_SHA -ErrorAction SilentlyContinue
  Remove-Item Env:HEAD_SHA -ErrorAction SilentlyContinue
  Remove-Item Env:GITHUB_OUTPUT -ErrorAction SilentlyContinue
}
```

Expected: exit code `0`, `release_count=5`, `build_count=5`, and both matrices list all five approved services. This proves the first addition of `versions.yaml` is interpreted as the initial five-service release.

- [ ] **Step 3: Check whitespace and the exact change set**

```powershell
git diff --check origin/main...HEAD
git status --short
git diff --stat origin/main...HEAD
```

Expected: `git diff --check` exits `0`; status is clean after commits; the diff contains only the design/plan documents and the nine implementation files listed above.

- [ ] **Step 4: Reconfirm that CI has no deployment or GitOps write commands**

```powershell
rg -n "kubectl|helm upgrade|argocd|git push|contents: write|packages: write" .github ci
```

Expected: no executable deployment, GitOps mutation, or elevated repository/package permission in the workflow. Documentation or negative test assertions may contain these strings and must be inspected rather than treated as commands.

- [ ] **Step 5: Verify the first release values**

```powershell
Get-Content -LiteralPath versions.yaml
```

Expected values: Cart `v0.0.2`, Catalog `v0.0.2`, Checkout `v0.0.3`, Orders `v0.1.1`, UI `v0.1.6`.

- [ ] **Step 6: Review the branch before any remote action**

```powershell
git log --oneline --decorate origin/main..HEAD
git diff --name-status origin/main...HEAD
```

Expected: focused commits for manifest/planner, formal tag rules, build action, workflow orchestration, and documentation. Do not push or create a PR until the user explicitly approves that external action.

## Remote verification after an approved push and PR

These checks occur only after the user authorizes pushing the branch and creating the PR:

1. Confirm `Validate and plan image changes` succeeds.
2. Confirm five build-only jobs run because `versions.yaml` is being added for the first time.
3. Confirm no ECR/GAR authentication or push occurs on the PR.
4. After a person merges the PR, confirm exactly five publish jobs run with the approved formal versions.
5. Confirm each job reports equal ECR/GAR digest values.
6. Confirm all ten formal tag references exist: five in ECR and five in GAR.
7. Confirm the previous historical tags and digests were not changed.
8. Confirm no GitOps repository, Argo CD application, EKS cluster, or GKE cluster was modified.
