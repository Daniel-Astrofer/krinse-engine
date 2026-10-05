package com.kerosene.kfe.wallet.application.port.in;

import com.kerosene.kfe.wallet.application.command.CreateWalletCommand;
import com.kerosene.kfe.wallet.application.command.UpdateWalletCommand;
import com.kerosene.kfe.wallet.application.command.WalletCommand;
import com.kerosene.kfe.wallet.application.result.WalletAddressView;
import com.kerosene.kfe.wallet.application.result.WalletView;

import java.util.List;

/** Inbound wallet lifecycle contract used by HTTP and internal callers. */
public interface WalletLifecycleUseCase {
    WalletView create(CreateWalletCommand command);

    List<WalletView> list(long ownerId);

    WalletView update(UpdateWalletCommand command);

    WalletView archive(WalletCommand command);

    WalletAddressView rotateAddress(WalletCommand command);

    String ensureActiveReceiveAddress(WalletCommand command);
}
