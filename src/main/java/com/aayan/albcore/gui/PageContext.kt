package com.aayan.albcore.gui

class PageContext(val currentPage: Int, val totalPages: Int, private val gui: GuiBuilder) {
    val hasNextPage: Boolean get() = currentPage < totalPages - 1
    val hasPreviousPage: Boolean get() = currentPage > 0

    fun nextPage() = gui.goToPage(currentPage + 1)
    fun previousPage() = gui.goToPage(currentPage - 1)
}