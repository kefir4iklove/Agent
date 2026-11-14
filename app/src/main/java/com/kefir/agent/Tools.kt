package com.kefir.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File

object Tools {

    private var appContext: Context? = null
    private var treeUri: Uri? = null
    private lateinit var localWorkspace: File

    fun init(context: Context, treeUriStr: String?) {
        appContext = context.applicationContext
        localWorkspace = File(context.getExternalFilesDir(null) ?: context.filesDir, "workspace")
        if (!localWorkspace.exists()) localWorkspace.mkdirs()
        this.treeUri = treeUriStr?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    }

    fun isSafMode(): Boolean = treeUri != null

    fun workspacePath(): String {
        val uri = treeUri ?: return localWorkspace.absolutePath
        return uri.lastPathSegment?.substringAfterLast(':') ?: "выбранная папка"
    }

    val descriptions = """
        - list_files({"path": "subdir"}) -> список файлов в папке воркспейса
        - read_file({"path": "file.txt"}) -> содержимое файла
        - write_file({"path": "file.txt", "content": "..."}) -> создать/перезаписать файл
        - delete_file({"path": "file.txt"}) -> удалить файл
        - run_shell({"command": "ls -la"}) -> выполнить shell-команду (только во встроенном воркспейсе)
    """.trimIndent()

    fun execute(name: String, args: JSONObject): String = when (name) {
        "list_files" -> listFiles(args.optString("path", ""))
        "read_file" -> readFile(args.getString("path"))
        "write_file" -> writeFile(args.getString("path"), args.getString("content"))
        "delete_file" -> deleteFile(args.getString("path"))
        "run_shell" -> runShell(args.getString("command"))
        else -> "ERROR: unknown tool '$name'"
    }

    private fun cleanRelative(relative: String): List<String> =
        relative.trim().split('/').filter { it.isNotBlank() && it != "." && it != ".." }

    private fun safRoot(): DocumentFile =
        DocumentFile.fromTreeUri(appContext!!, treeUri!!) ?: throw IllegalStateException("Папка воркспейса недоступна")

    private fun resolveDoc(relative: String, createDirs: Boolean): Pair<DocumentFile, String>? {
        val parts = cleanRelative(relative)
        if (parts.isEmpty()) return null
        var dir = safRoot()
        for (i in 0 until parts.size - 1) {
            val existing = dir.findFile(parts[i])
            dir = when {
                existing != null && existing.isDirectory -> existing
                existing == null && createDirs -> dir.createDirectory(parts[i]) ?: return null
                else -> return null
            }
        }
        return dir to parts.last()
    }

    private fun safFile(relative: String): DocumentFile? {
        val pair = resolveDoc(relative, false) ?: return null
        return pair.first.findFile(pair.second)?.takeIf { it.isFile }
    }

    private fun listFiles(relative: String): String {
        return if (treeUri != null) {
        val start = if (cleanRelative(relative).isEmpty()) safRoot()
        else safFile(relative)?.takeIf { it.isDirectory } ?: return "Папка не существует"
        val out = mutableListOf<String>()
        fun walk(dir: DocumentFile, prefix: String) {
            if (out.size > 500) return
            for (f in dir.listFiles()) {
                val name = prefix + (f.name ?: "?")
                if (f.isDirectory) {
                    out.add("[dir] $name")
                    walk(f, "$name/")
                } else out.add(name)
            }
        }
        walk(start, "")
        if (out.isEmpty()) "(пусто)" else out.joinToString("\n")
    } else {
        val dir = safeLocalPath(relative)
        if (!dir.exists()) return "Папка не существует"
        val items = dir.walkTopDown().take(500).toList()
        if (items.size == 1) "(пусто)"
        else items.drop(1).joinToString("\n") {
            (if (it.isDirectory) "[dir] " else "") + it.relativeTo(localWorkspace).path
        }
        }
    }

    private fun readFile(relative: String): String {
        val text = if (treeUri != null) {
            val doc = safFile(relative) ?: return "ERROR: файл не найден: $relative"
            appContext!!.contentResolver.openInputStream(doc.uri)?.bufferedReader()?.use { it.readText() }
                ?: return "ERROR: не удалось открыть файл"
        } else {
            val f = safeLocalPath(relative)
            if (!f.exists() || !f.isFile) return "ERROR: файл не найден: $relative"
            f.readText()
        }
        return if (text.length > 20000) text.take(20000) + "\n…(обрезано)" else text
    }

    private fun writeFile(relative: String, content: String): String {
        if (treeUri != null) {
            val pair = resolveDoc(relative, true) ?: return "ERROR: не удалось создать путь: $relative"
            val (dir, name) = pair
            dir.findFile(name)?.delete()
            val doc = dir.createFile(mimeFor(name), name) ?: return "ERROR: не удалось создать файл: $name"
            appContext!!.contentResolver.openOutputStream(doc.uri)?.use { it.write(content.toByteArray()) }
                ?: return "ERROR: не удалось открыть файл на запись"
        } else {
            val f = safeLocalPath(relative)
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
        return "OK: записан $relative (${content.length} символов)"
    }

    private fun deleteFile(relative: String): String {
        val ok = if (treeUri != null) {
            safFile(relative)?.delete() ?: false
        } else {
            safeLocalPath(relative).deleteRecursively()
        }
        return if (ok) "OK: удалено" else "ERROR: не удалось удалить"
    }

    private fun runShell(command: String): String {
        if (treeUri != null) {
            return "ERROR: run_shell недоступен, когда воркспейс — выбранная через SAF папка. Файловые инструменты работают, shell — нет."
        }
        return try {
            val proc = ProcessBuilder("sh", "-c", command)
                .directory(localWorkspace)
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

    private fun safeLocalPath(relative: String): File {
        val f = File(localWorkspace, relative.trimStart('/'))
        val canonical = f.canonicalFile
        if (!canonical.path.startsWith(localWorkspace.canonicalFile.path)) {
            throw IllegalArgumentException("Путь вне воркспейса запрещён: $relative")
        }
        return canonical
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "md", "json", "py", "js", "ts", "kt", "kts", "java", "xml", "html", "css",
        "yaml", "yml", "sh", "gradle", "csv", "log", "c", "cpp", "h", "rb", "go", "rs",
        "php", "ini", "toml", "sql", "swift", "dart" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
}
