package com.kerosene.kfe.pricing.adapters.in.http;

import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.pricing.application.port.in.QuoteTransactionUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for fee-aware KFE transaction quote requests. */
@RestController
@RequestMapping("/kfe/transactions")
public class TransactionQuoteController {

    /** Application use case that estimates fees and computes the current quote. */
    private final QuoteTransactionUseCase quoteTransaction;

    /** Creates the controller with the pricing application boundary. */
    public TransactionQuoteController(QuoteTransactionUseCase quoteTransaction) {
        this.quoteTransaction = quoteTransaction;
    }

    /** Validates the quote request, maps it into a command, and wraps the result in the API envelope. */
    @PostMapping("/quote")
    public ResponseEntity<ApiResponse<TransactionQuoteResponse>> quote(
            @Valid @RequestBody TransactionQuoteRequest request) {
        TransactionQuoteResponse response = TransactionQuoteHttpMapper.toResponse(
                quoteTransaction.quote(TransactionQuoteHttpMapper.toCommand(request)));
        return ResponseEntity.ok(ApiResponse.success("KFE transaction quote calculated.", response));
    }
}
