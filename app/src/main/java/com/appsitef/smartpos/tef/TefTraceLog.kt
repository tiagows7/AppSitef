package com.appsitef.smartpos.tef

import android.content.Context
import java.io.File

/** Rastro em arquivo porque o logcat do GPOS700 não devolve o log do app. */
object TefTraceLog {
    fun write(context: Context, line: String) {
        val text = "${System.currentTimeMillis()} $line\n"
        val targets = listOfNotNull(
            runCatching { File(context.filesDir, "tef-trace.txt") }.getOrNull(),
            runCatching { context.getExternalFilesDir(null)?.let { File(it, "tef-trace.txt") } }.getOrNull(),
        )
        targets.forEach { file ->
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(text)
            }
        }
    }
}
