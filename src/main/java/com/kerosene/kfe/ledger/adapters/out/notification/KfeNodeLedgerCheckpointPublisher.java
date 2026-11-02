package com.kerosene.kfe.ledger.adapters.out.notification;

import com.kerosene.kfe.adapters.out.integration.directory.KfeKeroseneNodeDirectory;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeLedgerCheckpointReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes read-only checkpoints; financial mutations remain solely in the KFE. */
@Component
@ConditionalOnProperty(name = "kfe.ledger.node-sync.enabled", havingValue = "true")
public class KfeNodeLedgerCheckpointPublisher {
    private final KfeLedgerCheckpointReader reader;
    private final KfeLedgerCheckpointSigner signer;
    private final KfeKeroseneNodeDirectory directory;

    public KfeNodeLedgerCheckpointPublisher(KfeLedgerCheckpointReader reader,
            KfeLedgerCheckpointSigner signer, KfeKeroseneNodeDirectory directory) {
        this.reader = reader;
        this.signer = signer;
        this.directory = directory;
    }

    @Scheduled(initialDelayString = "${kfe.ledger.node-sync.initial-delay-ms:15000}",
            fixedDelayString = "${kfe.ledger.node-sync.interval-ms:30000}")
    public void publish() {
        var root = reader.read();
        directory.publishLedgerCheckpoint(challenge -> signer.sign(root, challenge));
    }
}
