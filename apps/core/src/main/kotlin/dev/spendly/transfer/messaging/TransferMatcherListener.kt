package dev.spendly.transfer.messaging

import dev.spendly.shared.messaging.correlated
import dev.spendly.shared.messaging.ConsumerNames
import dev.spendly.shared.messaging.EventCodec
import dev.spendly.shared.messaging.EventTypes
import dev.spendly.shared.messaging.Topics
import dev.spendly.shared.messaging.TransactionsChangedPayload
import dev.spendly.transfer.domain.TransferMatcherService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/** Transfer matcher: group `spendly-backend-transfer-matcher` on `spendly.transactions-changed`. */
@Component
class TransferMatcherListener(private val codec: EventCodec, private val service: TransferMatcherService) {

    @KafkaListener(id = "transferMatcher", topics = [Topics.TRANSACTIONS_CHANGED], groupId = ConsumerNames.TRANSFER_MATCHER)
    fun onTransactionsChanged(record: ConsumerRecord<String, String>) {
        val env = codec.read(record.value(), EventTypes.TRANSACTIONS_CHANGED, TransactionsChangedPayload::class)
        correlated(env.correlationId) { service.handle(env) }
    }
}
