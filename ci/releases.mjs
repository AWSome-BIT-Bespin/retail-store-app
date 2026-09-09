import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
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
