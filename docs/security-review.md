# Agent가 판정하는 Local Push Security Review

Scanner는 후보를 찾고 Agent가 문맥에 따라 판단한다. Hook은 실제 Push 직전에 다시 검사해서 정확히 일치하는 로컬 검토 기록만 인정한다. Agent 호출이나 클라우드 전송은 자동으로 수행하지 않는다. 모든 명령은 Clone 루트에서 실행한다. Windows에서는 `python3` 대신 Python 3의 `python`을 사용할 수 있다.

## 개발 세션의 절차

1. 필요한 변경을 Commit한다. `python3 scripts/check-sensitive.py`는 Commit 전 Index/작업 트리 확인용이며 Push 승인을 대신하지 않는다.
2. 작업 Branch를 명시해 보고서를 만든다.

   ```sh
   python3 scripts/security-review.py --prepare origin refs/heads/repository-rules
   git rev-parse --git-path security-review
   ```

   `--prepare`의 Exit 1은 검토가 필요하다는 뜻이다. 출력된 Git 관리 디렉터리의 `security-review/pending.json`을 읽는다. 다른 Branch에서는 Ref 이름을 바꾼다. 일반 `git push`도 검토가 없으면 차단하면서 보고서를 생성한다. 다른 로컬 Ref를 다른 Remote Ref로 보내거나 여러 Ref를 함께 보내는 경우에는 해당 Push로 생성된 보고서를 사용한다.

3. Agent가 `scope.candidates`의 경로·Commit/Index/작업 트리 구분·행 번호·규칙을 확인하고 필요한 로컬 문맥을 검토한다. 보고서는 실제 값과 소스 줄을 포함하지 않는다. 실제 값을 채팅·진단 로그·검토 사유에 복사하지 않는다. `scope`, `scope_id`, 후보 ID는 수정하지 않는다.
4. `reviewer`에 검토한 Agent 식별자, `reason`에 전체 Push 범위와 개인정보 확인 결과를 기록한다. `decisions`의 각 후보 ID 아래에 다음 판정과 구체적 사유를 남긴다.

   - `false_positive`: 정상 코드, 타입 선언, 변수 참조 등 실제 Credential이 아님.
   - `synthetic`: 의도적으로 만든 테스트 데이터이며 실제 계정·개인정보와 무관함을 확인.
   - `secret`: 실제 Credential. Push 차단. 해당 정보가 들어간 모든 전송 대상 Commit에서 제거·대체한 뒤 보고서를 다시 생성. 이미 노출됐다면 폐기/회전. 기존 공유 이력 재작성은 담당자와 협의.
   - `uncertain`: 판단 불가. 추가 근거를 얻을 때까지 Push 보류.

   후보가 0개여도 `reviewer`와 전체 검토 `reason`은 필요하다. Agent가 직접 검토하지 않은 항목을 일괄 승인하지 않는다. 판정 사유 예: "Kotlin의 변수 참조이며 하드코딩된 값이 없음."

5. Agent가 검토 기록을 확정하고 Push한다.

   ```sh
   python3 scripts/security-review.py --approve
   git push origin refs/heads/repository-rules:refs/heads/repository-rules
   ```

   `--approve`는 사유가 있는 `false_positive`/`synthetic` 판정만 수락한다. `secret`/`uncertain`, 누락된 후보 또는 빈 사유는 수락하지 않는다. 승인을 기록한 뒤에도 Hook의 재검사를 통과해야 실제 Push가 허용된다.

## 승인 범위

- 승인 기록은 `git rev-parse --git-path security-review`로 찾는 Clone/Worktree별 Git 관리 영역에 저장한다. Repository 파일로 Commit하거나 다른 Clone에 공유하지 않는다.
- 승인에는 Repository 위치, 목적지, Ref 업데이트, 전송 대상 Commit, 현재 Index와 추적 작업 파일의 내용, Scanner/Hook 코드 해시와 후보 목록이 연결된다. 변경되면 다시 검토한다. 승인 기록은 조건이 동일할 때만 재사용한다.
- Untracked 파일은 Push 대상이 아니므로 검사하지 않는다. 새 파일을 Stage하면 승인 상태가 달라진다.
- 기존 Branch에서는 Remote 이전 Tip을 제외한 새 Commit과 전송 Tip을 검사한다. 새 Branch에서는 목적지의 현재 Branch Tip들을 조회해 이미 존재하는 이력을 제외한다. 필요한 Object가 없으면 Fetch 후 다시 시도한다. 빈 Remote에서는 새로 전달할 이력 전체가 대상이며 최대 256개로 제한한다.
- 각 전송 대상 Commit의 파일 Snapshot을 검사하므로, Secret을 나중 Commit에서 지웠거나 Index만 정리해도 이전 전송 Commit의 후보는 남는다. Submodule, Commit으로 해석할 수 없는 Tag, 검사 실패는 차단한다.
- `--prepare`는 기본적인 같은 이름의 단일 Branch Push를 준비한다. Push 시 Remote 상태·Refspec이 달라졌다면 Hook이 생성한 새 보고서를 검토한다.
- Exit Code: `0` 검사/승인 단계 성공, `1` 검토 필요, `2` 검사 또는 승인 처리 불가. 실행 오류는 오탐으로 승인할 수 없다.

## 한계와 로컬 테스트

검토 기록은 신뢰하는 로컬 Agent의 확인 기록이며 암호학적 서명이나 Agent 신원 인증이 아니다. 로컬 사용자에게 Hook과 기록을 변조할 권한이 있으므로 서버 측 보안 경계가 되지 않는다. 자동으로 모든 개인정보를 발견하거나 판정 사유가 사실인지 증명하지 못한다. 수동 개인정보 확인을 병행한다. 이 보안 판정은 PR 승인과 별개이며, 다른 팀원의 PR 승인을 요구하지 않는다.

네트워크나 실제 Credential 없이 임시 Repository/Bare Remote로 테스트:

```sh
python3 -m unittest discover -s tests -v
```

검증 항목은 Secret Commit 누락 방지, Synthetic/오탐의 항목별 승인, 미확인·실제 Secret 판정 차단, Commit·추적 파일·Remote/Ref 변경에 따른 승인 무효화, Scanner 실패와 출력 비노출을 포함한다. GitHub Actions Build CI는 추가하지 않는다.
