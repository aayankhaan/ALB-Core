package com.aayan.albcore.gui

/** Page information passed to page items and their click actions. */
class PageContext(val currentPage: Int, val totalPages: Int, private val gui: GuiBuilder) {
    val hasNextPage: Boolean get() = currentPage < totalPages - 1
    val hasPreviousPage: Boolean get() = currentPage > 0

    fun nextPage() = gui.goToPage(currentPage + 1)
    fun previousPage() = gui.goToPage(currentPage - 1)
    fun goTo(page: Int) = gui.goToPage(page)

    /** Re-renders the GUI (e.g. after a purchase changed a price). */
    fun refresh() {
        gui.refresh()
    }
}
