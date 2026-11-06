package cn.edu.lcu.libseat.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 验证码识别（设备端离线，ML Kit）。
 *
 * 两种验证码用不同策略：
 *
 * 1. **登录验证码**（4 位小写字母/数字）：只取拉丁模型会更准，但单一预处理容易失败，
 *    因此对同一张图做多组预处理（放大、灰度增强、二值化、通道分离、锐化、中值滤波、反相），
 *    每组都识别一次，收集所有「恰好 4 位」的候选，再按整串投票、位置投票定结果。
 *
 * 2. **点选验证码**（提示 N 个字，在照片里依次点出）：先把提示图读成 N 个字符，
 *    再对背景图做多组预处理取出所有「单字框」，合并去重后按提示顺序匹配；
 *    匹配有歧义时宁可放弃本次（换新验证码），也不乱点。
 *
 * 识别过程会通过 [onDiagnostic] 输出诊断信息，任务日志里能看到「读到了什么、卡在哪一步」。
 */
class CaptchaSolver {

    /** 诊断信息回调，用于把识别细节写进执行日志。 */
    var onDiagnostic: ((String) -> Unit)? = null

    private fun diag(message: String) {
        onDiagnostic?.invoke(message)
    }

    private data class Variant(val name: String, val bitmap: Bitmap, val owned: Boolean)

    /** 背景图中的一个字符框，坐标已换算回原图。 */
    private data class Glyph(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val priority: Int,
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val width: Int get() = (right - left).coerceAtLeast(1)
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    // ------------------------------------------------------------ 登录验证码

    /** 登录验证码的识别结果；[confident] 为 false 表示只有单轮结果，属于猜测。 */
    data class LoginAnswer(val text: String, val confident: Boolean)

    /** 识别 4 位登录验证码，识别不到返回 null。 */
    suspend fun solveLogin(source: Bitmap): LoginAnswer? {
        val variants = buildVariants(source, LOGIN_PREPROCESSING)
        val candidates = mutableListOf<String>()
        try {
            for (variant in variants) {
                for (latin in listOf(true, false)) {
                    val model = if (latin) "" else "/中文"
                    val result = recognize(variant.bitmap, latin = latin) ?: continue
                    val raw = result.text.replace('\n', ' ').trim()
                    if (raw.isNotEmpty()) {
                        diag("登录码[${variant.name}$model] 原始=\"${raw.take(12)}\"")
                    }
                    val found = loginCandidates(result)
                    if (found.isEmpty()) continue
                    candidates += found
                    diag("登录码[${variant.name}$model] 候选=${found.joinToString("/")}")
                    // 两张一致即可定稿，省掉后面的识别时间。
                    if (hasAgreement(candidates)) {
                        val text = voteLogin(candidates) ?: return null
                        diag("登录码定稿（多轮一致）：$text")
                        return LoginAnswer(text, confident = true)
                    }
                }
                if (candidates.size >= MAX_LOGIN_CANDIDATES) break
            }

            // 文本区裁剪再识别：整体识别漏字时，放大裁剪往往能补回。
            cropTextRegion(source)?.let { crop ->
                try {
                    for (latin in listOf(true, false)) {
                        val result = recognize(crop, latin = latin) ?: continue
                        val found = loginCandidates(result)
                        if (found.isEmpty()) continue
                        candidates += found
                        diag("登录码[文本区裁剪${if (latin) "" else "/中文"}] 候选=${found.joinToString("/")}")
                        if (hasAgreement(candidates)) {
                            val text = voteLogin(candidates) ?: return null
                            diag("登录码定稿（裁剪后一致）：$text")
                            return LoginAnswer(text, confident = true)
                        }
                    }
                } finally {
                    if (!crop.isRecycled) crop.recycle()
                }
            }
        } finally {
            recycle(variants)
        }

        if (candidates.isNotEmpty()) {
            val text = voteLogin(candidates) ?: return null
            diag("登录码只有单轮结果：$text（低置信度）")
            return LoginAnswer(text, confident = false)
        }

        // 列切分兜底：4 位验证码通常等距排列，逐列识别能避开整体识别漏字。
        solveLoginByColumns(source)?.let { text ->
            diag("登录码按列切分得到：$text（低置信度）")
            return LoginAnswer(text, confident = false)
        }
        diag("登录码：所有策略都没有结果")
        return null
    }

    /** 用 OCR 得到的文字区域把图片裁出来，去掉边缘噪点和干扰线。 */
    private suspend fun cropTextRegion(source: Bitmap): Bitmap? {
        val scaled = scale(source, 3f)
        val result = try {
            recognize(scaled, latin = true)
        } catch (e: Exception) {
            null
        }
        if (result == null) {
            scaled.recycle()
            return null
        }
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = 0
        var bottom = 0
        var found = false
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    if (element.text.isBlank()) continue
                    left = minOf(left, box.left)
                    top = minOf(top, box.top)
                    right = maxOf(right, box.right)
                    bottom = maxOf(bottom, box.bottom)
                    found = true
                }
            }
        }
        if (!found) {
            scaled.recycle()
            return null
        }
        val padX = 8
        val padY = 8
        val x1 = (left - padX).coerceAtLeast(0)
        val y1 = (top - padY).coerceAtLeast(0)
        val x2 = (right + padX).coerceAtMost(scaled.width)
        val y2 = (bottom + padY).coerceAtMost(scaled.height)
        if (x2 - x1 < 12 || y2 - y1 < 12) {
            scaled.recycle()
            return null
        }
        val crop = Bitmap.createBitmap(scaled, x1, y1, x2 - x1, y2 - y1)
        scaled.recycle()
        return crop
    }

    /** 等分成 4 列逐个识别；适合字符等距、但整体识别总漏一位的验证码。 */
    private suspend fun solveLoginByColumns(source: Bitmap): String? {
        for (step in COLUMN_PREPROCESSING) {
            val prepared = runCatching { apply(step, source) }.getOrNull() ?: continue
            try {
                val builder = StringBuilder()
                var ok = true
                for (index in 0 until 4) {
                    val width = prepared.width / 4
                    if (width < 4) {
                        ok = false
                        break
                    }
                    val left = (prepared.width * index / 4).coerceAtMost(prepared.width - width)
                    val slice = Bitmap.createBitmap(prepared, left, 0, width, prepared.height)
                    val text = try {
                        recognize(slice, latin = true)?.text
                    } finally {
                        slice.recycle()
                    }
                    val char = text?.lowercase()?.firstOrNull { it in 'a'..'z' || it in '0'..'9' }
                    if (char == null) {
                        ok = false
                        break
                    }
                    builder.append(char)
                }
                if (ok && builder.length == 4 && LOGIN_CODE_REGEX.matches(builder.toString())) {
                    return builder.toString()
                }
            } finally {
                if (prepared !== source && !prepared.isRecycled) prepared.recycle()
            }
        }
        return null
    }

    private fun loginCandidates(result: Text): List<String> {
        val found = mutableListOf<String>()

        fun add(raw: String) {
            // 站点验证码固定为 4 位、且只含小写字母与数字，不符合的读数直接丢弃。
            val cleaned = raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
            if (LOGIN_CODE_REGEX.matches(cleaned) && cleaned !in found) found += cleaned
        }

        add(result.text)
        add(elementsOf(result).joinToString(""))
        add(linesOf(result).joinToString(""))
        return found
    }

    private fun hasAgreement(candidates: List<String>): Boolean =
        candidates.groupingBy { it }.eachCount().any { it.value >= 2 }

    /** 整串投票优先，没有重复时按位置投票。 */
    private fun voteLogin(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        val counts = candidates.groupingBy { it }.eachCount()
        val best = counts.maxByOrNull { it.value } ?: return null
        if (best.value >= 2 || candidates.size == 1) return best.key

        val builder = StringBuilder()
        for (index in 0 until 4) {
            val perPosition = candidates.map { it[index] }.groupingBy { it }.eachCount()
            builder.append(perPosition.maxByOrNull { it.value }?.key ?: return null)
        }
        return builder.toString()
    }

    // ------------------------------------------------------------ 点选验证码

    /**
     * 识别点选验证码，返回按提示顺序排列的点击坐标。
     * 坐标以背景图显示宽度 330 像素为基准，与网页一致。
     */
    suspend fun solveClick(background: Bitmap, word: Bitmap, count: Int): List<TapPoint>? {
        if (count <= 0) return null

        val prompts = readPrompts(word, count)
        if (prompts == null) {
            diag("点选码：提示字识别失败（需要 $count 个字）")
            return null
        }
        diag("点选码提示字：${prompts.joinToString("")}")

        val glyphs = readGlyphs(background)
        if (glyphs.isEmpty()) {
            diag("点选码：背景图里没有识别到文字")
            return null
        }
        diag("点选码：背景图检测到 ${glyphs.size} 个字符（${glyphs.joinToString("") { it.text.take(1) }}）")

        val matched = matchGlyphs(prompts, glyphs)
        if (matched == null) {
            diag("点选码：提示字与背景字符无法一一对应，放弃本次")
            return null
        }

        val scale = 330f / background.width.coerceAtLeast(1)
        return matched.map { (glyph, charIndex) ->
            val charWidth = glyph.width.toFloat() / glyph.text.length.coerceAtLeast(1)
            val centerX = glyph.left + charWidth * (charIndex + 0.5f)
            TapPoint((centerX * scale).roundToInt(), (glyph.centerY * scale).roundToInt())
        }
    }

    private suspend fun readPrompts(word: Bitmap, count: Int): List<Char>? {
        val variants = buildVariants(word, CLICK_PREPROCESSING)
        var loose: List<Char>? = null
        try {
            for (variant in variants) {
                val result = recognize(variant.bitmap, latin = false) ?: continue
                val byElement = elementsOf(result).joinToString("").filter { it.isReadable() }
                if (byElement.length == count) {
                    diag("提示字[${variant.name}] → $byElement")
                    return byElement.toList()
                }
                val whole = result.text.filter { it.isReadable() }
                if (whole.length == count) {
                    diag("提示字[${variant.name}] → $whole")
                    return whole.toList()
                }
                if (loose == null && whole.length > count) loose = whole.take(count).toList()
            }
        } finally {
            recycle(variants)
        }
        return loose
    }

    private suspend fun readGlyphs(background: Bitmap): List<Glyph> {
        val variants = buildVariants(background, CLICK_PREPROCESSING)
        val raw = mutableListOf<Glyph>()
        try {
            variants.forEachIndexed { priority, variant ->
                val result = recognize(variant.bitmap, latin = false) ?: return@forEachIndexed
                val scaleX = background.width.toFloat() / variant.bitmap.width.coerceAtLeast(1)
                val scaleY = background.height.toFloat() / variant.bitmap.height.coerceAtLeast(1)
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        if (line.elements.isNotEmpty()) {
                            for (element in line.elements) {
                                val box = element.boundingBox ?: continue
                                val text = element.text.trim()
                                if (text.isEmpty()) continue
                                raw += toGlyph(text, box, scaleX, scaleY, priority)
                            }
                        } else {
                            // 中文单字常被整行识别，行级兜底只在整行只有 1 个字时使用。
                            val text = line.text.trim()
                            val box = line.boundingBox
                            if (text.length == 1 && box != null) {
                                raw += toGlyph(text, box, scaleX, scaleY, priority)
                            }
                        }
                    }
                }
            }
        } finally {
            recycle(variants)
        }
        return mergeGlyphs(raw, background)
    }

    private fun toGlyph(
        text: String,
        box: Rect,
        scaleX: Float,
        scaleY: Float,
        priority: Int,
    ) = Glyph(
        text = text,
        left = (box.left * scaleX).roundToInt(),
        top = (box.top * scaleY).roundToInt(),
        right = (box.right * scaleX).roundToInt(),
        bottom = (box.bottom * scaleY).roundToInt(),
        priority = priority,
    )

    /** 过滤掉整图级的大框、极小的噪点框，并合并多轮预处理的重复结果。 */
    private fun mergeGlyphs(glyphs: List<Glyph>, background: Bitmap): List<Glyph> {
        val maxWidth = (background.width * 0.6f).roundToInt()
        val maxHeight = (background.height * 0.8f).roundToInt()
        val filtered = glyphs.filter {
            it.width >= 6 && it.height >= 6 && it.width <= maxWidth && it.height <= maxHeight
        }

        val merged = mutableListOf<Glyph>()
        for (glyph in filtered.sortedBy { it.priority }) {
            val duplicated = merged.any { existing ->
                existing.text.first() == glyph.text.first() &&
                    abs(existing.centerX - glyph.centerX) < 10f &&
                    abs(existing.centerY - glyph.centerY) < 10f
            }
            if (duplicated) continue
            // 同一位置被读成不同字的，只保留优先级最高（最先命中）的一个。
            val sameSpot = merged.indexOfFirst { existing ->
                abs(existing.centerX - glyph.centerX) < 12f &&
                    abs(existing.centerY - glyph.centerY) < 12f
            }
            if (sameSpot < 0) merged += glyph
        }
        return merged
    }

    private fun matchGlyphs(prompts: List<Char>, glyphs: List<Glyph>): List<Pair<Glyph, Int>>? {
        val used = mutableSetOf<Int>()
        val result = mutableListOf<Pair<Glyph, Int>>()

        for (char in prompts) {
            val exact = glyphs.indices.filter { it !in used && glyphs[it].text.contains(char) }
            when {
                exact.size == 1 -> {
                    val index = exact[0]
                    used += index
                    result += glyphs[index] to glyphs[index].text.indexOf(char)
                }

                exact.size > 1 -> {
                    val sorted = exact.sortedWith(
                        compareBy({ glyphs[it].priority }, { glyphs[it].width })
                    )
                    // 同一轮预处理里出现多个命中，属于真歧义，换一张验证码更可靠。
                    if (glyphs[sorted[0]].priority == glyphs[sorted[1]].priority) return null
                    val index = sorted[0]
                    used += index
                    result += glyphs[index] to glyphs[index].text.indexOf(char)
                }

                else -> {
                    val scores = glyphs.indices
                        .filter { it !in used }
                        .map { it to similarity(char, glyphs[it].text.first()) }
                        .filter { it.second >= SIMILARITY_THRESHOLD }
                        .sortedByDescending { it.second }
                    val clearWinner = scores.isNotEmpty() &&
                        (scores.size == 1 || scores[0].second > scores[1].second)
                    if (!clearWinner) return null
                    val index = scores[0].first
                    used += index
                    result += glyphs[index] to 0
                }
            }
        }

        // 两个提示字不能落到同一个点上。
        for (i in result.indices) {
            for (j in i + 1 until result.size) {
                val a = result[i].first
                val b = result[j].first
                if (abs(a.centerX - b.centerX) < 4f && abs(a.centerY - b.centerY) < 4f) return null
            }
        }
        return result
    }

    /** 形近字兜底：只在唯一候选且明显领先时才会被采用。 */
    private fun similarity(expected: Char, actual: Char): Float {
        if (expected == actual) return 1f
        if (expected in CONFUSIONS[actual].orEmpty()) return 0.9f
        if (actual in CONFUSIONS[expected].orEmpty()) return 0.85f
        return 0f
    }

    // ------------------------------------------------------------ 预处理

    private enum class Preprocess(val label: String) {
        SCALE2("放大2倍"),
        SCALE3("放大3倍"),
        SCALE4("放大4倍"),
        GRAY_CONTRAST("灰度增强"),
        THRESHOLD("二值化150"),
        THRESHOLD_INV("二值化反相"),
        OTSU("自适应阈值"),
        CHANNEL_RED("红色通道"),
        CHANNEL_BLUE("蓝色通道"),
        CHANNEL_SAT("饱和度通道"),
        SHARPEN("锐化"),
        MEDIAN("中值滤波"),
        INVERT("反相"),
    }

    private fun buildVariants(source: Bitmap, steps: List<Preprocess>): List<Variant> {
        val variants = mutableListOf(Variant("原图", source, owned = false))
        for (step in steps) {
            val bitmap = runCatching { apply(step, source) }.getOrNull() ?: continue
            variants += Variant(step.label, bitmap, owned = true)
        }
        return variants
    }

    private fun recycle(variants: List<Variant>) {
        for (variant in variants) {
            if (variant.owned && !variant.bitmap.isRecycled) variant.bitmap.recycle()
        }
    }

    private fun apply(step: Preprocess, source: Bitmap): Bitmap = when (step) {
        Preprocess.SCALE2 -> scale(source, 2f)
        Preprocess.SCALE3 -> scale(source, 3f)
        Preprocess.SCALE4 -> scale(source, 4f)
        Preprocess.GRAY_CONTRAST -> grayscaleContrast(source, 2.0f)
        Preprocess.THRESHOLD -> threshold(source, 150, invert = false)
        Preprocess.THRESHOLD_INV -> threshold(source, 150, invert = true)
        Preprocess.OTSU -> threshold(source, otsuLevel(source), invert = false)
        Preprocess.CHANNEL_RED -> channel(source, 'r')
        Preprocess.CHANNEL_BLUE -> channel(source, 'b')
        Preprocess.CHANNEL_SAT -> channel(source, 's')
        Preprocess.SHARPEN -> sharpen(source)
        Preprocess.MEDIAN -> median(source)
        Preprocess.INVERT -> invert(source)
    }

    /** Otsu 自动阈值：比固定 150 更能适应不同底色的验证码。 */
    private fun otsuLevel(source: Bitmap): Int {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val histogram = IntArray(256)
        for (pixel in pixels) {
            val gray = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            histogram[gray.coerceIn(0, 255)]++
        }
        val total = pixels.size
        var sum = 0L
        for (level in 0..255) sum += level.toLong() * histogram[level]
        var sumBackground = 0L
        var weightBackground = 0
        var bestVariance = -1.0
        var bestLevel = 128
        for (level in 0..255) {
            weightBackground += histogram[level]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += level.toLong() * histogram[level]
            val meanBackground = sumBackground.toDouble() / weightBackground
            val meanForeground = (sum - sumBackground).toDouble() / weightForeground
            val variance = weightBackground.toDouble() * weightForeground *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (variance > bestVariance) {
                bestVariance = variance
                bestLevel = level
            }
        }
        return bestLevel.coerceIn(60, 200)
    }

    private fun scale(source: Bitmap, factor: Float): Bitmap = Bitmap.createScaledBitmap(
        source,
        (source.width * factor).roundToInt().coerceAtLeast(1),
        (source.height * factor).roundToInt().coerceAtLeast(1),
        true,
    )

    private fun grayscaleContrast(source: Bitmap, contrast: Float): Bitmap {
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            source,
            0f,
            0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) },
        )
        return output
    }

    private fun invert(source: Bitmap): Bitmap {
        val matrix = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            source,
            0f,
            0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) },
        )
        return output
    }

    private fun threshold(source: Bitmap, level: Int, invert: Boolean): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val gray = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            val dark = if (invert) gray >= level else gray < level
            pixels[index] = if (dark) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /** 按通道分离：彩色文字压在照片上时，单通道比灰度更能把文字分出来。 */
    private fun channel(source: Bitmap, which: Char): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val hsv = FloatArray(3)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val value = when (which) {
                'r' -> Color.red(pixel)
                'g' -> Color.green(pixel)
                'b' -> Color.blue(pixel)
                else -> {
                    Color.colorToHSV(pixel, hsv)
                    (hsv[1] * 255).toInt()
                }
            }.coerceIn(0, 255)
            pixels[index] = Color.rgb(value, value, value)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun sharpen(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
        val output = IntArray(width * height)
        val kernel = intArrayOf(0, -1, 0, -1, 5, -1, 0, -1, 0)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var red = 0
                var green = 0
                var blue = 0
                var index = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val px = (x + dx).coerceIn(0, width - 1)
                        val py = (y + dy).coerceIn(0, height - 1)
                        val pixel = input[py * width + px]
                        val weight = kernel[index++]
                        red += Color.red(pixel) * weight
                        green += Color.green(pixel) * weight
                        blue += Color.blue(pixel) * weight
                    }
                }
                output[y * width + x] = Color.rgb(
                    red.coerceIn(0, 255),
                    green.coerceIn(0, 255),
                    blue.coerceIn(0, 255),
                )
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(output, 0, width, 0, 0, width, height)
        }
    }

    /** 3×3 中值滤波：去掉验证码上的噪点和干扰线。 */
    private fun median(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
        val output = IntArray(width * height)
        val window = IntArray(9)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var index = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val px = (x + dx).coerceIn(0, width - 1)
                        val py = (y + dy).coerceIn(0, height - 1)
                        val pixel = input[py * width + px]
                        window[index++] =
                            (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                    }
                }
                window.sort()
                val value = window[4]
                output[y * width + x] = Color.rgb(value, value, value)
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(output, 0, width, 0, 0, width, height)
        }
    }

    // ------------------------------------------------------------ 工具

    private suspend fun recognize(bitmap: Bitmap, latin: Boolean): Text? =
        suspendCancellableCoroutine { cont ->
            val recognizer = if (latin) latinClient else chineseClient
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }

    private fun elementsOf(result: Text): List<String> = buildList {
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val text = element.text.trim()
                    if (text.isNotEmpty()) add(text)
                }
            }
        }
    }

    private fun linesOf(result: Text): List<String> = buildList {
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim()
                if (text.isNotEmpty()) add(text)
            }
        }
    }

    private fun Char.isReadable(): Boolean = isLetterOrDigit() || this in '\u4e00'..'\u9fff'

    companion object {
        private const val SIMILARITY_THRESHOLD = 0.8f

        /** 站点登录验证码的合法格式：恰好 4 位，只含小写字母与数字。 */
        val LOGIN_CODE_REGEX = Regex("^[a-z0-9]{4}$")

        /** 自动识别与手动输入共用同一套校验。 */
        fun isValidLoginCode(value: String): Boolean = LOGIN_CODE_REGEX.matches(value)

        /**
         * ML Kit 客户端按进程复用：每个客户端都要加载一次模型，
         * 每次任务都 new 一个会明显拖慢首次识别。
         */
        private val chineseClient by lazy {
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        }
        private val latinClient by lazy {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        private val LOGIN_PREPROCESSING = listOf(
            Preprocess.SCALE3,
            Preprocess.SCALE4,
            Preprocess.GRAY_CONTRAST,
            Preprocess.THRESHOLD,
            Preprocess.OTSU,
            Preprocess.CHANNEL_RED,
            Preprocess.MEDIAN,
            Preprocess.INVERT,
        )

        /** 候选总量上限：避免为一张读不出来的图跑满全部预处理。 */
        private const val MAX_LOGIN_CANDIDATES = 12

        /** 列切分兜底用的预处理：二值化与放大后的对比最明显。 */
        private val COLUMN_PREPROCESSING = listOf(
            Preprocess.THRESHOLD,
            Preprocess.OTSU,
            Preprocess.SCALE3,
        )

        private val CLICK_PREPROCESSING = listOf(
            Preprocess.SCALE2,
            Preprocess.GRAY_CONTRAST,
            Preprocess.CHANNEL_SAT,
            Preprocess.SHARPEN,
        )

        /** 形近字表：只用于「精确匹配失败」时的兜底，命中还需唯一且领先。 */
        private val CONFUSIONS: Map<Char, String> = mapOf(
            // 汉字
            '未' to "末", '末' to "未",
            '己' to "已巳", '已' to "己巳", '巳' to "己已",
            '日' to "曰目", '曰' to "日", '目' to "日自", '自' to "目白", '白' to "自百",
            '人' to "入八", '入' to "人八", '大' to "太犬", '太' to "大犬", '天' to "夭无",
            '干' to "于千", '千' to "干", '于' to "干", '土' to "士", '士' to "土",
            '王' to "玉主", '玉' to "王", '主' to "王", '木' to "本术", '本' to "木",
            '田' to "由甲申", '由' to "田甲申", '甲' to "由田申", '申' to "由田甲",
            '刀' to "力", '力' to "刀", '石' to "右", '右' to "石",
            '早' to "旱", '旱' to "早", '候' to "侯", '侯' to "候",
            '耍' to "要", '要' to "耍", '免' to "兔", '兔' to "免", '乌' to "鸟", '鸟' to "乌",
            '儿' to "几", '几' to "儿", '户' to "尸", '尸' to "户",
            // 字母 / 数字
            '0' to "oO", 'o' to "0O", 'O' to "0",
            '1' to "lIi", 'l' to "1I", 'i' to "1l", 'I' to "1l",
            '2' to "zZ", 'z' to "2", 'Z' to "2",
            '5' to "sS", 's' to "5", 'S' to "5",
            '6' to "bB", 'b' to "6", 'B' to "86",
            '8' to "B", '9' to "gq", 'g' to "9q", 'q' to "9g",
            'c' to "e", 'e' to "c", 'u' to "v", 'v' to "u",
        )

        /** 解析服务器返回的 data:image/xxx;base64,... 或纯 base64 图片。 */
        fun decodeDataUrl(value: String): Bitmap? {
            if (value.isBlank()) return null
            val raw = if (value.startsWith("data:")) value.substringAfter(",", "") else value
            return runCatching {
                val bytes = Base64.decode(raw, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }
}
