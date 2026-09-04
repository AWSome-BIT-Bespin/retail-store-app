import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { test } from 'node:test';

function source(path) {
  const url = new URL(path, import.meta.url);
  return existsSync(url) ? readFileSync(url, 'utf8') : '';
}

const workflow = source('../.github/workflows/build-images.yml');
const build = source('../.github/actions/build-image/action.yml');

test('workflow defaults to read-only permissions', () => {
  assert.match(workflow, /^permissions:\n  contents: read$/m);
  assert.ok(!workflow.includes('pull_request_target:'));
});

test('only the original repository main branch can enter the publishing job', () => {
  const publish = workflow.split('\n  publish:\n')[1] ?? '';
  assert.match(publish, /github\.repository == 'AWSome-BIT-Bespin\/retail-store-app'/);
  assert.match(publish, /github\.ref == 'refs\/heads\/main'/);
  assert.match(publish, /github\.event_name == 'push'/);
  assert.match(publish, /github\.event_name == 'workflow_dispatch' && inputs\.publish_images/);
  assert.match(publish, /id-token: write/);
  assert.ok(!workflow.split('\n  publish:\n')[0].includes('id-token: write'));
});

test('all five service builds run without matrix fail-fast cancellation', () => {
  const matrices = [...workflow.matchAll(/service: \[cart, catalog, checkout, orders, ui\]/g)];
  assert.equal(matrices.length, 2);
  assert.equal([...workflow.matchAll(/fail-fast: false/g)].length, 2);
});

test('manual runs default to no publication', () => {
  assert.match(workflow, /publish_images:\n\s+description:.*\n\s+type: boolean\n\s+default: false/);
});

test('external actions are pinned to full commit SHAs', () => {
  const uses = [...`${workflow}\n${build}`.matchAll(/uses: (.+)/g)].map(match => match[1].split(' #')[0].trim());
  assert.ok(uses.length >= 8);
  for (const value of uses) {
    assert.ok(value.startsWith('./') || /^[A-Za-z0-9_-]+\/[A-Za-z0-9_-]+@[a-f0-9]{40}$/.test(value), value);
  }
});

test('build runs before cloud authentication and does not publish itself', () => {
  const publish = workflow.split('\n  publish:\n')[1] ?? '';
  assert.ok(publish.indexOf('uses: ./.github/actions/build-image') >= 0);
  assert.ok(publish.indexOf('uses: ./.github/actions/build-image') < publish.indexOf('uses: aws-actions/configure-aws-credentials@'));
  assert.match(build, /load: true/);
  assert.match(build, /push: false/);
  assert.match(build, /platforms: linux\/amd64/);
  assert.match(workflow, /create_credentials_file: false/);
});

test('publishing verifies registry digests and never edits deployments', () => {
  assert.match(workflow, /node ci\/images\.mjs verify/);
  assert.ok(!/\b(kubectl|argocd|helm upgrade)\b/.test(workflow));
  assert.ok(!workflow.includes('contents: write'));
  assert.ok(!workflow.includes('packages: write'));
});

test('ECR policy only grants image push/read operations on the five existing repositories', () => {
  const text = source('./iam/aws-ecr-publish-policy.json');
  assert.ok(text, 'ECR policy template is missing');
  const policy = JSON.parse(text);
  const scoped = policy.Statement.find(statement => statement.Sid === 'PublishAndVerifyRetailImages');
  assert.deepEqual(scoped.Resource, ['cart', 'catalog', 'checkout', 'orders', 'ui'].map(service =>
    `arn:aws:ecr:ap-northeast-2:350606136784:repository/retail-${service}`));
  assert.ok(scoped.Action.every(action => !/Delete|CreateRepository|SetRepositoryPolicy|\*/.test(action)));
  assert.deepEqual(policy.Statement.filter(statement => statement.Resource === '*').map(statement => statement.Action),
    ['ecr:GetAuthorizationToken']);
});

test('AWS trust is restricted to the inspected GitHub repository identity and main', () => {
  const text = source('./iam/aws-github-trust-policy.json');
  assert.ok(text, 'AWS trust policy template is missing');
  const trust = JSON.parse(text).Statement[0];
  assert.equal(trust.Action, 'sts:AssumeRoleWithWebIdentity');
  assert.deepEqual(trust.Condition.StringEquals, {
    'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
    'token.actions.githubusercontent.com:sub': 'repo:AWSome-BIT-Bespin@323029296/retail-store-app@1352059579:ref:refs/heads/main',
  });
});
