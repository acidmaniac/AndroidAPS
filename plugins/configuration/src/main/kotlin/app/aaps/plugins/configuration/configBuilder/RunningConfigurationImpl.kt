package app.aaps.plugins.configuration.configBuilder

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Sensitivity
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ConfigBuilder
import app.aaps.core.interfaces.configuration.ConfigExportImport
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.nsclient.NSClientRepository
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.smoothing.Smoothing
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.interfaces.RunningConfiguration
import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.plugins.configuration.R
import dagger.Reusable
import kotlinx.serialization.json.JsonObject
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject

@Reusable
class RunningConfigurationImpl @Inject constructor(
    private val activePlugin: ActivePlugin,
    private val insulin: Insulin,
    private val configBuilder: ConfigBuilder,
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger,
    private val config: Config,
    private val pumpSync: PumpSync,
    private val notificationManager: NotificationManager,
    private val nsClientRepository: NSClientRepository,
    private val constraintsChecker: ConstraintsChecker
) : RunningConfiguration {

    private var counter = 0
    private val every = 12 // Send only every 12 device status to save traffic

    // called in AAPS mode only
    override fun configuration(): JSONObject {
        val json = JSONObject()
        val pumpInterface = activePlugin.activePump

        if (!pumpInterface.isInitialized()) return json
        if (counter++ % every == 0)
            try {
                val sensitivityInterface = activePlugin.activeSensitivity
                val safetyInterface = activePlugin.activeSafety
                val smoothingInterface = activePlugin.activeSmoothing
                // APS interface is needed for dynamic sensitivity calculation
                val apsInterface = activePlugin.activeAPS

                json.put("insulin", insulin.id.value)
                json.put("insulinConfiguration", JSONObject(buildFromPlugin(insulin).toString()))
                apsInterface?.let {
                    json.put("aps", it.algorithm.name)
                    json.put("apsConfiguration", JSONObject(buildFromPlugin(it).toString()))
                }
                json.put("sensitivity", sensitivityInterface.id.value)
                json.put("sensitivityConfiguration", JSONObject(buildFromPlugin(sensitivityInterface).toString()))
                json.put("smoothing", smoothingInterface.javaClass.simpleName)
                json.put("overviewConfiguration", JSONObject(buildOverviewConfiguration().toString()))
                json.put("safetyConfiguration", JSONObject(buildFromPlugin(safetyInterface).toString()))
                json.put("pump", pumpInterface.model().description)
                json.put("version", config.VERSION_NAME)
            } catch (e: JSONException) {
                aapsLogger.error("Unhandled exception", e)
            }
        return json
    }

    // called in NSClient mode only
    override fun apply(configuration: NSDeviceStatus.Configuration) {
        assert(config.AAPSCLIENT)

        configuration.version?.let {
            nsClientRepository.addLog("◄ VERSION", "Received AAPS version  $it")
            if (config.VERSION_NAME.startsWith(it).not())
                notificationManager.post(NotificationId.NSCLIENT_VERSION_DOES_NOT_MATCH, R.string.nsclient_version_does_not_match)
        }
        configuration.insulinConfiguration?.let { ic ->
            applyToPlugin(insulin, ic)
        }

        configuration.aps?.let {
            val algorithm = APSResult.Algorithm.valueOf(it)
            for (p in activePlugin.getSpecificPluginsListByInterface(APS::class.java)) {
                val apsPlugin = p as APS
                if (apsPlugin.algorithm == algorithm) {
                    if (!p.isEnabled()) {
                        aapsLogger.debug(LTag.CORE, "Changing aps plugin to ${apsPlugin.algorithm}")
                        configBuilder.performPluginSwitch(p, true, PluginType.APS)
                    }
                    configuration.apsConfiguration?.let { ac -> applyToPlugin(apsPlugin, ac) }
                }
            }
        }

        configuration.sensitivity?.let {
            val sensitivity = Sensitivity.SensitivityType.fromInt(it)
            for (p in activePlugin.getSpecificPluginsListByInterface(Sensitivity::class.java)) {
                val sensitivityPlugin = p as Sensitivity
                if (sensitivityPlugin.id == sensitivity) {
                    if (!p.isEnabled()) {
                        aapsLogger.debug(LTag.CORE, "Changing sensitivity plugin to ${sensitivity.name}")
                        configBuilder.performPluginSwitch(p, true, PluginType.SENSITIVITY)
                    }
                    configuration.sensitivityConfiguration?.let { sc -> applyToPlugin(sensitivityPlugin, sc) }
                }
            }
        }

        configuration.smoothing?.let {
            for (p in activePlugin.getSpecificPluginsListByInterface(Smoothing::class.java)) {
                val smoothingPlugin = p as Smoothing
                if (smoothingPlugin.javaClass.simpleName == it) {
                    if (!p.isEnabled()) {
                        aapsLogger.debug(LTag.CORE, "Changing smoothing plugin to ${smoothingPlugin.javaClass.simpleName}")
                        configBuilder.performPluginSwitch(p, true, PluginType.SMOOTHING)
                    }
                }
            }
        }

        configuration.pump?.let {
            if (preferences.get(StringKey.VirtualPumpType) != it) {
                preferences.put(StringKey.VirtualPumpType, it)
                activePlugin.activePump.pumpDescription.fillFor(PumpType.getByDescription(it))
                pumpSync.connectNewPump(endRunning = false) // do not end running TBRs, we call this only to accept data properly
                aapsLogger.debug(LTag.CORE, "Changing pump type to $it")
            }
        }

        configuration.overviewConfiguration?.let { applyOverviewConfiguration(it) }

        configuration.safetyConfiguration?.let { sc ->
            applyToPlugin(activePlugin.activeSafety, sc)
        }
    }

    private fun buildFromPlugin(plugin: ConfigExportImport): JsonObject =
        plugin.syncedKeys.fold(JsonObject(emptyMap())) { acc, k -> acc.put(k, preferences) }

    private fun applyToPlugin(plugin: ConfigExportImport, json: JsonObject) {
        plugin.syncedKeys.forEach { json.store(it, preferences) }
        plugin.reloadInternalState()
    }

    private fun buildOverviewConfiguration(): JsonObject {
        val base = SyncedConfigSchema.overviewKeys.fold(JsonObject(emptyMap())) { acc, k ->
            acc.put(k, preferences)
        }
        return base.put(BooleanNonKey.AutosensUsedOnMainPhone, constraintsChecker.isAutosensModeEnabled().value())
    }

    private fun applyOverviewConfiguration(configuration: JsonObject) {
        SyncedConfigSchema.overviewKeys.forEach { configuration.store(it, preferences) }
        configuration.store(BooleanNonKey.AutosensUsedOnMainPhone, preferences)
    }
}
