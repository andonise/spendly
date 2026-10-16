package dev.spendly.shared.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Takes `X-Correlation-Id` from the request (when it matches the envelope's correlationId pattern) or
 * generates one, exposes it to handlers and logs (MDC), and echoes it in the response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val incoming = request.getHeader(HEADER)?.trim()
        val id = if (incoming != null && VALID.matches(incoming)) incoming else "req-${UUID.randomUUID()}"
        request.setAttribute(ATTRIBUTE, id)
        response.setHeader(HEADER, id)
        MDC.put(MDC_KEY, id)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    override fun shouldNotFilterAsyncDispatch() = false

    companion object {
        const val HEADER = "X-Correlation-Id"
        const val ATTRIBUTE = "spendly.correlationId"
        const val MDC_KEY = "correlationId"
        val VALID = Regex("^[A-Za-z0-9._:-]{1,100}$")
    }
}
