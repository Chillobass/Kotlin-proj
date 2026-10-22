package tenderhub.app.web

import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import tenderhub.common.BusinessRuleException
import tenderhub.common.ConflictException
import tenderhub.common.DomainException
import tenderhub.common.ForbiddenException
import tenderhub.common.NotFoundException
import tenderhub.common.ValidationException

/**
 * Single place that maps errors to HTTP. Business code throws [DomainException]s with stable codes;
 * here they become RFC 7807 problem details:
 *
 * ValidationException -> 400, ForbiddenException -> 403, NotFoundException -> 404,
 * ConflictException -> 409, BusinessRuleException -> 422.
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DomainException::class)
    fun handleDomain(ex: DomainException): ResponseEntity<ProblemDetail> {
        val status = when (ex) {
            is ValidationException -> HttpStatus.BAD_REQUEST
            is NotFoundException -> HttpStatus.NOT_FOUND
            is ForbiddenException -> HttpStatus.FORBIDDEN
            is ConflictException -> HttpStatus.CONFLICT
            is BusinessRuleException -> HttpStatus.UNPROCESSABLE_ENTITY
        }
        log.debug("Business error {} ({}): {}", ex.code, status.value(), ex.message)
        val problem = problem(status, ex.code, ex.message ?: status.reasonPhrase)
        if (ex is ValidationException && ex.field != null) {
            problem.setProperty("errors", listOf(FieldError(ex.field!!, ex.message ?: "")))
        }
        return ResponseEntity.status(status).body(problem)
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleIntegrity(ex: DataIntegrityViolationException): ResponseEntity<ProblemDetail> {
        log.warn("Data integrity violation: {}", ex.mostSpecificCause.message)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(problem(HttpStatus.CONFLICT, "DATA_CONFLICT", "Операция конфликтует с текущим состоянием данных"))
    }

    @ExceptionHandler(PessimisticLockingFailureException::class)
    fun handleLocking(ex: PessimisticLockingFailureException): ResponseEntity<ProblemDetail> {
        log.warn("Locking failure: {}", ex.message)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "Параллельное изменение данных, повторите запрос"))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ProblemDetail> {
        log.error("Unhandled error", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка сервера"))
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errors = ex.bindingResult.fieldErrors.map { FieldError(it.field, it.defaultMessage ?: "invalid") }
        val problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Некорректные данные запроса")
        problem.setProperty("errors", errors)
        return ResponseEntity.badRequest().body(problem)
    }

    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Тело запроса не удалось разобрать")
        val field = (ex.cause as? MismatchedInputException)?.path
            ?.joinToString(".") { it.fieldName ?: "[${it.index}]" }
            ?.takeIf { it.isNotEmpty() }
        if (field != null) {
            val message = if (ex.cause is InvalidFormatException) "Неверный формат значения" else "Поле отсутствует или имеет неверный тип"
            problem.setProperty("errors", listOf(FieldError(field, message)))
        }
        return ResponseEntity.badRequest().body(problem)
    }

    private fun problem(status: HttpStatus, code: String, detail: String) =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = status.reasonPhrase
            setProperty("code", code)
        }

    data class FieldError(val field: String, val message: String)
}
