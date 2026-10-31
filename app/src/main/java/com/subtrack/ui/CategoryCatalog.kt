package com.subtrack.ui

import androidx.compose.ui.graphics.Color
import com.subtrack.R

data class CategoryUi(val id: String, val name: String, val iconRes: Int, val color: Color)

object Categories {
    val all: List<CategoryUi> = listOf(
        CategoryUi("video", "影音娱乐", R.drawable.ic_play, Color(0xFF007AFF)),
        CategoryUi("music", "音乐音频", R.drawable.ic_music, Color(0xFF32ADE6)),
        CategoryUi("cloud", "云存储", R.drawable.ic_cloud, Color(0xFF30B0C7)),
        CategoryUi("ai", "AI 服务", R.drawable.ic_sparkle, Color(0xFFFF9500)),
        CategoryUi("server", "软件与服务器", R.drawable.ic_terminal, Color(0xFF34C759)),
        CategoryUi("telecom", "通信宽带", R.drawable.ic_wifi, Color(0xFF5AC8FA)),
        CategoryUi("study", "学习阅读", R.drawable.ic_book, Color(0xFFA2845E)),
        CategoryUi("health", "健康生活", R.drawable.ic_heart, Color(0xFFFF2D55)),
        CategoryUi("other", "其他", R.drawable.ic_grid, Color(0xFF8E8E93)),
    )

    fun of(id: String): CategoryUi = all.firstOrNull { it.id == id } ?: all.last()
}
