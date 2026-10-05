package com.kerosene.kfe.adapters.out.persistence.model.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Persists the last issued address index for one wallet derivation cursor.
 *
 * <p>The cursor key identifies the wallet and derivation branch (as defined by its caller), while
 * the index starts at {@code -1} so the first issued address can use index zero. Hibernate updates
 * {@code updatedAt} whenever the row changes, which helps operators identify cursor advancement.</p>
 */
@Entity
@Table(name = "custodial_derivation_cursors", schema = "financial")
public class KfeDerivationCursorEntity {

    /** Stable key identifying the wallet and derivation path whose index is being tracked. */
    @Id
    @Column(name = "cursor_key", nullable = false, updatable = false, length = 64)
    private String cursorKey;

    /** Highest address index already issued; {@code -1} means no index has been issued yet. */
    @Column(name = "last_issued_index", nullable = false)
    private Integer lastIssuedIndex = -1;

    /** Last persistence update time, maintained by Hibernate. */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** @return derivation cursor identity used as the primary key */
    public String getCursorKey() {
        return cursorKey;
    }

    /** @param cursorKey stable identity of the wallet and derivation branch */
    public void setCursorKey(String cursorKey) {
        this.cursorKey = cursorKey;
    }

    /** @return greatest address index already handed out, or {@code -1} before the first issue */
    public Integer getLastIssuedIndex() {
        return lastIssuedIndex;
    }

    /** @param lastIssuedIndex latest issued index; callers advance it atomically to avoid reuse */
    public void setLastIssuedIndex(Integer lastIssuedIndex) {
        this.lastIssuedIndex = lastIssuedIndex;
    }

    /** @return most recent persistence update time */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** @param updatedAt update time supplied by persistence or migration code */
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
