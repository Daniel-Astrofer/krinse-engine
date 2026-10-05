package com.kerosene.kfe.wallet.application.usecase;

import com.kerosene.kfe.wallet.application.command.CreateColdPsbtCommand;
import com.kerosene.kfe.wallet.application.port.out.WalletApprovalPort;
import com.kerosene.kfe.wallet.application.port.out.WalletAuditPort;
import com.kerosene.kfe.wallet.application.port.out.WalletChangeAddressPort;
import com.kerosene.kfe.wallet.application.port.out.WalletChainPort;
import com.kerosene.kfe.wallet.application.port.out.WalletDestinationPort;
import com.kerosene.kfe.wallet.application.port.out.WalletHashPort;
import com.kerosene.kfe.wallet.application.port.out.WalletPsbtPort;
import com.kerosene.kfe.wallet.application.port.out.WalletQueryPort;
import com.kerosene.kfe.wallet.application.port.out.WalletWorkflowPort;
import com.kerosene.kfe.wallet.domain.model.ColdPsbtRequest;
import com.kerosene.kfe.wallet.domain.model.Outpoint;
import com.kerosene.kfe.wallet.domain.model.UtxoSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateColdPsbtServiceTest {
    @Test
    void createsBoundWorkflowThroughExplicitPorts() {
        UUID walletId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        var wallet = new WalletSnapshot(
                walletId, 42L, WalletKind.WATCH_ONLY, WalletStatus.ACTIVE, "BTC", false,
                true, true, "tpub", "wpkh(tpub/0/*)");
        var input = new UtxoSnapshot(
                new Outpoint("txid", 0), 20_000L, "0014", "tb1qsource", 6);
        WalletQueryPort wallets = mock(WalletQueryPort.class);
        WalletChainPort chain = mock(WalletChainPort.class);
        WalletApprovalPort approval = mock(WalletApprovalPort.class);
        WalletDestinationPort destination = mock(WalletDestinationPort.class);
        WalletChangeAddressPort change = mock(WalletChangeAddressPort.class);
        WalletPsbtPort psbt = mock(WalletPsbtPort.class);
        WalletWorkflowPort workflows = mock(WalletWorkflowPort.class);
        WalletHashPort hash = mock(WalletHashPort.class);
        WalletAuditPort audit = mock(WalletAuditPort.class);
        when(wallets.findOwned(42L, walletId)).thenReturn(Optional.of(wallet));
        when(wallets.activeAddresses(walletId)).thenReturn(List.of());
        when(chain.listUnspent(wallet, List.of(), 200)).thenReturn(List.of(input));
        when(change.issueChangeAddress(wallet)).thenReturn("tb1qchange");
        when(psbt.create(any(), any(), any(Long.class), any(), any(), any()))
                .thenReturn(new WalletPsbtPort.FundedPsbt("psbt", 120L));
        when(hash.sha256("psbt")).thenReturn("hash");
        when(workflows.create(42L, walletId, "psbt", "hash", 120L, 10_000L,
                "tb1qdestination", List.of(new Outpoint("txid", 0)))).thenReturn(workflowId);

        var service = new CreateColdPsbtService(
                wallets, chain, approval, destination, change, psbt, workflows, hash, audit, 200);
        var result = service.create(new CreateColdPsbtCommand(
                42L,
                walletId,
                new ColdPsbtRequest("tb1qdestination", 10_000L, 3, 2L,
                        List.of(new Outpoint("TXID", 0))),
                "totp"));

        assertThat(result.workflowId()).isEqualTo(workflowId);
        assertThat(result.psbtHash()).isEqualTo("hash");
        verify(approval).approveColdPsbt(42L, "totp");
        verify(destination).requireValidBitcoinAddress("tb1qdestination");
        verify(audit).record(any(), any(), any(Map.class));
    }
}
