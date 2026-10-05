package com.kerosene.kfe.pricing.application.port.in;

import com.kerosene.kfe.pricing.application.command.QuoteTransactionCommand;
import com.kerosene.kfe.pricing.application.result.TransactionQuoteResult;

public interface QuoteTransactionUseCase {
    TransactionQuoteResult quote(QuoteTransactionCommand command);
}
