package acr.browser.lightning.adblock.custom

/**
 * A user-defined custom blocking rule.
 *
 * @param pattern The pattern to match. Supports exact domain match and wildcard patterns
 *                using `*` (e.g. `ads.example.com`, `*ads*`, `*.tracker.com`).
 * @param enabled Whether this rule is currently active.
 */
data class CustomRule(
    val pattern: String,
    val enabled: Boolean = true,
)
