# Repository Collaboration and Security Rules

## Session Intake

- 새 개발 세션을 시작할 때 담당 업무 또는 GitHub Issue, 이번 세션의 구현 목표와 완료 조건을 확인한다. 이미 확인된 정보는 반복해서 질문하지 않는다.
- 현재 Branch, Working Tree 변경 사항, 관련 Remote/Branch 상태 등 필요한 최소 Git 상태만 확인하고 적절한 작업 Branch를 판단한다. 전체 Git History, 모든 Issue 또는 PR을 일괄 조회하지 않는다.
- 기존 변경 사항을 덮어쓰거나 삭제하지 않는다. 관련 `ROUTE.md`, `PLAN.md`, `PLAN-SHORT.md`, `PLAN-LONG.md`가 있으면 작업 전에 확인한다.

## Collaboration

- GitHub Issue를 기준으로 업무를 분배한다. Issue 또는 PR에 Module Owner, 구현 담당자, Reviewer와 완료 조건을 명시한다.
- Branch 이름은 `type/issue-number-description` 형식을 사용한다. 예: `feat/12-login-screen`, `fix/34-token-expiry`. 이번 정책 설정용 `repository-rules` Branch는 일회성 예외다.
- Feature Branch는 짧게 유지하고 하나의 Issue 또는 명확한 변경 단위에 집중한다.
- `main`에 직접 Push하지 않는다. PR은 작성자 외 최소 1명의 Reviewer가 검토하고 승인한 후 Squash Merge한다. 병합 후 Feature Branch를 삭제한다.
- 공유 Interface 또는 Architecture를 변경하기 전에 관련 Module Owner와 협의하고 합의 내용을 Issue 또는 PR에 남긴다.
- 이 문서는 협업 정책이다. GitHub 서버의 Branch Protection이나 승인 강제를 자동 설정하지 않는다.

## Security and Privacy

- API Keys, Tokens, Passwords, Private Keys, Session Cookies 및 기타 Credential을 Commit하거나 업로드하지 않는다.
- 학번, 이름, 학업 기록, LMS 개인 데이터 등 개인정보를 Commit하지 않는다. 테스트에는 실제 개인정보가 아닌 Synthetic Test Data를 사용한다.
- 민감정보를 Logs, Screenshots, Fixtures, Issue, PR 또는 테스트 결과에 포함하지 않는다. 진단 결과에도 Secret의 실제 값을 출력하지 않는다.
- `.gitignore`는 이미 추적 중인 파일을 보호하지 않는다. Commit할 파일과 Diff를 직접 검토한다. Scanner가 모든 개인정보 또는 Secret을 검출한다고 가정하지 않는다.
- Push 전에 `python3 scripts/check-sensitive.py`를 실행하고 결과를 확인한다. Windows에서 `python3`가 없다면 Python 3의 `python scripts/check-sensitive.py`를 사용한다.
- Secret이 발견되면 Push를 중단한다. 실제 Secret이 이미 공유되었다면 즉시 폐기/회전하고 담당자와 제거 절차를 협의한다. Scanner를 통과하기 위해 Secret을 단순히 숨기거나 Hook을 우회하지 않는다.

## Local Verification and Setup

- Build와 Test는 로컬에서 실행한다. GitHub Actions 기반 Build CI는 사용하지 않는다.
- 실행하지 않은 Test를 성공했다고 보고하지 않는다. 실행한 명령, 결과와 미검증 범위를 구분해서 기록한다.
- 각 개발자는 Clone마다 Python 3와 Git을 준비하고 다음 설정을 직접 실행한다.

  ```sh
  git config --local core.hooksPath .githooks
  python3 scripts/check-sensitive.py
  ```

- `.githooks/pre-push`는 `python3`를 우선 사용하고, 해당 명령이 Python 3로 실행되지 않으면 `python`을 확인한다. Python 3가 없거나 Scanner가 실패하면 Push를 중단한다.
- Hook 설정은 Git으로 공유되지 않는다. `git config --local --get core.hooksPath`로 `.githooks` 설정을 확인한다. 기존 Hooks가 있다면 이 설정 전에 팀과 통합한다.
- Scanner는 현재 Index와 추적 파일의 작업 트리를 검사한다. 과거 Commit, 삭제된 파일, Untracked 파일, Submodule 내부, 암호화/인코딩/압축된 데이터와 모든 개인정보를 검사하지 못한다. 이전 Commit에 포함된 Secret은 현재 파일에서 제거해도 Push 이력에 남을 수 있으므로 Commit/PR 검토가 추가로 필요하다.
- 이 로컬 Hook은 우회 가능하다. PR Review, 수동 개인정보 확인과 최소 권한 원칙을 함께 적용한다.
