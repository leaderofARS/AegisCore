package security;

import java.util.Set;

/**
 * Validates player display names against AegisCore naming rules.
 *
 * <p>Rules:
 * <ul>
 *   <li>2–20 characters long</li>
 *   <li>Alphanumeric characters and underscores only</li>
 *   <li>Must not be a reserved word (case-insensitive)</li>
 * </ul>
 *
 * <p>Non-instantiable.
 */
public final class NameValidator {

    private static final Set<String> RESERVED = Set.of(
        "admin", "server", "system", "aegiscore", "root", "mod",
        "moderator", "bot", "console", "null", "undefined"
    );

    private NameValidator() {}

    /**
     * Validates the given display name and returns a {@link ValidationResult}.
     *
     * @param name the candidate display name
     * @return {@code Valid} if the name is acceptable, otherwise {@code Invalid} with a reason
     */
    public static ValidationResult validate(String name) {
        if (name == null || name.isBlank()) {
            return new ValidationResult.Invalid("Name must not be empty.");
        }
        if (name.length() < 2) {
            return new ValidationResult.Invalid("Name must be at least 2 characters.");
        }
        if (name.length() > 20) {
            return new ValidationResult.Invalid("Name must be at most 20 characters.");
        }
        for (char c : name.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return new ValidationResult.Invalid(
                    "Name may only contain letters, digits, and underscores.");
            }
        }
        if (RESERVED.contains(name.toLowerCase())) {
            return new ValidationResult.Invalid("\"" + name + "\" is a reserved word.");
        }
        return new ValidationResult.Valid(name);
    }

    // -----------------------------------------------------------------------
    // Sealed result hierarchy
    // -----------------------------------------------------------------------

    /** Sealed result type returned by {@link #validate(String)}. */
    public sealed interface ValidationResult permits ValidationResult.Valid, ValidationResult.Invalid {

        /** The name passed all validation checks. */
        record Valid(String cleanName) implements ValidationResult {}

        /** The name failed validation. */
        record Invalid(String reason) implements ValidationResult {}
    }
}
