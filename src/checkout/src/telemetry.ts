import { config as loadEnvironment } from 'dotenv';

// 로컬 .env 설정을 먼저 읽습니다.
// 이미 전달된 환경변수는 기본적으로 덮어쓰지 않습니다.
loadEnvironment();

// WhaTap에 필요한 두 설정이 모두 있는지 확인합니다.
function isWhatapEnabled(): boolean {
  return Boolean(
    process.env.WHATAP_LICENSE && process.env.WHATAP_SERVER_HOST,
  );
}

// WhaTap을 사용하지 않고, OTel을 명시적으로 끄지 않았으면 활성화합니다.
// 나중에 app.module.ts에서도 같은 판단을 사용합니다.
export function isOpenTelemetryEnabled(): boolean {
  return !isWhatapEnabled() && process.env.OTEL_ENABLED !== 'false';
}

// 나중에 main.ts에서 호출할 APM 초기화 함수입니다.
export async function initializeTelemetry(): Promise<void> {
  const hasLicense = Boolean(process.env.WHATAP_LICENSE);
  const hasServerHost = Boolean(process.env.WHATAP_SERVER_HOST);

  // 둘 중 하나만 설정했다면 누락된 설정을 알려 줍니다.
  if (hasLicense !== hasServerHost) {
    const missing = hasLicense
      ? 'WHATAP_SERVER_HOST'
      : 'WHATAP_LICENSE';

    throw new Error(
      `${missing} is required when enabling WhaTap. Set both WHATAP_LICENSE and WHATAP_SERVER_HOST.`,
    );
  }

  // WhaTap을 사용할 때만 모듈을 불러옵니다.
  // 이 프로젝트의 whatap 2.0.0은 모듈 로딩 시 초기화가 시작됩니다.
  if (isWhatapEnabled()) {
    await import('whatap');
    return;
  }

  // WhaTap을 사용하지 않는 환경에서는 기존 OTel 설정을 따릅니다.
  if (isOpenTelemetryEnabled()) {
    const { default: otelSDK } = await import('./tracing');
    await otelSDK.start();
  }
}