package tenderhub.common

sealed class DomainException(val code: String, message: String) : RuntimeException(message)

class ValidationException(code: String, message: String, val field: String? = null) :
    DomainException(code, message)

class NotFoundException(code: String, message: String) : DomainException(code, message)

class ForbiddenException(code: String, message: String) : DomainException(code, message)

class ConflictException(code: String, message: String) : DomainException(code, message)

class BusinessRuleException(code: String, message: String) : DomainException(code, message)

fun validate(condition: Boolean, field: String, code: String, message: () -> String) {
    if (!condition) throw ValidationException(code, message(), field)
}

fun businessRule(condition: Boolean, code: String, message: () -> String) {
    if (!condition) throw BusinessRuleException(code, message())
}
