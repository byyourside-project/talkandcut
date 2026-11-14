package com.byyourside.talkandcut.pose

import java.io.File

/** 단위 테스트는 app 모듈 디렉터리에서 돌기 때문에 assets를 파일로 직접 읽는다. */
object TestPoses {
    val assets = File("src/main/assets")
    val catalog: PoseCatalog by lazy { PoseCatalog.parse(File(assets, "pose-catalog.json").readText()) }

    /** WebP(RIFF) 헤더에서 캔버스 크기를 읽는다. VP8X / VP8 / VP8L 지원. */
    fun webpSize(file: File): Pair<Int, Int> {
        val b = file.readBytes()
        fun u(i: Int) = b[i].toInt() and 0xFF
        require(String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP") { "not webp: $file" }
        return when (String(b, 12, 4)) {
            "VP8X" -> (1 + (u(24) or (u(25) shl 8) or (u(26) shl 16))) to (1 + (u(27) or (u(28) shl 8) or (u(29) shl 16)))
            "VP8 " -> ((u(26) or (u(27) shl 8)) and 0x3FFF) to ((u(28) or (u(29) shl 8)) and 0x3FFF)
            "VP8L" -> {
                val bits = u(21) or (u(22) shl 8) or (u(23) shl 16) or (u(24) shl 24)
                (1 + (bits and 0x3FFF)) to (1 + ((bits shr 14) and 0x3FFF))
            }
            else -> error("unknown webp chunk in $file")
        }
    }
}
