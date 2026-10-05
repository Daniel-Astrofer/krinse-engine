package com.kerosene.kfe.wallet.application.port.out;

import com.kerosene.kfe.wallet.application.command.CreateWalletCommand;
import com.kerosene.kfe.wallet.application.command.UpdateWalletCommand;
import com.kerosene.kfe.wallet.application.command.WalletCommand;
import com.kerosene.kfe.wallet.application.result.WalletAddressView;
import com.kerosene.kfe.wallet.application.result.WalletView;

import java.util.List;

/** Side-effect boundary for wallet lifecycle persistence and external provisioning. */
public interface WalletLifecyclePort {
    WalletView create(CreateWalletCommand command);

    List<WalletView> list(long ownerId);

    WalletView update(UpdateWalletCommand command);

    WalletView archive(WalletCommand command);

    WalletAddressView rotateAddress(WalletCommand command);

    String ensureActiveReceiveAddress(WalletCommand command);
}
