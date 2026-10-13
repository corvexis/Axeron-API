package frb.axeron.server

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class Mode(val label: String) {
    NOT_ACTIVATED("Not Activated"), ROOT("Root"), SHELL("Shell"), USER("User")
}


@Parcelize
data class ServerInfo(
    val version: String = "Unknown",
    val versionCode: Long = -1,
    val uid: Int = -1,
    val pid: Int = -1,
    val selinuxContext: String = "Unknown",
    /**
     * [android.os.SystemClock.elapsedRealtime] at server start, or [UNKNOWN_STARTING] when this
     * instance is a client-side placeholder rather than a real server reply.
     *
     * This used to default to `SystemClock.elapsedRealtime()` - i.e. "right now" - which made
     * every placeholder look like a server that had just started, so uptime rendered as
     * T+00:00:00 until real data arrived. The real server always passes a genuine value
     * (`AxeronService.starting`), so this default is only ever seen on the manager side.
     * Callers must check [hasKnownStarting] before rendering an uptime.
     */
    val starting: Long = UNKNOWN_STARTING,
    val permission: Boolean = false
) : Parcelable {

    /** True when [starting] holds a real timestamp rather than the placeholder sentinel. */
    fun hasKnownStarting(): Boolean = starting > 0L

    fun getMode(): Mode {
        return when (uid) {
            -1 -> Mode.NOT_ACTIVATED
            0 -> Mode.ROOT
            2000 -> Mode.SHELL
            else -> Mode.ROOT
        }
    }

    companion object {
        /** Sentinel meaning "no server has reported a start time yet". */
        const val UNKNOWN_STARTING = 0L
    }
}