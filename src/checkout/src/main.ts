import { initializeTelemetry } from './telemetry';

async function main() {
  // APM을 먼저 준비합니다.
  await initializeTelemetry();

  // 앞에서 작성한 bootstrap.ts를 불러옵니다.
  const { bootstrap } = await import('./bootstrap');

  // bootstrap.ts에 있는 앱 시작 함수를 실행합니다.
  await bootstrap();
}

main().catch((error: Error) => {
  console.error(
    '[startup] Checkout failed to start:',
    error.message,
  );

  process.exit(1);
});