package tenderhub.common

data class PageRequest(val page: Int = 0, val size: Int = DEFAULT_SIZE) {
    init {
        validate(page >= 0, "page", "INVALID_PAGE") { "Номер страницы не может быть отрицательным" }
        validate(size in 1..MAX_SIZE, "size", "INVALID_PAGE_SIZE") { "Размер страницы должен быть от 1 до $MAX_SIZE" }
    }

    val offset: Long get() = page.toLong() * size

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100
    }
}

data class Page<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
) {
    val totalPages: Int get() = if (total == 0L) 0 else ((total + size - 1) / size).toInt()

    fun <R> map(transform: (T) -> R): Page<R> = Page(items.map(transform), page, size, total)

    companion object {
        fun <T> of(items: List<T>, request: PageRequest, total: Long): Page<T> =
            Page(items, request.page, request.size, total)
    }
}
