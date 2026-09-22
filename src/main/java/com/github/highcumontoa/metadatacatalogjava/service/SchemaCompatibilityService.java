package com.github.highcumontoa.metadatacatalogjava.service;

import com.github.highcumontoa.metadatacatalogjava.model.DataType;
import org.springframework.stereotype.Service;

/**
 * Central schema-compatibility policy.
 * <p>
 * The single, uniform policy is <b>backward (reader) compatibility</b>:
 * <ul>
 *   <li>adding a field: compatible (old readers simply ignore it);</li>
 *   <li>removing a field: breaking (old readers may still request it) — rejected;</li>
 *   <li>widening a type: compatible, e.g. INTEGER &rarr; LONG, LONG &rarr; DECIMAL,
 *       DATE &rarr; TIMESTAMP, anything &rarr; STRING;</li>
 *   <li>every other type change: breaking — rejected with the rule text.</li>
 * </ul>
 * The policy is intentionally centralized so every entry point (REST,
 * registration conflict, tests) reaches the same explainable decision.
 */
@Service
public class SchemaCompatibilityService {

    /**
     * Whether changing {@code from} into {@code to} keeps old readers working.
     */
    public boolean isTypeChangeCompatible(DataType from, DataType to) {
        if (from == null || to == null || to == DataType.UNKNOWN) {
            return false;
        }
        if (from == to) {
            return true;
        }
        return switch (from) {
            // safe widenings
            case INTEGER -> to == DataType.LONG || to == DataType.DECIMAL || to == DataType.STRING;
            case LONG -> to == DataType.DECIMAL || to == DataType.STRING;
            case DATE -> to == DataType.TIMESTAMP || to == DataType.STRING;
            case BOOLEAN -> to == DataType.STRING;
            case STRING -> false;
            default -> false; // DECIMAL, TIMESTAMP, BINARY, UNKNOWN: no safe widening
        };
    }

    public String explainTypeChange(DataType from, DataType to) {
        if (from == to) {
            return "unchanged type " + from;
        }
        if (isTypeChangeCompatible(from, to)) {
            return "compatible widening " + from + " -> " + to;
        }
        return "breaking type change " + from + " -> " + to
                + ": only INTEGER->LONG/DECIMAL/STRING, LONG->DECIMAL/STRING, "
                + "DATE->TIMESTAMP/STRING, BOOLEAN->STRING are accepted";
    }
}
