# 변경 내역

버전을 올릴 때는 `gradle.properties`의 `pluginVersion`을 바꾸고 이 파일 맨 위에 같은 버전의 항목을 추가하세요.
`main`에 푸시하면 GitHub Release와 IDE 업데이트가 자동으로 만들어집니다.

## [1.3.0]
- Java processes show what they run: main class or jar (e.g. "Gradle Daemon", "ShopApplication", "app.jar"), Java heap usage and Java version
- Containers tab: Docker containers with published ports, CPU and memory; start, stop, restart, remove; "This project only" for docker compose
- Port conflicts caused by a Docker container now offer "Stop container and rerun" instead of stopping Docker's port forwarder
- Usage alerts: notify when CPU or memory stays above a limit (default 90% for 1 minute), with the top processes and a Stop button
- Java 프로세스에 실행 중인 메인 클래스/jar 이름, 힙 사용량, Java 버전 표시
- 컨테이너 탭: Docker 컨테이너 목록, 게시 포트, 시작·정지·재시작·삭제
- Docker 컨테이너 때문에 생긴 포트 충돌은 "컨테이너 정지 후 다시 실행"으로 해결
- 사용량 경고: CPU나 메모리가 기준(기본 90%, 1분)을 계속 넘으면 알림

## [1.2.0]
- Renamed to Process Diary (new plugin ID io.github.soonsoonyy.processdiary). Uninstall the old "Process Monitor" plugin after installing this one.
- English and Korean UI: follows the IDE language, or pick one in Settings → Tools → Process Diary → Language
- Fixed numbers such as ports and PIDs being shown with thousands separators in some messages
- New plugin icon, MIT license
- 이름이 Process Diary로 바뀌었습니다 (새 플러그인 ID). 설치 후 기존 "Process Monitor" 플러그인은 삭제하세요.
- 영어/한국어 화면 지원: IDE 언어를 따르거나 설정에서 직접 선택

## [1.1.1]
- 업데이트할 때 IDE가 플러그인을 바로 교체하려다 상태 표시줄 위젯이 잠깐 사라지던 문제: 처음부터 "재시작해서 적용"으로 안내

## [1.1.0]
- IDE 자동 업데이트: GitHub Release에서 새 버전을 받아 IntelliJ가 업데이트를 알려줍니다
- 처음 실행 시 "자동 업데이트 켜기" 알림, 설정 화면에서 켜기/끄기
- 그래프 글자를 IDE와 같은 방식으로 그리고, 라벨이 그래프 선에 가려지지 않게 개선 (윈도우에서 글자가 깨져 보이던 문제)

## [1.0.0]
- 상태 표시줄 위젯과 사용량 그래프 팝업 (CPU · 메모리 · 디스크 · 네트워크)
- Process Monitor 도구 창: 목록/트리, 검색(이름·PID·포트·작업 폴더·경로), 순서 고정, 다중 종료, 상세 패널
- Run/Debug 감시: 포트 충돌 알림과 "종료하고 다시 실행", 실행 종료 후 남은 프로세스 알림
- 설정 화면, Find Action/Tools 메뉴 동작, 프로젝트별 보기 상태 기억
