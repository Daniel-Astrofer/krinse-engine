package com.kerosene.kfe.wallet.application.port.in;

import com.kerosene.kfe.wallet.application.command.CreateColdPsbtCommand;
import com.kerosene.kfe.wallet.application.result.ColdPsbtResult;

public interface CreateColdPsbtUseCase {
    ColdPsbtResult create(CreateColdPsbtCommand command);
}
