# 팀원용 이미지 빌드 자동화 안내

## 범위와 현재 전제

이 워크플로는 cart, catalog, checkout, orders, ui를 빌드하고 기존 ECR과 GCP Artifact Registry에 올린다. GitOps 저장소, Helm 설정, Argo CD, EKS/GKE는 변경하지 않는다. 기존 `test`, `latest`, 버전 태그를 갱신하거나 이미지를 삭제하지 않는다.

초기 이미지 플랫폼은 **linux/amd64**이다. ARM64 노드 지원을 검증한 구성은 아니므로 배포 담당자는 실제 노드 아키텍처를 확인해야 한다. `src/ui-backup`은 빌드 대상이 아니다.

설정 파일이 존재하는 것과 업로드를 사용할 수 있는 것은 다르다. AWS/GCP 인증과 아래 세 변수가 준비되고 실제 업로드 실행이 성공해야 사용 가능한 상태다. 2026-09-04 최초 확인 시 저장소/Environment 변수와 시크릿은 없었으며, 사용자는 CI용 클라우드 인증도 아직 만들지 않았다고 확인했다. 아래 IAM 파일은 **검토용 정책 템플릿이며 클라우드에 적용된 상태가 아니다.**

## 실행 시점

| 상황 | 동작 | 클라우드 인증 / 업로드 |
| --- | --- | --- |
| `main`에 대상 서비스 코드 또는 CI 파일 변경 | 다섯 서비스 빌드 후 양쪽 업로드 | 사용 |
| `main` 대상 PR의 관련 파일 변경 | 다섯 서비스 빌드 검증 | 사용하지 않음 |
| 수동 실행, `publish_images` 체크 안 함 | 다섯 서비스 빌드 검증 | 사용하지 않음 |
| 원본 저장소의 `main`에서 수동 실행, 체크함 | 다섯 서비스 빌드 후 양쪽 업로드 | 사용 |
| 다른 브랜치/다른 저장소에서 수동 업로드 요청 | 오류로 중단 | 사용하지 않음 |

README와 일반 문서만 바꾸면 자동 이미지 빌드를 시작하지 않는다. 서비스 하나가 바뀌어도 1차 구성에서는 다섯 개를 모두 빌드한다. 서비스별 실패는 다른 서비스 작업을 취소하지 않는다. 실행별 고유 태그를 사용하며 진행 중인 업로드를 새 실행이 자동 취소하지 않는다.

수동 실행: GitHub의 **Actions → Build retail images → Run workflow**에서 브랜치를 확인하고 `publish_images`를 선택한다. 수동 실행 UI는 워크플로 파일이 기본 브랜치에 반영된 후 사용할 수 있다. 검증만 하려면 체크하지 않는다. 업로드하려면 원본 저장소의 `main`에서 체크한다. [GitHub 수동 실행 안내](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)

## 빌드와 업로드 방식

1. Node.js 테스트로 CI 입력 검증 및 실행 제한 규칙을 확인한다.
2. 서비스별 기존 Dockerfile로 이미지를 한 번 빌드해 해당 runner의 Docker에 적재한다.
3. 업로드 실행에서만 빌드 후 단기 AWS/GCP 인증을 받는다.
4. 같은 로컬 이미지에 ECR/GCP 주소를 붙여 두 저장소로 업로드한다.
5. 두 저장소에서 manifest digest를 읽어 일치하는지 확인한다. 불일치 또는 조회 실패는 성공으로 처리하지 않는다.

GitHub Actions는 서버를 직접 설치하지 않는 Ubuntu 24.04 표준 runner를 사용한다. 외부 Actions는 확인한 릴리스의 전체 커밋 SHA에 고정했다. 변경 시 릴리스 노트를 확인하고 빌드 검증을 다시 수행한다. Docker 빌드 캐시만 사용하며, 전체 이미지 tar나 빌드 기록을 GitHub Artifact로 업로드하지 않는다. [Docker 다중 저장소 업로드](https://docs.docker.com/build/ci/github-actions/push-multi-registries/)

### 빌드 성공과 테스트 성공은 다르다

이 1차 워크플로는 **CI 보조 코드의 테스트 + Docker 이미지 빌드**를 검증한다. 기존 Java Dockerfile에는 `-DskipTests`가 있고, 별도의 애플리케이션 단위·통합·주문 시나리오 테스트를 추가하지 않았다. 빌드 성공은 DB 연결, 실제 주문 저장, 배포 정상 동작을 보장하지 않는다.

## 이미지 태그와 결과 확인

태그 형식:

```text
sha-<전체 소스 커밋 SHA>-run-<GitHub 실행 ID>-<재실행 차수>
```

각 꺾쇠 항목은 GitHub 실행 정보에서 자동으로 생성된다. 사용자가 별도로 입력하지 않는다. 같은 커밋을 다시 실행해도 실행 ID 또는 재실행 차수가 달라 새 태그를 사용한다. 태그는 이 워크플로에서 재사용하지 않지만, 레지스트리 자체의 태그 불변성 설정을 바꾸는 것은 아니다.

| 서비스 | ECR 저장소 | Artifact Registry 이미지 경로 |
| --- | --- | --- |
| cart | `retail-cart` | `retail-store/retail-cart` |
| catalog | `retail-catalog` | `retail-store/retail-catalog` |
| checkout | `retail-checkout` | `retail-store/retail-checkout` |
| orders | `retail-orders` | `retail-store/retail-orders` |
| ui | `retail-ui` | `retail-store/retail-ui` |

완료된 실행의 **Summary** 또는 개별 서비스 job 요약에 태그 주소, 검증한 digest, digest로 고정한 pull 주소가 나온다. 빌드 전용 실행에는 **Not published to any registry**라고 표시된다. 아직 업로드하지 않은 예정 주소를 실제 이미지로 사용하면 안 된다.

주소 구조는 다음과 같다. 아래는 자리표시자가 포함된 설명용 형식이고, 실제 사용 시 성공한 실행의 Summary 주소를 복사한다.

```text
350606136784.dkr.ecr.ap-northeast-2.amazonaws.com/retail-<서비스>:<태그>
asia-northeast3-docker.pkg.dev/kdt4-3/retail-store/retail-<서비스>:<태그>
```

팀원 컴퓨터에서는 각 레지스트리에 읽기 권한으로 로그인한 뒤 이미지를 받아야 한다. CI의 업로드 역할은 팀원에게 공유하지 않는다. PowerShell에서 Docker가 설치되고 해당 레지스트리 로그인이 끝난 경우:

```powershell
$retailImageRef = Read-Host '성공한 실행 Summary의 이미지 주소 또는 digest 주소'
docker pull $retailImageRef
if ($LASTEXITCODE -ne 0) { throw '이미지 가져오기 실패: 주소, 로그인, 읽기 권한을 확인하세요.' }
docker image inspect $retailImageRef --format '{{.Os}}/{{.Architecture}}'
```

마지막 출력이 `linux/amd64`인지 확인한다. 이미지를 받는 작업은 클러스터 배포나 GitOps 설정 변경을 수행하지 않는다.

## 인증 준비: 적용 전 담당자 확인 필요

권장 연결은 AWS OIDC 역할과 GCP Workload Identity Federation을 통한 서비스 계정의 단기 토큰이다. 장기 AWS 액세스 키나 서비스 계정 JSON을 코드/대화에 넣지 않는다. 이 문서는 새 클라우드 권한 생성 승인을 대신하지 않는다.

### AWS

- 계정: `350606136784`, ECR 리전: `ap-northeast-2`.
- `https://token.actions.githubusercontent.com` OIDC provider가 이미 있는지 먼저 확인하고 중복 생성하지 않는다. audience는 `sts.amazonaws.com`.
- 이미지 업로드 전용 역할을 만들거나 승인된 기존 역할을 사용한다. 역할 이름은 적용 담당자가 결정한다.
- 신뢰 정책 템플릿: `ci/iam/aws-github-trust-policy.json`.
- ECR 권한 템플릿: `ci/iam/aws-ecr-publish-policy.json`. 기존 다섯 저장소의 업로드와 digest 확인용 읽기만 허용한다. 로그인 토큰 발급에 필요한 `ecr:GetAuthorizationToken`만 `Resource: *`이다. 저장소 생성·삭제, 이미지 삭제, EKS 접근 권한은 포함하지 않는다. [AWS ECR 업로드 권한](https://docs.aws.amazon.com/AmazonECR/latest/userguide/image-push-iam.html)

2026-09-04 GitHub API에서 확인한 이 저장소의 기본 subject prefix는 아래와 같다. `main` 신뢰 정책은 이 prefix에 `:ref:refs/heads/main`을 붙인다.

```text
repo:AWSome-BIT-Bespin@323029296/retail-store-app@1352059579
```

GitHub의 신규 저장소 기본 형식은 이름뿐 아니라 조직/저장소 ID를 포함할 수 있다. 예전 문서의 이름만 있는 subject를 그대로 복사하지 않는다. 저장소 OIDC 설정, 이름, 소유 조직 또는 Environment 사용을 변경하면 신뢰 정책도 재검토해야 한다. [GitHub OIDC subject 형식](https://docs.github.com/en/actions/reference/security/oidc)

### GCP

- 업로드 대상: 프로젝트 `kdt4-3`, 리전 `asia-northeast3`, Artifact Registry 저장소 `retail-store`.
- CI 전용 서비스 계정과 Workload Identity Pool/Provider를 준비한다. 실제 이름·프로젝트 번호는 만들거나 조회한 값만 사용한다.
- Provider의 issuer는 `https://token.actions.githubusercontent.com`으로 설정한다.
- 조직 ID `323029296`, 저장소 ID `1352059579`, `refs/heads/main`, `push` 또는 `workflow_dispatch` 조건으로 발급 범위를 제한한다. 저장소 이름만으로 조직 전체에 접근을 열지 않는다.
- Provider의 attribute mapping에는 `google.subject=assertion.sub`와 조건/바인딩에서 쓰는 저장소 ID, 조직 ID, ref, event_name 속성을 포함한다.
- 허용된 외부 principal에 해당 서비스 계정의 `roles/iam.workloadIdentityUser`를 부여한다.
- 서비스 계정에는 프로젝트 전체가 아닌 **`retail-store` 저장소 범위**의 `roles/artifactregistry.writer`를 부여한다. GKE, Cloud SQL, DMS 권한은 이 CI에 필요하지 않다. [GCP 외부 파이프라인 인증](https://docs.cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines), [Artifact Registry 역할과 범위](https://docs.cloud.google.com/artifact-registry/docs/access-control)
- 필요한 API 활성화 및 조직 정책은 적용 담당자가 확인한다. 워크플로가 API 활성화, 저장소 생성, IAM 정책 수정을 수행하지 않는다.

### GitHub에 입력할 값

원본 앱 저장소의 **Settings → Secrets and variables → Actions → Variables**에 다음 세 값을 넣는다. 값은 식별자이므로 변수로 사용하며, 키 파일이나 액세스 토큰을 넣는 칸이 아니다.

| 변수 | 입력할 실제 값 |
| --- | --- |
| `AWS_ROLE_ARN` | 승인된 ECR 업로드 역할 ARN |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `projects/프로젝트번호/locations/global/workloadIdentityPools/풀ID/providers/제공자ID` 형태의 실제 리소스 이름 |
| `GCP_SERVICE_ACCOUNT` | 위 서비스 계정의 실제 이메일 |

`main`에 쓸 수 있는 사람은 이 파이프라인을 통해 업로드를 시작할 수 있다. 브랜치 보호와 PR 승인 강제는 별도 팀 정책으로 결정하며, 이 작업이 자동 설정하지 않는다. GitOps 저장소 공개 여부는 이미지 빌드 워크플로의 인증을 자동으로 만들어주지 않는다.

## 최초 적용 및 검증 순서

1. PR에서 `Validate CI rules`와 다섯 `Build only` job이 성공했는지 확인한다. 이 단계에는 클라우드 인증이 필요 없다.
2. 승인된 담당자가 AWS/GCP 인증과 GitHub 세 변수를 준비한다.
3. CI 변경 PR을 검토하고 `main`에 반영한다. 최초 워크플로 파일 변경도 자동 실행 대상이다.
4. 다섯 `Build and publish` job의 성공과 서비스별 양쪽 digest 일치를 확인한다.
5. 팀원은 성공한 실행의 Summary에 나온 주소로 pull을 확인하고, 사용할 버전을 별도로 배포 설정에 반영한다.

인증 준비 전에 병합하면 업로드 작업은 필요한 변수 이름을 알려주고 실패한다. 이를 업로드 성공으로 보거나 검증 단계를 제거해서 통과시키면 안 된다.

## 실패 대응

| 실패 단계 | 확인할 내용 |
| --- | --- |
| CI 규칙 검사 | `node --test ci/images.test.mjs ci/workflow.test.mjs` 결과 |
| 인증 설정 사전 검사 | 세 변수의 존재와 형식, 원본 저장소/브랜치/수동 업로드 선택 |
| Docker 빌드 | 해당 서비스 Dockerfile, 의존성 다운로드, 빌드 로그 |
| AWS 인증 | audience/subject, 실제 역할 ARN, OIDC provider와 신뢰 정책 |
| GCP 인증 | Provider 전체 이름, ID/ref 조건, 서비스 계정 impersonation 권한, API 및 권한 전파 |
| 이미지 push | 저장소가 실제로 존재하는지, 저장소 범위 writer/push 권한 |
| digest 확인 | 양쪽 업로드 여부, manifest 읽기 권한, 실제 digest 차이 |

한쪽 업로드만 성공한 경우 해당 이미지가 남을 수 있다. 임의 삭제하거나 다른 저장소의 기존 태그를 덮어쓰지 않는다. 원인을 수정한 뒤 재실행하면 새 차수의 고유 태그를 사용한다. 다섯 서비스가 모두 확인된 실행을 팀 배포 기준으로 삼는다. 실패한 실행의 남은 이미지 정리와 레지스트리 수명 주기 정책은 별도 결정 사항이다.

## 변경 파일과 로컬 검사

- `.github/workflows/build-images.yml`: 실행 조건, 권한, 다섯 서비스 작업, 인증/업로드.
- `.github/actions/build-image/action.yml`: 공통 Docker 빌드.
- `ci/images.mjs`: 입력 검증, 태그/주소 생성, digest 검사, Summary 출력.
- `ci/*.test.mjs`: CI 동작 및 설정 안전장치 회귀 검사.
- `ci/iam/*.json`: 적용 전 검토할 AWS 정책 템플릿.

앱 저장소 루트에서 Node.js 20 이상으로 실행한다. 테스트는 클라우드에 접속하거나 이미지를 업로드하지 않는다.

```powershell
node --test ci/images.test.mjs ci/workflow.test.mjs
git diff --check
```

로컬 검사 통과는 실제 Docker 빌드/업로드 통과를 대신하지 않는다. 실제 결과는 GitHub 실행과 레지스트리 digest로 확인한다.
