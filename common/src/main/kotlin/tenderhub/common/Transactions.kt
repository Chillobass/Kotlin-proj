package tenderhub.common

interface TransactionRunner {
    fun <T> inTransaction(block: () -> T): T

    fun <T> readOnly(block: () -> T): T
}
