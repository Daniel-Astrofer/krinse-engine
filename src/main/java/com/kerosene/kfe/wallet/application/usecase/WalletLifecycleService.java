package com.kerosene.kfe.wallet.application.usecase;

import com.kerosene.kfe.wallet.application.command.CreateWalletCommand;
import com.kerosene.kfe.wallet.application.command.UpdateWalletCommand;
import com.kerosene.kfe.wallet.application.command.WalletCommand;
import com.kerosene.kfe.wallet.application.port.in.WalletLifecycleUseCase;
import com.kerosene.kfe.wallet.application.port.out.WalletLifecyclePort;
import com.kerosene.kfe.wallet.application.result.WalletAddressView;
import com.kerosene.kfe.wallet.application.result.WalletView;

import java.util.List;

/** Pure application boundary; framework, RPC and persistence stay in the adapter. */
public final class WalletLifecycleService implements WalletLifecycleUseCase {
    private final WalletLifecyclePort lifecycle;

    public WalletLifecycleService(WalletLifecyclePort lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public WalletView create(CreateWalletCommand command) {
        return lifecycle.create(command);
    }

    @Override
    public List<WalletView> list(long ownerId) {
        return lifecycle.list(ownerId);
    }

    @Override
    public WalletView update(UpdateWalletCommand command) {
        return lifecycle.update(command);
    }

    @Override
    public WalletView archive(WalletCommand command) {
        return lifecycle.archive(command);
    }

    @Override
    public WalletAddressView rotateAddress(WalletCommand command) {
        return lifecycle.rotateAddress(command);
    }

    @Override
    public String ensureActiveReceiveAddress(WalletCommand command) {
        return lifecycle.ensureActiveReceiveAddress(command);
    }
}
