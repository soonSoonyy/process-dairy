package io.github.soonsoonyy.processdiary.data

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.ConcurrentHashMap

/**
 * 윈도우 전용 정보 (IDE에 들어 있는 JNA 로 Windows API 호출).
 *  - 다른 프로세스의 작업 폴더: 프로세스 메모리의 PEB → 실행 매개변수 → CurrentDirectory 를 읽음 (OSHI 는 윈도우에서 지원하지 않음)
 *  - JVM 성능 정보: 윈도우에서는 hsperfdata 파일이 잠겨 있으므로 JVM 이 만든 공유 메모리 "hsperfdata_<사용자>_<PID>" 를 읽음
 * 64비트 IDE 에서 64비트 프로세스만 다루고, 실패하면 조용히 빈 값을 돌려준다.
 */
object WinNative {
    @Suppress("FunctionName")
    private interface Kernel32 : StdCallLibrary {
        fun OpenFileMappingW(access: Int, inherit: Boolean, name: WString): Pointer?
        fun MapViewOfFile(mapping: Pointer, access: Int, offsetHigh: Int, offsetLow: Int, size: Long): Pointer?
        fun UnmapViewOfFile(view: Pointer): Boolean
        fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
        fun ReadProcessMemory(process: Pointer, address: Pointer, buffer: Memory, size: Long, read: LongByReference?): Boolean
        fun IsWow64Process(process: Pointer, result: IntByReference): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    @Suppress("FunctionName")
    private interface NtDll : StdCallLibrary {
        fun NtQueryInformationProcess(process: Pointer, infoClass: Int, info: Memory, length: Int, returned: IntByReference?): Int
    }

    private const val FILE_MAP_READ = 0x0004
    private const val PROCESS_QUERY_INFORMATION = 0x0400
    private const val PROCESS_VM_READ = 0x0010

    private val k32: Kernel32? by lazy { load("kernel32", Kernel32::class.java) }
    private val nt: NtDll? by lazy { load("ntdll", NtDll::class.java) }

    private fun <T : StdCallLibrary> load(name: String, c: Class<T>): T? =
        if (Native.POINTER_SIZE != 8) null else try { Native.load(name, c) } catch (_: Throwable) { null }

    // ---------------------------------------------------------------- JVM 성능 정보 (공유 메모리)
    /** JVM 의 성능 정보를 바이트로 (없으면 null) */
    fun perfData(pid: Int): ByteArray? {
        val k = k32 ?: return null
        val user = System.getProperty("user.name") ?: return null
        return try {
            val mapping = k.OpenFileMappingW(FILE_MAP_READ, false, WString("hsperfdata_${user}_$pid")) ?: return null
            try {
                val view = k.MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, 0) ?: return null
                try {
                    // 앞부분(prologue)의 "used" 값만큼 읽음 (윈도우 JVM 은 리틀 엔디언)
                    val used = view.getInt(8)
                    if (used < 32 || used > (8 shl 20)) null else view.getByteArray(0, used)
                } finally {
                    k.UnmapViewOfFile(view)
                }
            } finally {
                k.CloseHandle(mapping)
            }
        } catch (_: Throwable) { null }
    }

    // ---------------------------------------------------------------- 작업 폴더
    private class Cached(val startTime: Long, val at: Long, val cwd: String)

    private val cwdCache = ConcurrentHashMap<Int, Cached>()

    /** 작업 폴더 (10초 캐시; 못 읽으면 "") */
    fun cwd(pid: Int, startTime: Long): String {
        val now = System.currentTimeMillis()
        cwdCache[pid]?.let { if (it.startTime == startTime && now - it.at < 10_000) return it.cwd }
        val v = readCwd(pid)
        cwdCache[pid] = Cached(startTime, now, v)
        return v
    }

    fun retain(alive: Set<Int>) { cwdCache.keys.retainAll(alive) }

    private fun readCwd(pid: Int): String {
        val k = k32 ?: return ""
        val n = nt ?: return ""
        if (pid <= 4) return ""
        val h = try { k.OpenProcess(PROCESS_QUERY_INFORMATION or PROCESS_VM_READ, false, pid) } catch (_: Throwable) { null } ?: return ""
        try {
            val wow = IntByReference()
            if (k.IsWow64Process(h, wow) && wow.value != 0) return "" // 32비트 프로세스는 구조가 달라 건너뜀
            // PROCESS_BASIC_INFORMATION (64비트): PebBaseAddress 는 8바이트 위치
            val pbi = Memory(48)
            if (n.NtQueryInformationProcess(h, 0, pbi, 48, null) != 0) return ""
            val peb = pbi.getLong(8).takeIf { it != 0L } ?: return ""
            // PEB+0x20: ProcessParameters
            val params = readLong(k, h, peb + 0x20) ?: return ""
            // RTL_USER_PROCESS_PARAMETERS+0x38: CurrentDirectory.DosPath (UNICODE_STRING: Length, MaxLength, 패딩, Buffer)
            val us = read(k, h, params + 0x38, 16) ?: return ""
            val len = us.getShort(0).toInt() and 0xffff
            val buf = us.getLong(8)
            if (len <= 0 || len > 4096 || buf == 0L) return ""
            val chars = read(k, h, buf, len.toLong()) ?: return ""
            val path = String(chars.getCharArray(0, len / 2))
            // "C:\work\app\" → "C:\work\app" (드라이브 루트 "C:\" 는 그대로)
            return if (path.length > 3 && path.endsWith("\\")) path.dropLast(1) else path
        } catch (_: Throwable) {
            return ""
        } finally {
            k.CloseHandle(h)
        }
    }

    private fun read(k: Kernel32, h: Pointer, address: Long, size: Long): Memory? {
        val m = Memory(size)
        return if (k.ReadProcessMemory(h, Pointer(address), m, size, null)) m else null
    }

    private fun readLong(k: Kernel32, h: Pointer, address: Long): Long? = read(k, h, address, 8)?.getLong(0)?.takeIf { it != 0L }
}
