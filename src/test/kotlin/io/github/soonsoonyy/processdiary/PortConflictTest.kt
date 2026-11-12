package io.github.soonsoonyy.processdiary

import io.github.soonsoonyy.processdiary.runs.PortConflict
import junit.framework.TestCase

/** 여러 프레임워크의 실제 "포트 사용 중" 오류 문구에서 포트를 정확히 뽑아내는지 */
class PortConflictTest : TestCase() {
    private fun check(line: String, expected: Int?) = assertEquals(line, expected, PortConflict.extractPort(line))

    fun testFrameworks() {
        check("Web server failed to start. Port 8080 was already in use.", 8080)                       // Spring Boot
        check("2026-11-11T06:28:42.123+09:00 ERROR 1234 --- [main] Port 8081 was already in use.", 8081) // Spring Boot (시각 포함)
        check("Error: listen EADDRINUSE: address already in use :::3000", 3000)                          // Node.js
        check("Error: listen EADDRINUSE: address already in use 127.0.0.1:5173", 5173)                   // Node.js
        check("2026/11/11 06:28:42 listen tcp :8080: bind: address already in use", 8080)               // Go (시각 포함)
        check("ERROR:    [Errno 98] error while attempting to bind on address ('127.0.0.1', 8000): address already in use", 8000) // uvicorn
        check("Address already in use - bind(2) for \"127.0.0.1\" port 3000 (Errno::EADDRINUSE)", 3000) // Rails/Puma
        check("Something is already running on port 3000.", 3000)                                        // create-react-app
        check("java.net.BindException: Address already in use: bind 0.0.0.0:9090", 9090)                 // Java
    }

    fun testNoFalsePositives() {
        check("Started Application in 2.345 seconds (process running for 2.9)", null)
        check("12:34:56 Server listening on port 3000", null)          // 정상 로그 (충돌 아님)
        check("12:34:56.789 OSError: [Errno 98] Address already in use", null) // 포트 정보 없음 → 시각을 포트로 오인하지 않음
    }
}
