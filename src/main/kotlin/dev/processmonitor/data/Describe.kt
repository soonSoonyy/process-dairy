package dev.processmonitor.data

import dev.processmonitor.model.Category

/** 프로세스 분류와 한국어 설명 */
object Describe {
    private val KNOWN = mapOf(
        // macOS
        "launchd" to "맥의 모든 프로세스를 시작하고 관리하는 가장 첫 번째 프로세스입니다. (PID 1) 종료할 수 없습니다.",
        "kernel_task" to "macOS 커널 자체입니다. CPU 온도가 높으면 일부러 CPU를 점유해 발열을 낮추기도 합니다.",
        "WindowServer" to "화면에 창과 그래픽을 그려주는 핵심 시스템 프로세스입니다. 종료하면 로그아웃됩니다.",
        "loginwindow" to "로그인/로그아웃과 사용자 세션을 관리합니다.",
        "Finder" to "파인더. 파일 탐색과 바탕화면을 담당합니다. 종료해도 자동으로 다시 실행됩니다.",
        "Dock" to "Dock과 Mission Control, 데스크탑 전환을 담당합니다.",
        "SystemUIServer" to "메뉴 막대 오른쪽의 시스템 아이콘을 그립니다.",
        "ControlCenter" to "메뉴 막대의 제어 센터(Wi-Fi, 블루투스, 밝기 등)입니다.",
        "NotificationCenter" to "알림 센터와 위젯을 담당합니다.",
        "mds" to "Spotlight 검색용 메타데이터 인덱스를 관리합니다.",
        "mds_stores" to "Spotlight 검색 인덱스를 디스크에 저장·관리합니다. 큰 파일을 옮긴 직후엔 CPU를 많이 쓸 수 있습니다.",
        "mdworker_shared" to "Spotlight이 파일 내용을 읽어 색인하는 작업자입니다.",
        "coreaudiod" to "맥의 모든 소리 입출력(스피커, 마이크)을 관리합니다.",
        "bluetoothd" to "블루투스 기기 연결을 관리합니다.",
        "cfprefsd" to "앱과 시스템의 환경설정 값을 읽고 쓰는 것을 중계합니다.",
        "fseventsd" to "파일 변경 이벤트를 기록해 Spotlight, Time Machine 등에 알려줍니다.",
        "cloudd" to "iCloud 데이터 동기화를 담당합니다.",
        "bird" to "iCloud Drive 파일 동기화를 담당합니다.",
        "backupd" to "Time Machine 백업을 실행합니다.",
        "softwareupdated" to "macOS 소프트웨어 업데이트를 확인하고 내려받습니다.",
        "sharingd" to "AirDrop, Handoff 등 기기 간 공유 기능을 담당합니다.",
        "Safari" to "Safari 웹 브라우저입니다.",
        "Terminal" to "터미널 앱입니다.",
        "iTerm2" to "iTerm2 터미널 앱입니다.",
        // Windows
        "System" to "Windows 커널과 드라이버가 동작하는 시스템 프로세스입니다. 종료할 수 없습니다.",
        "explorer" to "Windows 탐색기. 작업 표시줄, 시작 메뉴, 바탕화면을 담당합니다.",
        "svchost" to "Windows 서비스 여러 개를 묶어서 실행하는 공용 호스트 프로세스입니다.",
        "csrss" to "Windows 핵심 시스템 프로세스(콘솔/스레드 관리)입니다. 종료하면 시스템이 멈춥니다.",
        "winlogon" to "Windows 로그인/로그아웃을 처리합니다.",
        "services" to "Windows 서비스들을 시작·중지하는 서비스 관리자입니다.",
        "lsass" to "Windows 로그인 인증과 보안 정책을 처리합니다. 종료하면 재부팅됩니다.",
        "dwm" to "창 화면 합성(투명 효과, 애니메이션)을 담당하는 데스크톱 창 관리자입니다.",
        "MsMpEng" to "Microsoft Defender 바이러스 백신 엔진입니다. 검사 중엔 CPU를 많이 쓸 수 있습니다.",
        "SearchIndexer" to "Windows 검색 색인을 만듭니다.",
        "RuntimeBroker" to "스토어 앱의 권한(위치, 마이크 등)을 관리합니다.",
        "conhost" to "명령 프롬프트/콘솔 창을 그려주는 호스트입니다.",
        "taskhostw" to "Windows 예약 작업과 백그라운드 작업을 실행합니다.",
        // 공통 개발 도구
        "idea" to "IntelliJ IDEA (지금 사용 중인 IDE일 수 있습니다).",
        "idea64" to "IntelliJ IDEA (지금 사용 중인 IDE일 수 있습니다).",
        "java" to "Java 프로그램을 실행 중입니다. Gradle 데몬, Spring 서버 등일 수 있으니 실행 명령을 확인하세요.",
        "node" to "Node.js(자바스크립트) 프로그램을 실행 중입니다. 개발 서버인 경우가 많습니다.",
        "python" to "Python 스크립트를 실행 중입니다. 실행 명령에서 어떤 스크립트인지 확인하세요.",
        "python3" to "Python 스크립트를 실행 중입니다. 실행 명령에서 어떤 스크립트인지 확인하세요.",
        "ruby" to "Ruby 프로그램을 실행 중입니다.",
        "docker" to "Docker 관련 프로세스입니다.",
        "ssh" to "SSH로 다른 컴퓨터에 접속 중입니다.",
        "sshd" to "SSH 접속을 받아주는 서버입니다.",
        "zsh" to "zsh 셸(터미널에서 명령을 입력받는 프로그램)입니다.",
        "bash" to "bash 셸(터미널에서 명령을 입력받는 프로그램)입니다.",
        "fsnotifier" to "IntelliJ가 파일 변경을 감지하기 위해 쓰는 보조 프로그램입니다.",
    )
    private val SHELLS = setOf("zsh", "bash", "sh", "fish", "dash", "tcsh", "login", "cmd", "powershell", "pwsh")
    private val DEVS = setOf("python", "python3", "node", "ruby", "java", "go", "docker", "npm", "git", "ssh", "perl", "php",
        "cargo", "rustc", "swift", "clang", "gcc", "make", "vim", "nvim", "code", "idea", "idea64", "gradle", "mvn", "kotlin", "deno", "bun")

    /** 이름에서 .exe 제거 */
    fun baseName(name: String): String = name.removeSuffix(".exe").removeSuffix(".EXE")

    fun classify(path: String, name: String): Category {
        val n = baseName(name)
        val low = n.lowercase()
        val p = path.replace('\\', '/')
        val pl = p.lowercase()
        if (p.startsWith("/System/") || p.startsWith("/usr/libexec") || p.startsWith("/usr/sbin") || p.startsWith("/sbin") ||
            p.startsWith("/Library/Apple") || pl.startsWith("c:/windows/") || n in setOf("launchd", "kernel_task", "System", "Registry", "smss")) {
            return Category.SYSTEM
        }
        if (low in SHELLS) return Category.SHELL
        if (low in DEVS || low.startsWith("python") || p.startsWith("/opt/homebrew") || p.startsWith("/usr/local") ||
            pl.contains("/jetbrains/") || pl.contains("/jdk")) return Category.DEV
        if (p.contains(".app/") || pl.contains("/program files")) return Category.APP
        return Category.OTHER
    }

    fun describe(name: String, path: String, cat: Category): String {
        val n = baseName(name)
        KNOWN[n]?.let { return it }
        val low = n.lowercase()
        if ("helper" in low) {
            val base = n.replace(Regex("\\s*Helper.*$", RegexOption.IGNORE_CASE), "")
            val kind = when {
                "renderer" in low -> " (웹 페이지를 그리는 렌더러)"
                "gpu" in low -> " (그래픽 처리)"
                "plugin" in low -> " (플러그인 실행)"
                else -> ""
            }
            return "'$base' 앱이 작업을 나눠 처리하려고 띄운 보조 프로세스입니다$kind. 브라우저·Electron 앱은 탭/기능마다 여러 개를 만듭니다."
        }
        return when (cat) {
            Category.SYSTEM -> if (n.endsWith("d") || n.endsWith("agent") || path.contains("/usr/libexec"))
                "운영체제가 백그라운드에서 돌리는 시스템 서비스(데몬/에이전트)입니다. 이름 끝의 d는 보통 daemon을 뜻합니다. 함부로 종료하지 않는 것이 좋습니다."
            else "운영체제에 포함된 시스템 프로세스입니다."
            Category.APP -> {
                val m = Regex("/([^/]+)\\.app/").find(path.replace('\\', '/'))
                "'${m?.groupValues?.get(1) ?: n}' 앱이 실행 중인 프로세스입니다."
            }
            Category.SHELL -> "터미널에서 명령을 입력받는 셸 프로세스입니다."
            Category.DEV -> "개발 도구 또는 패키지 관리자로 설치한 프로그램입니다. 실행 명령에서 자세한 내용을 확인하세요."
            Category.OTHER -> "분류되지 않은 프로세스입니다. 경로와 실행 명령을 보고 어떤 프로그램인지 확인해 보세요."
        }
    }
}
