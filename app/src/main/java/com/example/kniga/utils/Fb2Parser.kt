package com.example.kniga.utils

import android.content.Context
import java.io.File

object BookParser {
    
    data class BookContent(
        val title: String,
        val author: String,
        val annotation: String,
        val chapters: List<Fb2Parser.Chapter>
    )
    
    fun parseBook(context: Context, filePath: String, format: String): BookContent? {
        return try {
            val file = File(filePath)
            if (!file.exists()) return null
            
            when (format.lowercase()) {
                "fb2" -> {
                    val parser = Fb2Parser()
                    val result = parser.parse(file)
                    BookContent(
                        title = result.title,
                        author = result.author,
                        annotation = result.annotation,
                        chapters = result.chapters
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
