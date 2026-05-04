package app.aaps.plugins.configuration.configBuilder

import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.NonPreferenceKey

/**
 * Single source of truth for preferences carried in the `overviewConfiguration`
 * block of [RunningConfigurationImpl]. The same list drives both build (write to JSON)
 * and apply (read from JSON), eliminating the parallel-list anti-pattern.
 *
 * Plugin-owned keys (Insulin / APS / Sensitivity / Safety) are NOT here — those are
 * iterated via each plugin's own `syncedKeys`.
 *
 * `StringNonKey.QuickWizard` is included here even though `QuickWizard` is a
 * `ConfigExportImport` (step 1.5). The wire shape today nests it inside
 * `overviewConfiguration`; keeping it here preserves that shape during the dual-write
 * window. When the publisher writes the new settings doc (phase 1) it will choose
 * which channel owns the key and resolve the duplication.
 *
 * `BooleanNonKey.AutosensUsedOnMainPhone` is intentionally NOT in this list — its
 * outbound value is computed (`constraintsChecker.isAutosensModeEnabled()`), not
 * read from preferences. It's handled explicitly in [RunningConfigurationImpl].
 */
object SyncedConfigSchema {

    val overviewKeys: List<NonPreferenceKey> = listOf(
        StringKey.GeneralUnits,
        StringNonKey.QuickWizard,
        StringNonKey.TempTargetPresets,
        UnitDoubleKey.OverviewLowMark,
        UnitDoubleKey.OverviewHighMark,
        IntKey.OverviewCageWarning,
        IntKey.OverviewCageCritical,
        IntKey.OverviewIageWarning,
        IntKey.OverviewIageCritical,
        IntKey.OverviewSageWarning,
        IntKey.OverviewSageCritical,
        IntKey.OverviewSbatWarning,
        IntKey.OverviewSbatCritical,
        IntKey.OverviewBageWarning,
        IntKey.OverviewBageCritical,
        IntKey.OverviewResWarning,
        IntKey.OverviewResCritical,
        IntKey.OverviewBattWarning,
        IntKey.OverviewBattCritical,
        IntKey.OverviewBolusPercentage,
    )
}
