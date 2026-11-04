package com.octavo.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Crea archivos reales sin librerías: PowerPoint (.pptx), Word (.docx) y PDF.
 * Los .pptx y .docx se arman a mano (son ZIP con XML) y los PDF con la herramienta de Android.
 * Todo se guarda en Descargas/JARVIS.
 */
object Documentos {
    /** Una diapositiva (título + viñetas) o una sección de documento (título + párrafos). */
    class Seccion(val titulo: String, val puntos: List<String>)

    private const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    private const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    // ---------- Utilidades ----------

    /** Texto seguro para XML y PDF: sin markdown, sin saltos ni caracteres de control. */
    private fun limpio(s: String): String = TextCleaner.clean(s).ifEmpty { s.trim() }.filter { it.code >= 32 }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun nombre(titulo: String, ext: String): String {
        val base = Parsers.fold(titulo).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifEmpty { "jarvis" }
        return base + "_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + "." + ext
    }

    private fun zip(partes: List<Pair<String, String>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((nombre, contenido) in partes) {
                z.putNextEntry(ZipEntry(nombre))
                z.write(contenido.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** Parte un texto largo en trozos que quepan en una página. */
    private fun trozos(texto: String, max: Int = 900): List<String> {
        if (texto.length <= max) return listOf(texto)
        val salida = ArrayList<String>()
        var actual = StringBuilder()
        for (frase in texto.split(Regex("(?<=[.!?])\\s+"))) {
            if (actual.length + frase.length > max && actual.isNotEmpty()) {
                salida.add(actual.toString().trim())
                actual = StringBuilder()
            }
            actual.append(frase).append(' ')
        }
        if (actual.isNotBlank()) salida.add(actual.toString().trim())
        return salida
    }

    // ---------- PowerPoint (.pptx) ----------

    private fun parrafoPptx(txt: String, sz: Int, color: String, negrita: Boolean = false, vineta: Boolean = false): String {
        val ppr = if (vineta) {
            """<a:pPr marL="342900" indent="-342900"><a:spcBef><a:spcPts val="900"/></a:spcBef><a:buClr><a:srgbClr val="FF2B3A"/></a:buClr><a:buChar char="&#8226;"/></a:pPr>"""
        } else {
            """<a:pPr marL="0" indent="0"><a:buNone/></a:pPr>"""
        }
        val b = if (negrita) 1 else 0
        return """<a:p>$ppr<a:r><a:rPr lang="es-AR" sz="$sz" b="$b" dirty="0"><a:solidFill><a:srgbClr val="$color"/></a:solidFill></a:rPr><a:t>${esc(txt)}</a:t></a:r></a:p>"""
    }

    private fun cajaPptx(id: Int, nombre: String, x: Int, y: Int, cx: Int, cy: Int, parrafos: String, anclaje: String = "t"): String =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$nombre"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square" anchor="$anclaje"><a:normAutofit/></a:bodyPr><a:lstStyle/>$parrafos</p:txBody></p:sp>"""

    private fun barraPptx(id: Int, x: Int, y: Int, cx: Int, cy: Int, color: String): String =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="Barra"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="$color"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="es-AR"/></a:p></p:txBody></p:sp>"""

    private fun diapoPortada(titulo: String, sub: String): String =
        Plantillas.SLIDE_HEAD +
            barraPptx(2, 700000, 1500000, 90000, 1700000, "FF2B3A") +
            cajaPptx(3, "Titulo", 950000, 1400000, 10200000, 1900000, parrafoPptx(titulo, 5000, "18B6FF", true), "ctr") +
            cajaPptx(4, "Subtitulo", 950000, 3500000, 10200000, 700000, parrafoPptx(sub, 2200, "9FE8FF")) +
            Plantillas.SLIDE_TAIL

    private fun diapoContenido(titulo: String, puntos: List<String>): String {
        val cuerpo = puntos.joinToString("") { parrafoPptx(it, 2600, "E6F6FF", false, true) }
        return Plantillas.SLIDE_HEAD +
            cajaPptx(2, "Titulo", 600000, 330000, 10992000, 900000, parrafoPptx(titulo, 3800, "18B6FF", true), "b") +
            barraPptx(3, 700000, 1300000, 1800000, 50000, "FF2B3A") +
            cajaPptx(4, "Cuerpo", 600000, 1550000, 10992000, 4700000, cuerpo) +
            Plantillas.SLIDE_TAIL
    }

    /** Presentación de PowerPoint con portada + una diapositiva por sección. */
    fun presentacionPptx(ctx: Context, titulo: String, subtitulo: String, diapos: List<Seccion>): Web.Archivo {
        val n = diapos.size + 1
        val partes = ArrayList<Pair<String, String>>()
        partes.add("[Content_Types].xml" to Plantillas.PPTX_CT_HEAD +
            (1..n).joinToString("") { Plantillas.PPTX_CT_SLIDE.replace("{N}", it.toString()) } + Plantillas.PPTX_CT_TAIL)
        partes.add("_rels/.rels" to Plantillas.PPTX_RELS)
        partes.add("ppt/presentation.xml" to Plantillas.PRES_HEAD +
            (1..n).joinToString("") { Plantillas.PRES_SLD.replace("{ID}", (255 + it).toString()).replace("{R}", (it + 1).toString()) } +
            Plantillas.PRES_TAIL)
        partes.add("ppt/_rels/presentation.xml.rels" to Plantillas.PRES_RELS_HEAD +
            (1..n).joinToString("") { Plantillas.PRES_RELS_SLD.replace("{R}", (it + 1).toString()).replace("{N}", it.toString()) } +
            Plantillas.PRES_RELS_TAIL.replace("{R}", (n + 2).toString()))
        partes.add("ppt/slideMasters/slideMaster1.xml" to Plantillas.MASTER)
        partes.add("ppt/slideMasters/_rels/slideMaster1.xml.rels" to Plantillas.MASTER_RELS)
        partes.add("ppt/slideLayouts/slideLayout1.xml" to Plantillas.LAYOUT)
        partes.add("ppt/slideLayouts/_rels/slideLayout1.xml.rels" to Plantillas.LAYOUT_RELS)
        partes.add("ppt/theme/theme1.xml" to Plantillas.THEME)
        partes.add("ppt/slides/slide1.xml" to diapoPortada(limpio(titulo), limpio(subtitulo)))
        partes.add("ppt/slides/_rels/slide1.xml.rels" to Plantillas.SLIDE_RELS)
        diapos.forEachIndexed { i, d ->
            val puntos = d.puntos.map { limpio(it).take(150) }.filter { it.isNotEmpty() }.take(5)
            partes.add("ppt/slides/slide${i + 2}.xml" to diapoContenido(limpio(d.titulo).take(80), puntos))
            partes.add("ppt/slides/_rels/slide${i + 2}.xml.rels" to Plantillas.SLIDE_RELS)
        }
        return Web.guardarBytes(ctx, nombre(titulo, "pptx"), MIME_PPTX, zip(partes))
    }

    // ---------- Word (.docx) ----------

    /** Documento de Word con título y secciones (cada sección: encabezado + párrafos). */
    fun documentoDocx(ctx: Context, titulo: String, secciones: List<Seccion>): Web.Archivo {
        val cuerpo = StringBuilder()
        cuerpo.append("""<w:p><w:pPr><w:spacing w:after="240"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val="0B3C6B"/><w:sz w:val="44"/></w:rPr><w:t xml:space="preserve">${esc(limpio(titulo))}</w:t></w:r></w:p>""")
        for (s in secciones) {
            cuerpo.append("""<w:p><w:pPr><w:keepNext/><w:spacing w:before="280" w:after="120"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val="18B6FF"/><w:sz w:val="30"/></w:rPr><w:t xml:space="preserve">${esc(limpio(s.titulo))}</w:t></w:r></w:p>""")
            for (p in s.puntos) {
                val t = limpio(p)
                if (t.isEmpty()) continue
                cuerpo.append("""<w:p><w:pPr><w:spacing w:after="120"/></w:pPr><w:r><w:rPr><w:sz w:val="22"/></w:rPr><w:t xml:space="preserve">${esc(t)}</w:t></w:r></w:p>""")
            }
        }
        val partes = listOf(
            "[Content_Types].xml" to Plantillas.DOCX_CT,
            "_rels/.rels" to Plantillas.DOCX_RELS,
            "word/document.xml" to Plantillas.DOCX_HEAD + cuerpo.toString() + Plantillas.DOCX_TAIL
        )
        return Web.guardarBytes(ctx, nombre(titulo, "docx"), MIME_DOCX, zip(partes))
    }

    // ---------- PDF ----------

    private fun terminar(doc: PdfDocument): ByteArray {
        val bos = ByteArrayOutputStream()
        doc.writeTo(bos)
        doc.close()
        return bos.toByteArray()
    }

    /** PDF de texto tamaño A4 con título, encabezados y párrafos. */
    fun documentoPdf(ctx: Context, titulo: String, secciones: List<Seccion>): Web.Archivo {
        val doc = PdfDocument()
        val ancho = 595
        val alto = 842
        val margen = 48
        val util = ancho - 2 * margen
        var numero = 1
        var pagina = doc.startPage(PdfDocument.PageInfo.Builder(ancho, alto, numero).create())
        var y = margen.toFloat()
        val pTitulo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; typeface = Typeface.DEFAULT_BOLD; color = 0xFF0B3C6B.toInt() }
        val pSeccion = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f; typeface = Typeface.DEFAULT_BOLD; color = 0xFF0A7BC4.toInt() }
        val pTexto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = 0xFF202020.toInt() }

        fun bloque(texto: String, paint: TextPaint, despues: Float) {
            val layout = StaticLayout.Builder.obtain(texto, 0, texto.length, paint, util).setLineSpacing(2f, 1f).build()
            if (y + layout.height > alto - margen) {
                doc.finishPage(pagina)
                numero++
                pagina = doc.startPage(PdfDocument.PageInfo.Builder(ancho, alto, numero).create())
                y = margen.toFloat()
            }
            val c = pagina.canvas
            c.save()
            c.translate(margen.toFloat(), y)
            layout.draw(c)
            c.restore()
            y += layout.height + despues
        }

        bloque(limpio(titulo), pTitulo, 18f)
        for (s in secciones) {
            bloque(limpio(s.titulo), pSeccion, 6f)
            for (p in s.puntos) {
                val t = limpio(p)
                if (t.isEmpty()) continue
                for (trozo in trozos(t)) bloque(trozo, pTexto, 8f)
            }
        }
        doc.finishPage(pagina)
        return Web.guardarBytes(ctx, nombre(titulo, "pdf"), "application/pdf", terminar(doc))
    }

    /** PDF con formato de diapositivas (horizontal, fondo oscuro): sirve para ver la presentación en cualquier teléfono. */
    fun presentacionPdf(ctx: Context, titulo: String, subtitulo: String, diapos: List<Seccion>): Web.Archivo {
        val doc = PdfDocument()
        val an = 960
        val al = 540
        val fondo = Paint().apply { color = 0xFF050B14.toInt() }
        val rojo = Paint().apply { color = 0xFFFF2B3A.toInt() }
        val pPortada = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 48f; typeface = Typeface.DEFAULT_BOLD; color = 0xFF18B6FF.toInt() }
        val pSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; color = 0xFF9FE8FF.toInt() }
        val pTitulo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 36f; typeface = Typeface.DEFAULT_BOLD; color = 0xFF18B6FF.toInt() }
        val pPunto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = 0xFFE6F6FF.toInt() }

        fun texto(c: Canvas, txt: String, paint: TextPaint, x: Float, y: Float, ancho: Int): Float {
            val layout = StaticLayout.Builder.obtain(txt, 0, txt.length, paint, ancho).setLineSpacing(2f, 1f).build()
            c.save()
            c.translate(x, y)
            layout.draw(c)
            c.restore()
            return layout.height.toFloat()
        }

        fun pagina(numero: Int, dibujar: (Canvas) -> Unit) {
            val p = doc.startPage(PdfDocument.PageInfo.Builder(an, al, numero).create())
            p.canvas.drawRect(0f, 0f, an.toFloat(), al.toFloat(), fondo)
            dibujar(p.canvas)
            doc.finishPage(p)
        }

        pagina(1) { c ->
            c.drawRect(60f, 160f, 68f, 300f, rojo)
            texto(c, limpio(titulo), pPortada, 90f, 160f, 800)
            texto(c, limpio(subtitulo), pSub, 90f, 330f, 800)
        }
        diapos.forEachIndexed { i, d ->
            pagina(i + 2) { c ->
                texto(c, limpio(d.titulo).take(80), pTitulo, 50f, 40f, 860)
                c.drawRect(50f, 110f, 200f, 114f, rojo)
                var y = 140f
                for (p in d.puntos.take(5)) {
                    val t = limpio(p).take(150)
                    if (t.isEmpty() || y > 470f) continue
                    y += texto(c, "•  $t", pPunto, 60f, y, 840) + 14f
                }
            }
        }
        return Web.guardarBytes(ctx, nombre(titulo, "pdf"), "application/pdf", terminar(doc))
    }
}
