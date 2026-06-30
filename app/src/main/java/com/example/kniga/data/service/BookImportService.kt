package com.example.kniga.data.service

import android.content.Context
import android.net.Uri
import com.example.kniga.data.local.entity.Book
import com.example.kniga.data.local.entity.BookFormat
import com.example.kniga.data.local.entity.BookStatus
import com.example.kniga.data.repository.BookRepository
import com.example.kniga.utils.FileUtils
import com.example.kniga.utils.Fb2Parser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class BookImportService(
    private val context: Context,
    private val bookRepository: BookRepository
) {

    /**
     * Импорт книги из URI
     */
    suspend fun importBook(uri: Uri): Result<Book> = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(uri)
                ?: return@withContext Result.failure(Exception("Не удалось получить имя файла"))

            val fileExtension = fileName.substringAfterLast(".", "").lowercase()
            if (!isSupportedFormat(fileExtension)) {
                return@withContext Result.failure(
                    Exception("Неподдерживаемый формат файла. Поддерживаются: EPUB, PDF, FB2, MOBI, TXT")
                )
            }

            val booksDir = FileUtils.getBooksDirectory(context)
            if (!booksDir.exists()) {
                booksDir.mkdirs()
            }

            val destinationFile = File(booksDir, fileName)

            // Копируем файл
            context.contentResolver.openInputStream(uri)?.use { input ->
                destinationFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(
                Exception("Не удалось прочитать файл")
            )

            // Извлекаем метаданные
            val metadata = extractMetadata(destinationFile, fileExtension)

            // Определяем количество страниц
            val totalPages = if (metadata.totalPages > 0) {
                metadata.totalPages
            } else {
                // Пытаемся определить по содержимому
                estimatePages(destinationFile, fileExtension)
            }

            val book = Book(
                title = metadata.title.ifEmpty { fileName.substringBeforeLast(".") },
                author = metadata.author.ifEmpty { "Неизвестный автор" },
                isbn = null,
                publisher = metadata.publisher,
                publishedDate = metadata.publishedDate,
                description = metadata.description,
                filePath = destinationFile.absolutePath,
                format = fileExtension.uppercase(),
                coverPath = metadata.coverPath,
                fileSize = destinationFile.length(),
                totalPages = totalPages.coerceAtLeast(1),
                currentPage = 1,
                progress = 0f,
                status = BookStatus.NOT_STARTED,
                isFavorite = false,
                fileHash = FileUtils.calculateFileHash(destinationFile),
                isSynced = false,
                cloudId = null,
                addedAt = System.currentTimeMillis(),
                lastReadAt = null,
                completedAt = null,
                updatedAt = System.currentTimeMillis()
            )

            val id = bookRepository.insertBook(book)
            Result.success(book.copy(id = id))

        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * Получение имени файла из URI
     */
    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                fileName = cursor.getString(nameIndex)
            }
        }
        return fileName ?: uri.lastPathSegment
    }

    /**
     * Проверка поддерживаемого формата
     */
    private fun isSupportedFormat(extension: String): Boolean {
        return extension in listOf("epub", "pdf", "fb2", "mobi", "txt", "fb2.zip", "zip")
    }

    /**
     * Извлечение метаданных в зависимости от формата
     */
    private fun extractMetadata(file: File, format: String): BookMetadata {
        return try {
            when (format.lowercase()) {
                "pdf" -> extractPdfMetadata(file)
                "epub" -> extractEpubMetadata(file)
                "fb2", "fb2.zip" -> extractFb2Metadata(file)
                "mobi" -> extractMobiMetadata(file)
                "txt" -> extractTxtMetadata(file)
                else -> BookMetadata(
                    title = file.nameWithoutExtension,
                    author = "Неизвестный автор",
                    description = "Импортированная книга",
                    totalPages = 0
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            BookMetadata(
                title = file.nameWithoutExtension,
                author = "Неизвестный автор",
                description = "Ошибка извлечения метаданных: ${e.message}",
                totalPages = 0
            )
        }
    }

    /**
     * Извлечение метаданных из PDF
     */
    private fun extractPdfMetadata(file: File): BookMetadata {
        return try {
            PDDocument.load(file).use { document ->
                val pageCount = document.numberOfPages
                val info = document.documentInformation

                BookMetadata(
                    title = info?.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
                    author = info?.author?.takeIf { it.isNotBlank() } ?: "Неизвестный автор",
                    publisher = info?.creator?.takeIf { it.isNotBlank() },
                    description = info?.subject?.takeIf { it.isNotBlank() } ?: "PDF документ",
                    totalPages = pageCount
                )
            }
        } catch (e: Exception) {
            BookMetadata(
                title = file.nameWithoutExtension,
                author = "Неизвестный автор",
                description = "PDF файл",
                totalPages = 0
            )
        }
    }

    /**
     * Извлечение метаданных из EPUB
     */
    private fun extractEpubMetadata(file: File): BookMetadata {
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("META-INF/container.xml")
                if (entry == null) {
                    return BookMetadata(
                        title = file.nameWithoutExtension,
                        author = "Неизвестный автор",
                        description = "EPUB книга",
                        totalPages = 0
                    )
                }

                zip.getInputStream(entry).use { input ->
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(input, "UTF-8")

                    var eventType = parser.eventType
                    var rootFile = ""

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        when (eventType) {
                            XmlPullParser.START_TAG -> {
                                if (parser.name == "rootfile") {
                                    rootFile = parser.getAttributeValue(null, "full-path") ?: ""
                                }
                            }
                        }
                        eventType = parser.next()
                    }

                    if (rootFile.isNotEmpty()) {
                        val opfEntry = zip.getEntry(rootFile)
                        if (opfEntry != null) {
                            zip.getInputStream(opfEntry).use { opfInput ->
                                val opfParser = XmlPullParserFactory.newInstance().newPullParser()
                                opfParser.setInput(opfInput, "UTF-8")

                                var title = ""
                                var author = ""
                                var publisher = ""
                                var description = ""

                                var event = opfParser.eventType
                                var inMetadata = false

                                while (event != XmlPullParser.END_DOCUMENT) {
                                    when (event) {
                                        XmlPullParser.START_TAG -> {
                                            when (opfParser.name) {
                                                "metadata" -> inMetadata = true
                                                "title" -> {
                                                    if (inMetadata) {
                                                        title = opfParser.nextText()
                                                    }
                                                }
                                                "creator" -> {
                                                    if (inMetadata) {
                                                        author = opfParser.nextText()
                                                    }
                                                }
                                                "publisher" -> {
                                                    if (inMetadata) {
                                                        publisher = opfParser.nextText()
                                                    }
                                                }
                                                "description" -> {
                                                    if (inMetadata) {
                                                        description = opfParser.nextText()
                                                    }
                                                }
                                            }
                                        }
                                        XmlPullParser.END_TAG -> {
                                            if (opfParser.name == "metadata") {
                                                inMetadata = false
                                            }
                                        }
                                    }
                                    event = opfParser.next()
                                }

                                return BookMetadata(
                                    title = title.ifEmpty { file.nameWithoutExtension },
                                    author = author.ifEmpty { "Неизвестный автор" },
                                    publisher = publisher,
                                    description = description,
                                    totalPages = 0
                                )
                            }
                        }
                    }
                }
            }

            BookMetadata(
                title = file.nameWithoutExtension,
                author = "Неизвестный автор",
                description = "EPUB книга",
                totalPages = 0
            )
        } catch (e: Exception) {
            e.printStackTrace()
            BookMetadata(
                title = file.nameWithoutExtension,
                author = "Неизвестный автор",
                description = "EPUB книга",
                totalPages = 0
            )
        }
    }

    /**
     * Извлечение метаданных из FB2
     */
    private fun extractFb2Metadata(file: File): BookMetadata {
        return try {
            val parser = Fb2Parser()
            val result = parser.parse(file)

            BookMetadata(
                title = result.title.ifEmpty { file.nameWithoutExtension },
                author = result.author.ifEmpty { "Неизвестный автор" },
                description = result.annotation.ifEmpty { "FB2 книга" },
                totalPages = result.chapters.size.coerceAtLeast(1)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            BookMetadata(
                title = file.nameWithoutExtension,
                author = "Неизвестный автор",
                description = "Ошибка парсинга FB2: ${e.message}",
                totalPages = 1
            )
        }
    }

    /**
     * Извлечение метаданных из MOBI
     */
    private fun extractMobiMetadata(file: File): BookMetadata {
        return BookMetadata(
            title = file.nameWithoutExtension,
            author = "Неизвестный автор",
            description = "MOBI книга",
            totalPages = 0
        )
    }

    /**
     * Извлечение метаданных из TXT
     */
    private fun extractTxtMetadata(file: File): BookMetadata {
        return try {
            val content = FileInputStream(file).use { input ->
                val bytes = input.readBytes()
                val text = String(bytes, Charsets.UTF_8)
                val lines = text.lines()
                val firstLine = lines.firstOrNull()?.trim() ?: ""

                BookMetadata(
                    title = firstLine.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
                    author = lines.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: "Неизвестный автор",
                    description = "Текстовый файл",
                    totalPages = lines.size / 30 // Примерное количество страниц
                )
            }
        } catch (e: Exception) {
            BookMetadata(
                title = file.nameWithoutExtension,
                author = "Неизвестный автор",
                description = "Текстовый файл",
                totalPages = 0
            )
        }
    }

    /**
     * Оценка количества страниц для форматов без точного подсчёта
     */
    private fun estimatePages(file: File, format: String): Int {
        return try {
            when (format.lowercase()) {
                "epub" -> {
                    // Для EPUB считаем количество глав
                    var chapterCount = 0
                    ZipFile(file).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.name.endsWith(".html") || entry.name.endsWith(".xhtml")) {
                                chapterCount++
                            }
                        }
                    }
                    chapterCount.coerceAtLeast(1)
                }
                "txt" -> {
                    val content = FileInputStream(file).use { input ->
                        String(input.readBytes(), Charsets.UTF_8)
                    }
                    (content.lines().size / 30).coerceAtLeast(1)
                }
                else -> 1
            }
        } catch (e: Exception) {
            1
        }
    }

    /**
     * Метаданные книги
     */
    data class BookMetadata(
        val title: String = "",
        val author: String = "Неизвестный автор",
        val publisher: String? = null,
        val publishedDate: String? = null,
        val description: String = "Книга",
        val totalPages: Int = 0,
        val coverPath: String? = null
    )
}
