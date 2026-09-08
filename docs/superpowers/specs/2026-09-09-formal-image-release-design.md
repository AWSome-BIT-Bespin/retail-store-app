# 정식 이미지 릴리스 설계

## 1. 목적

`retail-store-app`의 다섯 서비스를 현재 소스에서 각각 한 번만 빌드하고, 사람이 정한 정식 버전 태그로 AWS ECR과 Google Artifact Registry(GAR)에 게시한다. 두 Registry에는 같은 이미지와 같은 태그가 저장되어야 하며, 게시 후 manifest digest가 같아야 릴리스 성공으로 판단한다.

이번 구현은 컨테이너 이미지의 버전 결정·검증·게시까지만 담당한다. GitOps 저장소 수정, Helm Chart 게시, Argo CD 동기화, EKS/GKE 배포는 포함하지 않는다.

## 2. 확인된 현재 상태

- 앱 저장소: `AWSome-BIT-Bespin/retail-store-app`
- 대상 서비스: `cart`, `catalog`, `checkout`, `orders`, `ui`
- 기존 CI는 PR에서 다섯 이미지를 빌드만 하고, `main` Push 또는 명시적인 수동 게시 실행에서 `sha-<commit>-run-<run>-<attempt>` 태그를 ECR과 GAR에 게시한다.
- 기존 CI는 같은 로컬 이미지를 양쪽 Registry에 게시하고 digest 일치를 검증한다.
- 기존 정식 버전 이력은 Registry별로 일치하지 않는다.

| 서비스 | ECR에서 확인한 최고 정식 버전 | GAR에서 확인한 최고 정식 버전 | 이번 릴리스 버전 |
| --- | --- | --- | --- |
| Cart | `v0.0.1` | `v0.0.1` | `v0.0.2` |
| Catalog | `v0.0.1` | `v0.0.1` | `v0.0.2` |
| Checkout | `v0.0.2` | `v0.0.1` | `v0.0.3` |
| Orders | `v0.1.0` | `v0.0.1` | `v0.1.1` |
| UI | `v0.1.5` | `v0.1.2` | `v0.1.6` |

Cart와 Catalog도 양쪽의 최고 버전 번호는 같지만 기존 태그가 가리키는 digest가 달랐다. 과거 태그를 이동·덮어쓰기·삭제하지 않는다. 이번 정식 릴리스부터 양쪽 Registry의 최신 릴리스 지점을 일치시키며, 과거 이력 전체를 소급해 일치시키지는 않는다.

현재 GitHub 저장소에는 필수 PR, 필수 리뷰 승인, 필수 상태 검사를 강제하는 Ruleset이 없다. 팀은 PR의 `Merge pull request`를 릴리스 결정 절차로 사용하지만, 이는 GitHub 권한 설정으로 강제되는 승인 게이트가 아니다. `main` 직접 Push 가능성은 이번 범위의 알려진 제한사항이다.

## 3. 버전 원장

저장소 루트의 `versions.yaml`을 이미지 정식 버전의 원장으로 사용한다.

```yaml
images:
  cart: v0.0.2
  catalog: v0.0.2
  checkout: v0.0.3
  orders: v0.1.1
  ui: v0.1.6
```

규칙은 다음과 같다.

- 허용되는 키는 다섯 서비스뿐이며 중복·누락·추가 서비스를 허용하지 않는다.
- 값은 `vMAJOR.MINOR.PATCH` 형식이어야 한다.
- 기존 파일이 있는 경우 변경한 버전은 이전 값보다 SemVer 기준으로 커야 한다.
- Major, Minor, Patch 중 무엇을 올릴지는 사람이 결정한다. Patch가 반드시 1씩 증가할 필요는 없다.
- 버전 항목의 변경 자체를 해당 서비스에 대한 명시적인 릴리스 요청으로 해석한다. 소스 변경 없이 버전만 올리는 것도 허용한다.
- 최초 파일 추가는 위 다섯 항목 모두를 릴리스 대상으로 해석한다.
- 새 릴리스에는 정식 `vX.Y.Z` 태그만 게시한다. 기존 `sha-*` 태그 게시를 계속하지 않는다.
- 소스 커밋은 태그를 추가하는 대신 이미지의 `org.opencontainers.image.revision` OCI 라벨에 기록한다.

## 4. 변경과 버전의 대응

서비스 이미지 변경 범위는 보수적으로 판단한다.

- `src/cart/**` 변경은 Cart 버전 상승을 요구한다.
- `src/catalog/**` 변경은 Catalog 버전 상승을 요구한다.
- `src/checkout/**` 변경은 Checkout 버전 상승을 요구한다.
- `src/orders/**` 변경은 Orders 버전 상승을 요구한다.
- `src/ui/**` 변경은 UI 버전 상승을 요구한다.
- 단, `src/<service>/chart/**`는 이미지 버전 판단에서 제외한다. Helm Chart 버전과 OCI 게시 정책은 별도 설계 대상으로 남긴다.
- `src/ui-backup/**`는 현재 이미지 빌드·릴리스 대상에서 제외한다.
- `ci/**`, `.github/**`, `docs/**`만 변경된 경우 서비스 이미지 버전 상승을 요구하지 않는다.
- 서비스 소스가 변경됐는데 해당 서비스 버전이 상승하지 않으면 PR과 `main` 검사를 실패시킨다.

CI 구현 자체가 변경된 경우 이미지 버전 상승은 요구하지 않지만, 빌드 회귀를 막기 위해 다섯 서비스를 모두 build-only 방식으로 검사한다.

## 5. 실행 흐름

### 5.1 Pull Request

1. Base와 Head의 `versions.yaml` 및 변경 파일을 비교한다.
2. 파일 구조, 서비스 집합, SemVer 형식, 버전 상승, 소스와 버전의 대응을 검증한다.
3. 버전이 변경된 서비스만 build-only 방식으로 빌드한다.
4. CI 구성 자체가 변경된 경우 다섯 서비스를 모두 build-only 방식으로 빌드한다.
5. PR에서는 AWS/GCP에 인증하지 않고 이미지를 게시하지 않는다.
6. 사람이 검사 결과를 확인하고 `Merge pull request`를 눌러 병합 여부를 결정한다.

### 5.2 `main` 반영 후

1. 이전 커밋과 현재 커밋을 비교해 버전이 상승한 서비스 목록을 계산한다.
2. 서비스별 독립 Matrix 작업을 만들고 `fail-fast: false`를 유지한다.
3. 각 서비스 이미지를 `linux/amd64`로 한 번만 빌드한다.
4. 기존 AWS OIDC와 GCP Workload Identity Federation으로 단기 자격 증명을 얻는다.
5. 대상 정식 태그가 ECR 또는 GAR에 이미 있는지 Registry별 읽기 API로 검사한다.
6. 양쪽 모두 태그가 없을 때만 같은 로컬 이미지에 같은 정식 태그를 적용해 게시한다.
7. 게시된 양쪽 manifest digest를 조회해 일치 여부를 검증한다.
8. 결과를 GitHub Actions Job Summary에 기록한다.

기존 `workflow_dispatch` 기능은 유지한다. 수동 build-only는 게시하지 않으며, 명시적인 수동 게시 실행도 `main`의 현재 커밋과 그 부모를 비교해 릴리스 대상을 결정하고 동일한 검증 규칙을 적용한다. 변경된 버전이 없으면 게시할 대상도 없다.

## 6. 게시 결과

서비스별 성공 요약에는 다음 정보가 포함되어야 한다.

- 서비스명
- 정식 태그
- ECR의 태그 포함 이미지 주소
- GAR의 태그 포함 이미지 주소
- 양쪽에서 일치한 manifest digest
- ECR과 GAR의 digest 기반 불변 Pull 주소
- 소스 커밋 SHA

이미지 게시가 성공해도 GitOps 구성이나 Kubernetes 클러스터가 변경되지 않았음을 요약에 명시한다.

## 7. 실패 및 복구 정책

다음 조건은 실패로 처리한다.

- `versions.yaml` 구조 또는 버전 형식 오류
- 버전 감소 또는 서비스 변경 후 버전 미상승
- 지원하지 않는 서비스 입력
- ECR 또는 GAR 중 한쪽이라도 대상 정식 태그가 이미 존재함
- 인증 또는 권한 오류
- 한쪽 Registry 게시 실패
- 양쪽 manifest digest 불일치

두 클라우드 Registry에 대한 게시를 원자적으로 묶을 수 없으므로 한쪽에만 태그가 남는 부분 게시 가능성이 있다. 이 경우 자동 삭제, 자동 덮어쓰기, 반대편으로의 자동 복제를 수행하지 않는다. 해당 서비스를 `publication incomplete`로 표시하고, 어느 Registry까지 게시됐는지 확인할 수 있는 진단 정보를 남긴 뒤 사람이 상태를 검토한다.

두 Registry 모두 태그 불변성을 강제하도록 설정하는 작업은 이번 범위에 포함하지 않는다. 워크플로의 사전 존재 확인으로 덮어쓰기를 방지하지만, 확인과 게시 사이에 다른 주체가 같은 태그를 생성하는 경쟁 조건은 남는다. Registry 수준의 immutable tag 설정은 후속 보강 항목이다.

## 8. 보안 경계

- 기본 Workflow 권한은 `contents: read`로 유지한다.
- `id-token: write`는 신뢰된 원본 저장소의 `main` 게시 작업에만 부여한다.
- 장기 AWS Access Key, GCP 서비스 계정 JSON 또는 기타 키 파일을 저장소와 GitHub Secrets에 추가하지 않는다.
- 기존 AWS OIDC 역할과 GCP WIF 설정을 사용한다.
- 이미지 빌드를 클라우드 인증보다 먼저 실행해 자격 증명이 Build Context에 들어가지 않도록 한다.
- 외부 GitHub Action은 전체 Commit SHA로 고정한다.
- 게시 대상 계정, 리전, 프로젝트, 저장소, 다섯 서비스 이름을 허용 목록으로 제한한다.
- PR 및 Fork에서 게시 작업이 실행되지 않도록 한다.

## 9. 테스트 전략

### 9.1 단위 테스트

- 정상 `versions.yaml` 파싱
- 누락, 추가, 중복 서비스 거부
- 잘못된 SemVer 및 줄바꿈 삽입값 거부
- 버전 감소 거부와 정상적인 Major/Minor/Patch 상승 허용
- 변경 파일에서 서비스 집합 계산
- 서비스 소스 변경 후 버전 미상승 거부
- Chart, `ui-backup`, 문서 전용 변경 제외
- 최초 `versions.yaml` 추가 시 다섯 서비스 선택
- 정식 태그를 ECR/GAR 주소에 동일하게 매핑
- 일치·불일치 digest 처리

### 9.2 Workflow 안전성 테스트

- PR에 `id-token: write`가 없는지 검사
- 원본 저장소 `main` 이외의 게시 차단 검사
- 버전별 동적 Matrix와 `fail-fast: false` 검사
- 기존 태그 발견 시 Push 단계에 진입하지 않는지 검사
- `kubectl`, `helm upgrade`, `argocd` 또는 GitOps 쓰기 작업이 없는지 검사
- 외부 Action의 Commit SHA 고정 검사

### 9.3 실제 최초 릴리스 완료 기준

다섯 서비스 모두 다음 조건을 만족해야 한다.

1. 정해진 정식 태그가 ECR과 GAR에 존재한다.
2. 같은 서비스의 양쪽 manifest digest가 일치한다.
3. GitHub Actions 요약에 태그, 두 주소, digest, 소스 커밋이 표시된다.
4. 기존 정식 태그는 변경되지 않는다.
5. GitOps 저장소와 EKS/GKE에는 변경이 없다.

## 10. GitOps 기록 후속 계획

이미지 릴리스 구현이 검증된 뒤 별도 작업으로 진행한다.

1. 성공한 GitHub Actions 요약에서 서비스별 정식 태그와 digest를 확인한다.
2. GitOps 저장소의 AWS 환경 Values에는 ECR Repository 주소와 정식 태그를 기록한다.
3. GCP 환경 Values에는 GAR Repository 주소와 같은 정식 태그를 기록한다.
4. AWS와 GCP 변경을 하나의 GitOps PR에 함께 담아 두 환경이 같은 애플리케이션 버전을 가리키는지 검토할 수 있게 한다.
5. 병합 전 두 Registry의 태그가 같은 digest를 가리키는지 다시 확인한다.
6. GitOps PR 병합 및 Argo CD Sync 시점의 승인 정책은 배포 설계 단계에서 별도로 확정한다.

Helm Chart 원본 저장 위치와 OCI Chart Registry, Chart 버전 정책은 아직 확정하지 않는다. OCI 방식을 선택하면 GitOps 저장소는 환경별 Values와 Argo CD `Application`/`ApplicationSet`에서 사용할 OCI Chart 버전을 기록하고, Chart 템플릿 전체를 반드시 보관할 필요는 없다.

## 11. 범위 제외

- 기존 정식 태그의 소급 복구 또는 Registry 간 과거 이력 복제
- ECR/GAR Repository 설정 변경과 태그 불변성 강제
- Helm Chart 수정·패키징·OCI 게시
- GitOps 저장소 자동 PR 생성 또는 수정
- Argo CD 설치·설정·동기화
- EKS/GKE 배포와 배포 승인
- 애플리케이션 기능·통합·부하 테스트
- GitHub Ruleset 또는 Branch Protection 변경
