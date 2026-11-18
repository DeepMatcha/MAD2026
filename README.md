# MAD2026

과제, 강의 일정, Notes를 기기에서 관리하는 Android 앱입니다. 수집한 데이터와 사용자 설정은 기기에 저장합니다. [공통 Kotlin API 계약 v0.5](API.md)와 [협업 규칙](AGENTS.md)을 팀 간 기준으로 사용합니다.

## 빌드

JDK 17, Android SDK Platform 36, Build Tools 36.0.0이 필요합니다. 지원 기기는 Android 8.0(API 26) 이상입니다. Android Studio에서 저장소 루트를 열거나, 터미널에서 `ANDROID_HOME`을 지정하고 Gradle Wrapper를 실행합니다.

```sh
./gradlew :app:assembleDebug :contract:testDebugUnitTest :demo-data:testDebugUnitTest :backend:assembleDebug
```

실기기에 설치하려면 `adb devices`에서 대상 시리얼을 확인해 `ADB_SERIAL` 환경 변수로 지정합니다.

```sh
adb -s "$ADB_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
```

CT 777에서는 기존 도구를 사용합니다.

```sh
JAVA_HOME=/opt/android-jdk ANDROID_HOME=/opt/android-sdk GRADLE_USER_HOME=/cache/gradle \
  ./gradlew :app:assembleDebug
```

## 팀별 작업

| 모듈 | 역할 |
| --- | --- |
| `:contract` | 양 팀이 사용하는 Repository·Service 인터페이스, 모델과 구조화된 오류 |
| `:app` | Android 실행 진입점과 프론트엔드 작업 공간 |
| `:demo-data` | 개발용 합성 데이터 및 인메모리 구현 |
| `:backend` | 기기 저장소 및 연동을 구현할 백엔드 작업 공간 |

공통 베이스의 앱은 실행 확인용 화면을 엽니다. 프론트는 `(application as MadApplication).dependencies`로 계약과 예시 데이터를 이용하거나, 테스트에서 `DemoMadDependencies.create()`로 독립 인스턴스를 생성할 수 있습니다. 이 예시 구현은 프로세스 메모리를 사용합니다.

`frontend`와 `backend`는 같은 `main`에서 출발하고 각각 `main`을 대상으로 PR을 엽니다. 계약에 영향을 주는 변경은 [협업 규칙](AGENTS.md)에 따라 조율합니다.

## Canvas·LearningX 접근 조사 기록
#### 당시 접근 확인
- Canvas 과목 정보·공지·과제·제출 상태 및 개별 첨부파일 다운로드
- LearningX 주차·차시, 자료 목록·상세, 영상 메타데이터
- LearningX 게시판 공개글·댓글, 출결 요약, 학습진도
- 강의계획서 뷰어
- 일부 웹 로그인 경로에서 API 키 인증이 동작하는 것을 확인했습니다.

#### 불가능 (단순 API 이용한 Query)
- Canvas 과목 파일 목록 및 일반 웹 세션 발급: 401
- 전자출석부: 별도 SSO 로그인 필요
- LearningX 원본 파일 다운로드: 500 서버 오류, 원인 미확인
