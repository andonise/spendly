package dev.spendly.common

import org.springframework.http.HttpStatus

/** Errors that map to an RFC 7807 ProblemDetail with a machine-readable `code`. Messages never contain PII. */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
    val fieldErrors: List<FieldViolation> = emptyList(),
) : RuntimeException(message)

data class FieldViolation(val field: String, val message: String)

class NotFoundException(what: String) : ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "$what not found")

open class ConflictException(code: String, message: String) : ApiException(HttpStatus.CONFLICT, code, message)

class BadRequestException(code: String, message: String, fieldErrors: List<FieldViolation> = emptyList()) :
    ApiException(HttpStatus.BAD_REQUEST, code, message, fieldErrors)

class UnsupportedFileTypeException(message: String) :
    ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE", message)

class FileTooLargeException(message: String) :
    ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", message)
