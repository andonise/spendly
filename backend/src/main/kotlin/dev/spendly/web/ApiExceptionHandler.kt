package dev.spendly.web

import dev.spendly.common.ApiException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/** RFC 7807 ProblemDetail for every error, with a machine-readable `code` (and `errors` for validation). */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException): ResponseEntity<ProblemDetail> {
        val pd = ProblemDetail.forStatusAndDetail(ex.status, ex.message ?: ex.status.reasonPhrase)
        pd.title = ex.status.reasonPhrase
        pd.setProperty("code", ex.code)
        if (ex.fieldErrors.isNotEmpty()) pd.setProperty("errors", ex.fieldErrors)
        return ResponseEntity.status(ex.status).body(pd)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ProblemDetail> {
        log.error("Unhandled error: {}", ex.javaClass.name, ex)
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error")
        pd.title = HttpStatus.INTERNAL_SERVER_ERROR.reasonPhrase
        pd.setProperty("code", "INTERNAL_ERROR")
        return ResponseEntity.internalServerError().body(pd)
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed")
        pd.title = HttpStatus.BAD_REQUEST.reasonPhrase
        pd.setProperty("code", "VALIDATION_ERROR")
        pd.setProperty("errors", ex.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to (it.defaultMessage ?: "invalid")) })
        return ResponseEntity.badRequest().body(pd)
    }

    override fun handleMaxUploadSizeExceededException(
        ex: MaxUploadSizeExceededException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "The file is too large")
        pd.title = "Content Too Large"
        pd.setProperty("code", "FILE_TOO_LARGE")
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(pd)
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val response = super.handleExceptionInternal(ex, body, headers, statusCode, request)
        (response?.body as? ProblemDetail)?.let { pd ->
            if (pd.properties?.containsKey("code") != true) pd.setProperty("code", if (statusCode.is4xxClientError) "BAD_REQUEST" else "ERROR")
        }
        return response
    }
}
