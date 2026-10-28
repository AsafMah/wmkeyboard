package com.wasimaster.wmkeyboard.core.share

import kotlinx.serialization.Serializable

/**
 * Where a theme or layout came from, when it was installed from an addon
 * repository: kept on the spec so an edited copy still names its original.
 *
 * The name and author travel with the key rather than being looked up, because
 * the install record they would come from is gone the moment the user
 * uninstalls the original, and a remix should keep its credit either way.
 */
@Serializable
data class RemixCredit(
    /** `"<repoId>/<addonId>"`, the install key in the addon store. */
    val key: String,
    val name: String = "",
    val author: String = "",
)
