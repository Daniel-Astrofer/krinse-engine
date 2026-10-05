package com.kerosene.kfe.adapters.out.persistence.model.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Stores a user's tax classification for one externally identified financial event.
 *
 * <p>The composite key prevents a user from having duplicate classifications for the same event,
 * while allowing different users to classify that event independently. Creation and update times
 * are maintained in UTC by the persistence lifecycle callbacks.</p>
 */
@Entity
@Table(name = "tax_event_classifications", schema = "financial")
@IdClass(KfeTaxEventClassificationEntity.Key.class)
public class KfeTaxEventClassificationEntity {

    /** User who owns this classification; first component of the composite persistence key. */
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Stable external event identifier; second component of the composite persistence key. */
    @Id
    @Column(name = "event_id", nullable = false, length = 96)
    private String eventId;

    /** Required tax category assigned by the user, limited to the mapped column length. */
    @Column(name = "classification", nullable = false, length = 64)
    private String classification;

    /** UTC insertion time, fixed after the first persist. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** UTC time of the latest persisted change to this classification. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Initializes both timestamps from one UTC instant before inserting a new classification. */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    /** Refreshes the modification timestamp in UTC before updating a classification. */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** @return owner of this tax classification */
    public Long getUserId() {
        return userId;
    }

    /** @param userId user identifier forming the first part of the entity key */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /** @return external identifier of the classified event */
    public String getEventId() {
        return eventId;
    }

    /** @param eventId event identifier forming the second part of the entity key */
    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    /** @return the tax category currently assigned to the event */
    public String getClassification() {
        return classification;
    }

    /** @param classification tax category to persist for this user and event */
    public void setClassification(String classification) {
        this.classification = classification;
    }

    /**
     * Serializable JPA key containing the user and event identity components.
     *
     * <p>Equality and hash code use both fields so this key follows the same uniqueness semantics
     * as the entity's {@code @IdClass} mapping.</p>
     */
    public static class Key implements Serializable {
        /** User identifier component of the composite key. */
        private Long userId;
        /** Event identifier component of the composite key. */
        private String eventId;

        /** Required no-argument constructor for JPA composite-key materialization. */
        public Key() {
        }

        /** Creates a composite key from its two identity components.
         * @param userId user identifier
         * @param eventId event identifier
         */
        public Key(Long userId, String eventId) {
            this.userId = userId;
            this.eventId = eventId;
        }

        /** @return user identifier component */
        public Long getUserId() {
            return userId;
        }

        /** @param userId user identifier component to assign */
        public void setUserId(Long userId) {
            this.userId = userId;
        }

        /** @return event identifier component */
        public String getEventId() {
            return eventId;
        }

        /** @param eventId event identifier component to assign */
        public void setEventId(String eventId) {
            this.eventId = eventId;
        }

        /**
         * Compares both identity components, with the usual reflexive fast path.
         *
         * @param other candidate key to compare
         * @return {@code true} only when both user and event identifiers match
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return java.util.Objects.equals(userId, key.userId)
                    && java.util.Objects.equals(eventId, key.eventId);
        }

        /**
         * Computes a hash from both identity components, consistent with {@link #equals(Object)}.
         *
         * @return hash code suitable for hash-based persistence collections
         */
        @Override
        public int hashCode() {
            return java.util.Objects.hash(userId, eventId);
        }
    }
}
