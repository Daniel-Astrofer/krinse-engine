package com.kerosene.kfe.wallet.application.port.in;

import com.kerosene.kfe.wallet.application.command.ListWalletUtxosCommand;
import com.kerosene.kfe.wallet.application.result.WalletUtxoResult;

public interface ListWalletUtxosUseCase {
    WalletUtxoResult list(ListWalletUtxosCommand command);
}
