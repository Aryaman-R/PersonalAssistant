package com.sentient.mesh;

/**
 * Last-Writer-Wins register: holds one value plus the {@link HLC} at which
 * that value was written. {@link #set(Object, HLC)} accepts the new value
 * only if the new HLC is strictly greater than the stored one.
 *
 * <p>Type parameter {@code T} is whatever the underlying field is: usually
 * {@link String} or {@link Boolean}. A null value is permitted (meaning
 * "the field has been explicitly cleared at this HLC") — distinguish from
 * "never set" by checking {@link #ts()} for null.
 *
 * <p>Operations are thread-safe through coarse {@code synchronized}; the
 * register is the granularity of mesh ops, so contention is fine-grained.
 */
public final class LwwRegister<T> {

    private T value;
    private HLC ts;

    public LwwRegister() {}

    public LwwRegister(T value, HLC ts) {
        this.value = value;
        this.ts = ts;
    }

    /**
     * Try to apply a write. Returns {@code true} if the register was updated
     * (i.e. the new HLC is strictly greater than the stored one), {@code false}
     * if the write lost the merge race.
     *
     * @param newValue new value (may be null to clear)
     * @param newTs    HLC of the writing op (required)
     */
    public synchronized boolean set(T newValue, HLC newTs) {
        if (newTs == null) throw new IllegalArgumentException("HLC required");
        if (ts == null || newTs.compareTo(ts) > 0) {
            value = newValue;
            ts = newTs;
            return true;
        }
        return false;
    }

    public synchronized T get() { return value; }
    public synchronized HLC ts() { return ts; }
    public synchronized boolean isSet() { return ts != null; }
}
