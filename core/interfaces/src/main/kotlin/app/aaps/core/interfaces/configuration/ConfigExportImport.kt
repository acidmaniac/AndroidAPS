package app.aaps.core.interfaces.configuration

import app.aaps.core.keys.interfaces.NonPreferenceKey

/**
 * Allow export and import plugin configuration
 */
interface ConfigExportImport {

    /**
     * Preference keys whose values are part of this plugin's exportable configuration.
     * Iterated by the central orchestrator (e.g. RunningConfigurationImpl) to build
     * outbound JSON and to apply incoming JSON.
     */
    val syncedKeys: List<NonPreferenceKey>

    /**
     * Called after one or more [syncedKeys] have been written externally
     * (cross-device sync, devicestatus apply, etc.).
     * Empty body if no cached state to rebuild; non-empty if the plugin holds
     * an in-memory cache derived from those preferences.
     */
    fun reloadInternalState()
}
