package io.github.soonsoonyy.processdiary.runs

/**
 * 실행 로그에서 "포트가 이미 사용 중" 오류를 찾아 포트 번호를 뽑아낸다.
 * 예)
 *  - Spring Boot : "Web server failed to start. Port 8080 was already in use."
 *  - Node.js     : "Error: listen EADDRINUSE: address already in use :::3000"
 *  - Go          : "listen tcp :8080: bind: address already in use"
 *  - uvicorn     : "error while attempting to bind on address ('127.0.0.1', 8000): address already in use"
 *  - Rails/Puma  : "Address already in use - bind(2) for \"127.0.0.1\" port 3000"
 *  - CRA         : "Something is already running on port 3000."
 */
object PortConflict {
    private val trigger = Regex("(?i)(already in use|EADDRINUSE|address in use|already running on port|BindException)")
    private val portWord = Regex("(?i)\\bport\\s*[:=]?\\s*(\\d{2,5})\\b")
    private val tuple = Regex("\\(\\s*'[^']*'\\s*,\\s*(\\d{2,5})\\s*\\)")
    private val colon = Regex(":(\\d{2,5})\\b")
    private val clock = Regex("\\b\\d{1,2}:\\d{2}(:\\d{2})?([.,]\\d+)?\\b") // 로그 앞의 시각 12:34:56.789

    fun extractPort(line: String): Int? {
        if (!trigger.containsMatchIn(line)) return null
        val port = portWord.find(line)?.groupValues?.get(1)
            ?: tuple.find(line)?.groupValues?.get(1)
            ?: colon.findAll(clock.replace(line, " ")).lastOrNull()?.groupValues?.get(1)
            ?: return null
        return port.toIntOrNull()?.takeIf { it in 1..65535 }
    }
}
