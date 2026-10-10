package com.finoniq.docql;

import java.util.Objects;

/**
 * The instruction of a queryDocument call: a query or a prompt, never both
 * and never neither. The type system enforces the exclusive choice at compile
 * time — {@link #query(String)} and {@link #prompt(String)} are the only ways
 * to obtain an instance (D-02).
 *
 * <p>{@code Instruction.query("")} is sent unchanged; the server answers
 * 422 {@code missing_query_or_prompt}.
 */
public final class Instruction {

    private final boolean prompt;
    private final String text;

    private Instruction(boolean prompt, String text) {
        this.prompt = prompt;
        this.text = Objects.requireNonNull(text, "text");
    }

    /**
     * A natural-language question about the document.
     *
     * @param text the query; an empty string is sent unchanged
     * @return the instruction
     */
    public static Instruction query(String text) {
        return new Instruction(false, text);
    }

    /**
     * An extraction prompt describing what to pull out of the document.
     *
     * @param text the prompt
     * @return the instruction
     */
    public static Instruction prompt(String text) {
        return new Instruction(true, text);
    }

    /** Returns whether this instruction is a prompt (else a query). */
    boolean isPrompt() {
        return prompt;
    }

    /** Returns the instruction text, possibly empty for a query. */
    String text() {
        return text;
    }
}
