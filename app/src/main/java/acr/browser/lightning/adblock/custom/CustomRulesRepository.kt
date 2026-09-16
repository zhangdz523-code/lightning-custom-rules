package acr.browser.lightning.adblock.custom

/**
 * A repository that stores user-defined [CustomRule]s.
 */
interface CustomRulesRepository {

    /**
     * Add a custom rule. Returns `true` if the rule was added, `false` if it already existed.
     */
    suspend fun addRule(rule: CustomRule): Boolean

    /**
     * Remove a custom rule by its pattern.
     */
    suspend fun removeRule(pattern: String): Boolean

    /**
     * Enable or disable a custom rule.
     */
    suspend fun setRuleEnabled(pattern: String, enabled: Boolean)

    /**
     * Get all custom rules.
     */
    suspend fun getAllRules(): List<CustomRule>

    /**
     * Get all enabled custom rules.
     */
    suspend fun getEnabledRules(): List<CustomRule>

    /**
     * Check if a URL matches any enabled custom rule.
     */
    fun matchesAnyRule(url: String): Boolean

    /**
     * Remove all custom rules.
     */
    suspend fun removeAllRules()

    /**
     * Count the total number of rules.
     */
    suspend fun count(): Long
}
