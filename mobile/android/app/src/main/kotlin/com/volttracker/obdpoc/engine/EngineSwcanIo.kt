package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.PidPollingState

/**
 * The engine operations [SwcanListenRunner] (and, through [CarControlEngineIo], car controls) drive.
 * All adapter IO still goes through the engine's own command path under the adapter IO lock.
 */
internal class EngineSwcanIo(
    private val host: EngineHost,
    private val connection: () -> ElmConnection,
    private val sendCommand: (String, Long) -> String,
    private val reinit: () -> Unit,
    private val pidPolling: PidPollingState,
    private val parked: ParkedDetector,
) : SwcanListenRunner.Io {
    override fun send(
        command: String,
        timeoutMs: Long,
    ): String = sendCommand(command, timeoutMs)

    override fun monitor(
        command: String,
        listenMs: Long,
        stopTimeoutMs: Long,
    ): ElmConnection.MonitorResult =
        synchronized(host.ioLock) {
            host.recorder.bodyBusMonitoring()
            connection().monitor(command, listenMs, stopTimeoutMs, host.running::get)
        }

    override fun monitorStream(
        command: String,
        listenMs: Long,
        stopTimeoutMs: Long,
        onLine: (String) -> Boolean,
        onDrain: (String) -> Unit,
    ): ElmConnection.MonitorResult =
        synchronized(host.ioLock) {
            host.recorder.bodyBusMonitoring()
            connection().monitorStream(command, listenMs, stopTimeoutMs, host.running::get, onLine, onDrain)
        }

    override fun reinitialize() = reinit()

    override fun liveCycleCount(): Long = pidPolling.liveCycleCount()

    override fun msSinceLiveData(): Long = pidPolling.msSinceLastLiveData()

    override fun <T> exclusive(block: () -> T): T = synchronized(host.ioLock) { block() }

    override fun isStationary(): Boolean = parked.isParked(System.currentTimeMillis())

    override fun isInPark(): Boolean = parked.isInPark(System.currentTimeMillis())

    override fun motionCount(): Long = parked.motionCount()

    override fun requestGearRead() = pidPolling.pollSoon(ParkedDetector.GEAR_COMMAND)

    override fun noteMotion() = parked.moved()

    override fun openVoice(): GuidedCarTest.Voice = AndroidVoice(host.androidContext)

    override fun logEvent(
        event: String,
        vararg pairs: String,
    ) {
        host.recorder.logEvent(event, *pairs)
    }
}
