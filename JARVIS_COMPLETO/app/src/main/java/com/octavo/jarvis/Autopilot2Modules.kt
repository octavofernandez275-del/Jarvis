package com.octavo.jarvis

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.widget.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile

/** Registro local de experiencias: limitado, versionado y separado de la memoria del chat. */
internal object ExperienceMemory {
    private const val PREF = "jarvis_experience_memory"
    private const val KEY = "events"
    @Synchronized fun add(context: Context, kind: String, title: String, detail: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val list = try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        list.put(JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis())
            .put("kind", kind.take(40)).put("title", title.take(160)).put("detail", detail.take(1200)))
        while (list.length() > 200) list.remove(0)
        prefs.edit().putString(KEY, list.toString()).apply()
    }
    fun recent(context: Context, limit: Int = 30): List<JSONObject> {
        val list = try { JSONArray(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.takeLast(limit.coerceIn(1, 200)).reversed()
    }
}

/** Contrato opcional para un proveedor de imágenes compatible. No presupone endpoints ni claves de terceros. */
internal interface ImageGenerationProvider {
    val providerId: String
    fun isAvailable(context: Context): Boolean
    fun generate(context: Context, prompt: String, destination: File): File
}

internal object ImageGenerationBridge {
    @Volatile var provider: ImageGenerationProvider? = null
    fun isAvailable(context: Context): Boolean = try { provider?.isAvailable(context) == true } catch (_: Exception) { false }
}

/** Comparte únicamente artefactos elegidos por el usuario mediante URI temporal y de solo lectura. */
internal object JarvisFileSharing {
    fun share(context: Context, file: File) {
        require(file.isFile && file.length() > 0L) { "El archivo no existe o está vacío" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = when (file.extension.lowercase(Locale.ROOT)) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            "txt", "kt", "java", "md" -> "text/plain"
            else -> "application/octet-stream"
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Compartir archivo de JARVIS"))
    }
}

/** Empaqueta un proyecto generado sin permitir que rutas externas entren al ZIP. */
internal object DeveloperProjectExporter {
    fun export(context: Context, projectName: String): File {
        val safeName = projectName.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]"), "_").take(40)
        require(safeName.isNotBlank() && !safeName.contains("..")) { "Nombre de proyecto no válido" }
        val storageRoot = context.getExternalFilesDir(null) ?: context.filesDir
        val base = File(storageRoot, "JARVIS/DeveloperWorkspace/$safeName").canonicalFile
        require(base.isDirectory) { "No existe el proyecto. Primero creá el esqueleto." }
        val files = base.walkTopDown().filter { it.isFile }.toList()
        require(files.isNotEmpty()) { "El proyecto está vacío" }
        require(files.size <= 300) { "El proyecto supera el límite de 300 archivos" }
        val total = files.sumOf { it.length() }
        require(total <= 25L * 1024L * 1024L) { "El proyecto supera el límite de 25 MB" }
        val outputDir = File(storageRoot, "JARVIS/DeveloperExports")
        if (!outputDir.exists() && !outputDir.mkdirs()) throw IllegalStateException("No se pudo crear la carpeta de exportación")
        val output = File(outputDir, "${safeName}_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                files.forEach { source ->
                    val canonical = source.canonicalFile
                    require(canonical.path.startsWith(base.path + File.separator)) { "Se detectó una ruta fuera del proyecto" }
                    val relative = base.toPath().relativize(canonical.toPath()).toString().replace(File.separatorChar, '/')
                    require(relative.isNotBlank() && relative.split('/').none { it == ".." || it.isBlank() }) { "Ruta interna no válida" }
                    zip.putNextEntry(ZipEntry(relative))
                    source.inputStream().use { input -> input.copyTo(zip, 16 * 1024) }
                    zip.closeEntry()
                }
            }
            ZipFile(output).use { archive ->
                require(archive.size() == files.size) { "La verificación del ZIP no coincide" }
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    require(!name.startsWith('/') && name.split('/').none { it == ".." }) { "El ZIP contiene una ruta insegura" }
                }
            }
            ExperienceMemory.add(context, "PROJECT_EXPORT", safeName, "ZIP verificado; ${files.size} archivos; $total bytes sin ejecutar")
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }
}

/** Registro de capacidades reales; no anuncia herramientas que no estén implementadas. */
internal object AgentToolRegistry {
    data class Tool(val id: String, val label: String, val description: String, val local: Boolean, val confirmation: Boolean)
    fun available(): List<Tool> = listOf(
        Tool("planner", "Planificador", "Descompone objetivos en pasos y conserva progreso", true, false),
        Tool("research", "Investigación web", "Consulta fuentes públicas y filtra URLs", false, false),
        Tool("download", "Descargas Android", "Usa DownloadManager; nunca ejecuta archivos", true, true),
        Tool("creative", "Creative Studio", "Exporta PDF, DOCX, XLSX, PPTX y TXT", true, false),
        Tool("developer", "Developer Engine", "Crea y revisa archivos de código sin ejecutarlos", true, true),
        Tool("memory", "Memoria de experiencias", "Guarda historial local limitado", true, false),
        Tool("quality", "Control de calidad", "Comprueba archivos, paquetes y riesgos comunes", true, false),
        Tool("images", "Motor de imágenes", "Conector preparado; requiere configurar un motor compatible", false, true)
    )
}

internal object ProactiveAdvisor {
    fun suggestions(context: Context): List<String> {
        val events = ExperienceMemory.recent(context, 80)
        val out = mutableListOf<String>()
        val failures = events.count { it.optString("kind") in setOf("ERROR", "REVISAR") }
        if (failures >= 3) out.add("Hay varios resultados que requirieron revisión. Conviene revisar las fuentes y reforzar la validación antes de automatizar más pasos.")
        if (events.count { it.optString("kind") == "EXPORT" } >= 5) out.add("Se repiten las exportaciones. Podés conservar una plantilla reutilizable para acelerar documentos similares.")
        if (out.isEmpty()) out.add("No hay suficientes experiencias para detectar patrones fiables. Registrá resultados de algunas misiones y volveré a evaluar mejoras.")
        out.add("Las optimizaciones se proponen primero; no se modifican procedimientos ni permisos automáticamente.")
        return out
    }
}

internal object ArtifactQuality {
    data class Report(val ok: Boolean, val lines: List<String>)
    fun inspect(file: File): Report {
        val lines = mutableListOf<String>()
        if (!file.exists()) return Report(false, listOf("El archivo no existe."))
        if (!file.isFile) lines.add("La ruta no es un archivo regular.")
        if (file.length() <= 0L) lines.add("El archivo está vacío.") else lines.add("Tamaño: ${file.length()} bytes.")
        if (file.name.contains("..") || file.name.contains('/') || file.name.contains('\\')) lines.add("Nombre de archivo sospechoso.")
        if (file.extension.lowercase(Locale.ROOT) in setOf("apk", "exe", "sh", "bat", "jar")) lines.add("Tipo ejecutable: no se abrirá ni ejecutará automáticamente.")
        val required = when (file.extension.lowercase(Locale.ROOT)) {
            "docx" -> listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml")
            "xlsx" -> listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml")
            "pptx" -> listOf("[Content_Types].xml", "_rels/.rels", "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels", "ppt/slides/slide1.xml", "ppt/slides/_rels/slide1.xml.rels", "ppt/slideMasters/slideMaster1.xml", "ppt/slideMasters/_rels/slideMaster1.xml.rels", "ppt/slideLayouts/slideLayout1.xml", "ppt/slideLayouts/_rels/slideLayout1.xml.rels", "ppt/theme/theme1.xml")
            else -> emptyList()
        }
        if (required.isNotEmpty()) {
            try {
                ZipFile(file).use { archive ->
                    val names = mutableSetOf<String>(); val entries = archive.entries()
                    while (entries.hasMoreElements()) { val entry = entries.nextElement(); names.add(entry.name); if (entry.name.startsWith("/") || entry.name.split('/').any { it == ".." }) lines.add("La estructura ZIP contiene una ruta insegura.") }
                    val missing = required.filterNot { it in names }
                    if (missing.isNotEmpty()) lines.add("Paquete Office incompleto: faltan ${missing.joinToString()}")
                    else lines.add("Estructura ZIP y componentes principales de Office presentes.")
                }
            } catch (_: Exception) { lines.add("El archivo no es un paquete ZIP válido.") }
        }
        if (lines.isEmpty()) lines.add("Comprobaciones básicas superadas; esto no certifica el contenido ni la ausencia de malware.")
        val invalidOffice = required.isNotEmpty() && lines.any { it.contains("incompleto") || it.contains("no es un paquete") || it.contains("ruta insegura") }
        val invalidPdf = if (file.extension.equals("pdf", true)) {
            try {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    val header = ByteArray(5)
                    raf.readFully(header)
                    val tailSize = minOf(1024L, raf.length()).toInt()
                    val tail = ByteArray(tailSize)
                    raf.seek(raf.length() - tailSize)
                    raf.readFully(tail)
                    val valid = String(header, Charsets.US_ASCII) == "%PDF-" && String(tail, Charsets.ISO_8859_1).contains("%%EOF")
                    if (valid) lines.add("Cabecera y marcador final PDF presentes.") else lines.add("El archivo no presenta una estructura PDF básica válida.")
                    !valid
                }
            } catch (_: Exception) { lines.add("No se pudo verificar la estructura PDF."); true }
        } else false
        return Report(file.isFile && file.length() > 0L && !invalidOffice && !invalidPdf, lines)
    }
}

/** Exportadores OOXML mínimos sin bibliotecas pesadas. Se generan paquetes Office editables. */
internal object CreativeExporter {
    fun export(context: Context, type: String, title: String, body: String): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val dir = File(root, "JARVIS/CreativeStudio")
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("No se pudo crear la carpeta de documentos")
        val safe = title.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').take(48).ifBlank { "documento" }
        val safeBody = body.take(250_000)
        val ext = when (type) { "WORD" -> "docx"; "EXCEL" -> "xlsx"; "POWERPOINT" -> "pptx"; "PDF" -> "pdf"; else -> "txt" }
        val file = File(dir, "${safe}_${System.currentTimeMillis()}.$ext")
        when (type) {
            "PDF" -> pdf(file, title, safeBody)
            "WORD" -> zip(file, wordEntries(title, safeBody))
            "EXCEL" -> zip(file, excelEntries(title, safeBody))
            "POWERPOINT" -> zip(file, pptEntries(title, safeBody))
            else -> file.writeText("$title\n\n$safeBody", Charsets.UTF_8)
        }
        val check = ArtifactQuality.inspect(file)
        if (!check.ok) { file.delete(); throw IllegalStateException(check.lines.joinToString(" ")) }
        ExperienceMemory.add(context, "EXPORT", file.name, "Formato $type; ${file.length()} bytes")
        return file
    }
    private fun zip(file: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(file)).use { z -> entries.forEach { (name, value) -> z.putNextEntry(ZipEntry(name)); z.write(value.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
    }
    private fun xml(s: String) = s.filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun wordEntries(title: String, body: String) = mapOf(
        "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
        "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
        "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:rPr><w:b/><w:sz w:val="32"/></w:rPr><w:t>${xml(title)}</w:t></w:r></w:p>${body.lines().joinToString("") { "<w:p><w:r><w:t xml:space=\"preserve\">${xml(it)}</w:t></w:r></w:p>" }}<w:sectPr/></w:body></w:document>"""
    )
    private fun excelEntries(title: String, body: String): Map<String, String> {
        val rows = (listOf(title) + body.lines()).filter { it.isNotBlank() }
        val cells = rows.mapIndexed { i, value -> "<row r=\"${i + 1}\"><c r=\"A${i + 1}\" t=\"inlineStr\"><is><t>${xml(value)}</t></is></c></row>" }.joinToString("")
        return mapOf(
            "[Content_Types].xml" to """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="JARVIS" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$cells</sheetData></worksheet>"""
        )
    }
    private fun pptEntries(title: String, body: String): Map<String, String> {
        val text = (listOf(title) + body.lines().take(30)).joinToString("") { line -> "<a:p><a:r><a:rPr lang=\"es-AR\"/><a:t>${xml(line)}</a:t></a:r><a:endParaRPr lang=\"es-AR\"/></a:p>" }
        val types = """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/><Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/><Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/></Types>"""
        return mapOf(
            "[Content_Types].xml" to types,
            "_rels/.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>""",
            "ppt/presentation.xml" to """<?xml version="1.0"?><p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst><p:sldSz cx="9144000" cy="5143500"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to """<?xml version="1.0"?><p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/><p:sp><p:nvSpPr><p:cNvPr id="2" name="Texto"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="9144000" cy="5143500"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square"/><a:lstStyle/>$text</p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>""",
            "ppt/slides/_rels/slide1.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>""",
            "ppt/slideMasters/slideMaster1.xml" to """<?xml version="1.0"?><p:sldMaster xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="1" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>""",
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/></Relationships>""",
            "ppt/slideLayouts/slideLayout1.xml" to """<?xml version="1.0"?><p:sldLayout xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" type="blank" preserve="1"><p:cSld name="Blank"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>""",
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>""",
            "ppt/theme/theme1.xml" to """<?xml version="1.0"?><a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="JARVIS"><a:themeElements><a:clrScheme name="JARVIS"><a:dk1><a:srgbClr val="071321"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="17324A"/></a:dk2><a:lt2><a:srgbClr val="EAF7FF"/></a:lt2><a:accent1><a:srgbClr val="28CDFF"/></a:accent1><a:accent2><a:srgbClr val="167DA8"/></a:accent2><a:accent3><a:srgbClr val="53E6B1"/></a:accent3><a:accent4><a:srgbClr val="8AAFC4"/></a:accent4><a:accent5><a:srgbClr val="B4C9D7"/></a:accent5><a:accent6><a:srgbClr val="FFB74D"/></a:accent6><a:hlink><a:srgbClr val="0000FF"/></a:hlink><a:folHlink><a:srgbClr val="800080"/></a:folHlink></a:clrScheme><a:fontScheme name="JARVIS"><a:majorFont><a:latin typeface="Aptos Display"/></a:majorFont><a:minorFont><a:latin typeface="Aptos"/></a:minorFont></a:fontScheme><a:fmtScheme name="JARVIS"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"""
        )
    }
    private fun pdf(file: File, title: String, body: String) {
        val doc = PdfDocument()
        val paint = android.graphics.Paint().apply { color = Color.BLACK; textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
        var pageNumber = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
        page.canvas.drawText(title.take(55), 40f, 55f, paint)
        paint.textSize = 11f; paint.typeface = Typeface.DEFAULT
        var y = 85f
        body.lines().forEach { line ->
            var rest = line
            while (rest.isNotEmpty()) {
                if (y > 810f) {
                    doc.finishPage(page); pageNumber++
                    page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()); y = 50f
                }
                val part = rest.take(88); page.canvas.drawText(part, 40f, y, paint); rest = rest.drop(part.length); y += 16f
            }
            y += 4f
        }
        doc.finishPage(page); FileOutputStream(file).use { doc.writeTo(it) }; doc.close()
    }
}

class CreativeStudioActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); studioUi() }
    private fun studioUi() {
        val root = LinearLayout(this).apply { orientation = 1; setPadding(24, 24, 24, 24); setBackgroundColor(Color.rgb(5, 12, 23)) }
        root.addView(TextView(this).apply { text = "CREATIVE STUDIO"; textSize = 24f; setTextColor(Color.CYAN); typeface = Typeface.DEFAULT_BOLD })
        val title = EditText(this).apply { hint = "Título del archivo"; setTextColor(Color.WHITE); setHintTextColor(0xFF91A9B8.toInt()) }
        val body = EditText(this).apply { hint = "Contenido del documento / datos / guion"; minLines = 8; gravity = Gravity.TOP; setTextColor(Color.WHITE); setHintTextColor(0xFF91A9B8.toInt()) }
        val format = Spinner(this).apply { adapter = ArrayAdapter(this@CreativeStudioActivity, android.R.layout.simple_spinner_dropdown_item, listOf("WORD", "PDF", "EXCEL", "POWERPOINT", "TXT")) }
        root.addView(title); root.addView(format); root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply { text = "GENERAR Y VERIFICAR"; setOnClickListener {
            val t = title.text.toString().trim().ifBlank { "JARVIS_documento" }; val b = body.text.toString()
            if (b.isBlank()) { Toast.makeText(this@CreativeStudioActivity, "Ingresá contenido antes de exportar", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            try {
                val file = CreativeExporter.export(this@CreativeStudioActivity, format.selectedItem.toString(), t, b)
                AlertDialog.Builder(this@CreativeStudioActivity).setTitle("ARCHIVO GENERADO")
                    .setMessage("${file.absolutePath}\n\n${ArtifactQuality.inspect(file).lines.joinToString("\n")}")
                    .setPositiveButton("COMPARTIR") { _, _ -> try { JarvisFileSharing.share(this@CreativeStudioActivity, file) } catch (e: Exception) { Toast.makeText(this@CreativeStudioActivity, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show() } }
                    .setNegativeButton("CERRAR", null).show()
            }
            catch (e: Exception) { Toast.makeText(this@CreativeStudioActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
        } })
        root.addView(Button(this).apply { text = "VOLVER"; setOnClickListener { finish() } }); setContentView(root)
    }
}

class DeveloperEngineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = 1; setPadding(22, 22, 22, 22); setBackgroundColor(Color.rgb(5, 12, 23)) }
        root.addView(TextView(this).apply { text = "DEVELOPER ENGINE"; textSize = 24f; setTextColor(Color.CYAN); typeface = Typeface.DEFAULT_BOLD })
        val name = EditText(this).apply { hint = "Nombre de archivo (ej. Main.kt)"; setTextColor(Color.WHITE); setHintTextColor(0xFF91A9B8.toInt()) }
        val code = EditText(this).apply { hint = "Pegá o escribí código fuente"; minLines = 12; gravity = Gravity.TOP; setTextColor(Color.WHITE); setHintTextColor(0xFF91A9B8.toInt()) }
        root.addView(name); root.addView(code, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply { text = "CREAR ESQUELETO DE PROYECTO ANDROID"; setOnClickListener {
            var projectName = name.text.toString().trim().lowercase(Locale.ROOT).replace(Regex("[^A-Za-z0-9_]"), "_").take(40)
            if (projectName.isNotBlank() && !projectName.first().isLetter()) projectName = "project_$projectName"
            if (projectName.isBlank()) { Toast.makeText(this@DeveloperEngineActivity, "Ingresá un nombre de proyecto", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val storageRoot = getExternalFilesDir(null) ?: filesDir
            val base = File(storageRoot, "JARVIS/DeveloperWorkspace/$projectName")
            val files = mapOf(
                "settings.gradle.kts" to "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { google(); mavenCentral() } }\nrootProject.name = \"$projectName\"\ninclude(\":app\")\n",
                "build.gradle.kts" to "plugins { id(\"com.android.application\") version \"8.7.3\" apply false; id(\"org.jetbrains.kotlin.android\") version \"2.0.21\" apply false }\n",
                "gradle.properties" to "org.gradle.jvmargs=-Xmx1536m\nandroid.useAndroidX=true\nkotlin.code.style=official\n",
                "app/build.gradle.kts" to "plugins { id(\"com.android.application\"); id(\"org.jetbrains.kotlin.android\") }\nandroid { namespace = \"com.example.$projectName\"; compileSdk = 34; defaultConfig { applicationId = \"com.example.$projectName\"; minSdk = 26; targetSdk = 34 } }\n",
                "app/src/main/AndroidManifest.xml" to "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:theme=\"@android:style/Theme.Material.Light.NoActionBar\" android:label=\"$projectName\"><activity android:name=\".MainActivity\" android:exported=\"true\"><intent-filter><action android:name=\"android.intent.action.MAIN\"/><category android:name=\"android.intent.category.LAUNCHER\"/></intent-filter></activity></application></manifest>\n",
                "app/src/main/java/com/example/$projectName/MainActivity.kt" to "package com.example.$projectName\n\nimport android.app.Activity\nimport android.os.Bundle\nimport android.widget.TextView\n\nclass MainActivity : Activity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(TextView(this).apply { text = \"$projectName\" }) } }\n",
                ".github/workflows/build.yml" to """name: Build Android APK
on: [push, workflow_dispatch]
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.10.2'
      - run: gradle assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: android-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
""",
                "README.md" to "# $projectName\n\nEsqueleto Android Kotlin generado por JARVIS Developer Engine. Incluye un workflow de GitHub Actions para compilar el APK. Revisá SDK y dependencias antes de publicar. JARVIS no ejecutó ni compiló este código.\n"
            )
            try { files.forEach { (path, content) -> val target = File(base, path); target.parentFile?.mkdirs(); target.writeText(content, Charsets.UTF_8) }; ExperienceMemory.add(this@DeveloperEngineActivity, "PROJECT", projectName, "Esqueleto Android generado; no compilado"); AlertDialog.Builder(this@DeveloperEngineActivity).setTitle("PROYECTO CREADO").setMessage("${base.absolutePath}\n\n${files.size} archivos generados. El proyecto todavía no fue compilado.").setPositiveButton("OK", null).show() }
            catch (e: Exception) { Toast.makeText(this@DeveloperEngineActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
        } })
        root.addView(Button(this).apply { text = "EXPORTAR PROYECTO COMPLETO A ZIP"; setOnClickListener {
            var projectName = name.text.toString().trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]"), "_").take(40)
            if (projectName.isNotBlank() && !projectName.first().isLetter()) projectName = "project_$projectName"
            if (projectName.isBlank()) { Toast.makeText(this@DeveloperEngineActivity, "Ingresá primero el nombre del proyecto", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            try {
                val archive = DeveloperProjectExporter.export(this@DeveloperEngineActivity, projectName)
                AlertDialog.Builder(this@DeveloperEngineActivity).setTitle("PROYECTO EXPORTADO")
                    .setMessage("${archive.absolutePath}\n\nZIP verificado. El código no fue compilado ni ejecutado.")
                    .setPositiveButton("COMPARTIR ZIP") { _, _ -> try { JarvisFileSharing.share(this@DeveloperEngineActivity, archive) } catch (e: Exception) { Toast.makeText(this@DeveloperEngineActivity, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show() } }
                    .setNegativeButton("CERRAR", null).show()
            } catch (e: Exception) { Toast.makeText(this@DeveloperEngineActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
        } })
        root.addView(Button(this).apply { text = "REVISAR Y GUARDAR"; setOnClickListener {
            val n = name.text.toString().trim(); val source = code.text.toString()
            val errors = mutableListOf<String>()
            if (n.isBlank() || n.contains("..") || n.contains('/') || n.contains('\\')) errors.add("Nombre de archivo no válido")
            if (source.isBlank()) errors.add("El código está vacío")
            if (Regex("Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder\\s*\\(").containsMatchIn(source)) errors.add("Se detectó una llamada de ejecución de procesos; requiere revisión manual")
            if (Regex("(?i)(api[_-]?key|secret|password)\\s*[:=]\\s*[\"'][^\"']{8,}").containsMatchIn(source)) errors.add("Posible secreto incrustado en el código")
            if (errors.isNotEmpty()) { AlertDialog.Builder(this@DeveloperEngineActivity).setTitle("REVISIÓN REQUERIDA").setMessage(errors.joinToString("\n")).setPositiveButton("OK", null).show(); return@setOnClickListener }
            val storageRoot = getExternalFilesDir(null) ?: filesDir
            val dir = File(storageRoot, "JARVIS/DeveloperWorkspace"); if (!dir.exists()) dir.mkdirs()
            val file = File(dir, n); file.writeText(source, Charsets.UTF_8)
            val report = ArtifactQuality.inspect(file)
            ExperienceMemory.add(this@DeveloperEngineActivity, "DEV_SAVE", n, "Guardado en workspace; no ejecutado")
            AlertDialog.Builder(this@DeveloperEngineActivity).setTitle("ARCHIVO GUARDADO").setMessage("${file.absolutePath}\n\n${report.lines.joinToString("\n")}\n\nNo se compiló ni ejecutó el código.").setPositiveButton("OK", null).show()
        } })
        root.addView(Button(this).apply { text = "VOLVER"; setOnClickListener { finish() } }); setContentView(root)
    }
}

class JarvisMemoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = 1; setPadding(22, 22, 22, 22); setBackgroundColor(Color.rgb(5, 12, 23)) }
        root.addView(TextView(this).apply { text = "MEMORIA Y ASESOR PROACTIVO"; textSize = 21f; setTextColor(Color.CYAN); typeface = Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply { text = ProactiveAdvisor.suggestions(this@JarvisMemoryActivity).joinToString("\n\n• ", prefix = "• "); setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 18, 0, 18) })
        AgentToolRegistry.available().forEach { tool -> root.addView(TextView(this).apply { text = "${tool.label} · ${if (tool.local) "LOCAL" else "OPCIONAL"}\n${tool.description}${if (tool.confirmation) " · requiere confirmación" else ""}"; setTextColor(0xFFB8D5E5.toInt()); setPadding(0, 8, 0, 8) }) }
        root.addView(TextView(this).apply { text = "EXPERIENCIAS RECIENTES"; setTextColor(Color.CYAN); textSize = 16f; setPadding(0, 18, 0, 8) })
        ExperienceMemory.recent(this, 20).forEach { event -> root.addView(TextView(this).apply { text = "${event.optString("kind")} · ${event.optString("title")}\n${event.optString("detail").take(260)}"; setTextColor(Color.WHITE); textSize = 12f; setPadding(0, 6, 0, 6) }) }
        val imageStatus = if (ImageGenerationBridge.isAvailable(this)) "Hay un proveedor de imágenes disponible." else "No hay un modelo generador configurado en este proyecto. El módulo no simula resultados ni consume servicios externos sin configuración."
        root.addView(TextView(this).apply { text = "IMÁGENES: $imageStatus"; setTextColor(0xFFFFD180.toInt()); setPadding(0, 16, 0, 12) })
        val scroll = ScrollView(this); scroll.addView(root); setContentView(scroll)
    }
}
