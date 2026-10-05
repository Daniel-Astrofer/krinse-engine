package com.kerosene.kfe.adapters.out.persistence.model.shared;

/** Direction of value flow represented by a persisted financial operation. */
public enum KfeDirection {
    /** Value enters the user's or system's financial boundary. */
    INBOUND,
    /** Value leaves the user's or system's financial boundary. */
    OUTBOUND,
    /** Value moves between internal accounts without crossing the external boundary. */
    INTERNAL
}
