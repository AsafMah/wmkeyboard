package com.wasimaster.wmkeyboard.ime.speedtest

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.resolveProviders
import com.wasimaster.wmkeyboard.core.tools.speedtest.LibreSpeedServers
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestServer

/**
 * The speed test's server, from its provider list: the row the panel's
 * chooser picked, or the first row that is on. Null when every row is off or
 * none can be asked (a LibreSpeed row with no address).
 */
object SpeedTestProviders {

    fun server(settings: KeyboardSettings, pick: String): SpeedTestServer? =
        settings.resolveProviders(ProviderTool.SPEED_TEST, pick = pick).firstNotNullOfOrNull(::serverOf)

    fun serverOf(row: ResolvedProvider): SpeedTestServer? = when (row.typeId) {
        ProviderTypes.CLOUDFLARE_SPEED.id -> SpeedTestServer.Cloudflare(ServiceEndpoints.base(ServiceEndpoint.CLOUDFLARE_SPEED))
        ProviderTypes.LIBRESPEED.id -> row.url.takeIf { it.isNotBlank() }?.let {
            LibreSpeedServers.fromCredential(it, row.customName, row.credential?.extra.orEmpty())
        }
        else -> null
    }
}
