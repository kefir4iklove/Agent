package com.kefir.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

object Tools {

    private lateinit var workspace: File

    fun init(context: Context) {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        workspace = File(base, "workspace")
        if (!workspace.exists()) workspace.mkdirs()
    }

    fun workspacePath(): String = workspace.absolutePath

    val descriptions = """
        - list_files({"path": "subdir"}) -> список файлов в папке воркспейса
        - read_file({"path": "file.txt"}) -> содержимое файла
        - write_file({"path": "file.txt", "content": "..."}) -> создать/перезаписать файл
        - delete_file({"path": "file.txt"}) -> удалить файл
        - run_shell({"command": "ls -la"}) -> выполнить shell-команду в воркспейсе
    """.trimIndent()

    fun execute(name: String, args: JSONObject): String = when (name) {
        "list_files" -> listFiles(safePath(args.optString("path", "")))
        "read_file" -> readFile(safePath(args.getString("path")))
        "write_file" -> writeFile(safePath(args.getString("path")), args.getString("content"))
        "delete_file" -> deleteFile(safePath(args.getString("path")))
        "run_shell" -> runShell(args.getString("command"))
        else -> "ERROR: unknown tool '$name'"
    }

    private fun safePath(relative: String): File {
        val f = File(workspace, relative.trimStart('/'))
        val canonical = f.canonicalFile
        if (!canonical.path.startsWith(workspace.canonicalFile.path)) {
            throw IllegalArgumentException("Путь вне воркспейса запрещён: $relative")
        }
        return canonical
    }

    private fun listFiles(dir: File): String {
        if (!dir.exists()) return "Папка не существует"
        val items = dir.walkTopDown().take(500).toList()
        if (items.size == 1) return "(пусто)"
        return items.drop(1).joinToString("\n") {
            (if (it.isDirectory) "[dir] " else "") + it.relativeTo(workspace).path
        }
    }

    private fun readFile(f: File): String {
        if (!f.exists() || !f.isFile) return "ERROR: файл не найден: ${f.name}"
        val text = f.readText()
        return if (text.length > 20000) text.take(20000) + "\n…(обрезано)" else text
    }

    private fun writeFile(f: File, content: String): String {
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "OK: записан ${f.relativeTo(workspace).path} (${content.length} символов)"
    }

    private fun deleteFile(f: File): String {
        val ok = f.deleteRecursively()
        return if (ok) "OK: удалено" else "ERROR: не удалось удалить"
    }

    private fun runShell(command: String): String = try {
        val proc = ProcessBuilder("sh", "-c", command)
            .directory(workspace)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        val finished = proc.waitFor()
        val combined = (out + if (err.isNotBlank()) "\nSTDERR:\n$err" else "").trim()
        val truncated = if (combined.length > 8000) combined.take(8000) + "\n…(обрезано)" else combined
        "exit=$finished\n$truncated"
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }
}
