import { appendFileSync } from 'node:fs';

const services = ['cart', 'catalog', 'checkout', 'orders', 'ui'];
const repository = 'AWSome-BIT-Bespin/retail-store-app';
const ecr = '350606136784.dkr.ecr.ap-northeast-2.amazonaws.com';
const gar = 'asia-northeast3-docker.pkg.dev/kdt4-3/retail-store';

function setting(name, pattern) {
  const value = process.env[name] ?? '';
  if (!value || /[\r\n]/.test(value) || (pattern && !pattern.test(value))) {
    throw new Error(`Missing or invalid ${name}. See docs/ci-image-build.md.`);
  }
  return value;
}

function metadata() {
  const service = setting('SERVICE');
  if (!services.includes(service)) throw new Error('Unsupported SERVICE.');
  const sha = setting('GITHUB_SHA', /^[a-f0-9]{40}$/);
  const run = setting('GITHUB_RUN_ID', /^[1-9][0-9]{0,19}$/);
  const attempt = setting('GITHUB_RUN_ATTEMPT', /^[1-9][0-9]{0,9}$/);
  const tag = `sha-${sha}-run-${run}-${attempt}`;
  const image = `retail-${service}:${tag}`;
  return { service, tag, local_image: image, ecr_image: `${ecr}/${image}`, gar_image: `${gar}/${image}` };
}

function checkAuth() {
  const env = process.env;
  const allowedEvent = env.GITHUB_EVENT_NAME === 'push'
    || (env.GITHUB_EVENT_NAME === 'workflow_dispatch' && env.PUBLISH_IMAGES === 'true');
  if (env.GITHUB_REPOSITORY !== repository || env.GITHUB_REF !== 'refs/heads/main' || !allowedEvent) {
    throw new Error('Publishing is only allowed from the original repository main branch on push or an explicit manual publish run.');
  }
  setting('AWS_ROLE_ARN', /^arn:aws:iam::350606136784:role\/[A-Za-z0-9+=,.@_/-]+$/);
  setting('GCP_WORKLOAD_IDENTITY_PROVIDER', /^projects\/[0-9]+\/locations\/global\/workloadIdentityPools\/[a-z0-9-]+\/providers\/[a-z0-9-]+$/);
  setting('GCP_SERVICE_ACCOUNT', /^[a-z0-9][a-z0-9-]*@[a-z0-9][a-z0-9-]*\.iam\.gserviceaccount\.com$/);
  return { ok: true };
}

function registryDigest(name) {
  let value;
  try {
    value = JSON.parse(process.env[name] ?? '').digest;
  } catch {
    throw new Error(`Invalid ${name}: expected manifest JSON with a digest.`);
  }
  if (typeof value !== 'string' || !/^sha256:[a-f0-9]{64}$/.test(value) || /[\r\n]/.test(value)) {
    throw new Error(`Invalid ${name}: expected a SHA-256 manifest digest.`);
  }
  return value;
}

function summary(lines) {
  if (process.env.GITHUB_STEP_SUMMARY) {
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${lines.join('\n')}\n`, 'utf8');
  }
}

function execute(command) {
  if (command === 'check-auth') return checkAuth();
  if (!['metadata', 'summary-build', 'verify'].includes(command)) throw new Error('Unknown command.');
  const info = metadata();
  if (command === 'metadata') {
    if (process.env.GITHUB_OUTPUT) {
      appendFileSync(process.env.GITHUB_OUTPUT,
        `${Object.entries(info).map(([key, value]) => `${key}=${value}`).join('\n')}\n`, 'utf8');
    }
    return info;
  }
  if (command === 'summary-build') {
    summary([
      `### ${info.service}: build-only validation`, '',
      'Image built successfully for linux/amd64. **Not published to any registry.**', '',
      `Source commit: \`${process.env.GITHUB_SHA}\``,
      'This confirms Docker build success, not application tests or deployment readiness.', '',
    ]);
    return { service: info.service, published: false };
  }
  const ecrDigest = registryDigest('ECR_MANIFEST_JSON');
  const garDigest = registryDigest('GAR_MANIFEST_JSON');
  if (ecrDigest !== garDigest) throw new Error('Registry digests differ; dual publication is not verified.');
  const result = {
    digest: ecrDigest,
    ecr_pull: `${ecr}/retail-${info.service}@${ecrDigest}`,
    gar_pull: `${gar}/retail-${info.service}@${garDigest}`,
  };
  summary([
    `### ${info.service}: published and digest-verified`, '',
    `Tag: \`${info.tag}\``, '',
    '| Registry | Image |', '| --- | --- |',
    `| ECR | \`${info.ecr_image}\` |`,
    `| Artifact Registry | \`${info.gar_image}\` |`, '',
    `Both registry manifest digests: \`${result.digest}\``, '',
    'Immutable pull references:', '', '```text', result.ecr_pull, result.gar_pull, '```', '',
    'No GitOps configuration or cluster was changed. Application tests are outside this initial build workflow.', '',
  ]);
  return result;
}

try {
  console.log(JSON.stringify(execute(process.argv[2])));
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
