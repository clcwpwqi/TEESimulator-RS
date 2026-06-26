package org.matrix.TEESimulator.interception.soter

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.matrix.TEESimulator.interception.core.BinderInterceptor
import org.matrix.TEESimulator.logging.SystemLogger

/**
 * Keeps [SoterServiceInterceptor] mounted on the on-demand, restartable
 * `com.tencent.soter.soterserver` process.
 *
 * `AbstractKeystoreInterceptor` injects `keystore2` exactly once: it is always alive and
 * servicemanager-published, so the daemon gets its binder from `ServiceManager` and may
 * `exitProcess` on failure. soterserver inverts both — it is Intent-bound (NOT in
 * `ServiceManager`) and may die and respawn. This supervisor therefore *binds* the SOTER
 * service, which both triggers its on-demand start AND yields the `ISoterService` binder
 * (the target the native MITM registry keys on); injects `libTEESimulator.so` on every
 * (re)start; confirms the landing with the `0xdeadbeef` backdoor handshake; then registers
 * the forge. It re-binds — re-poking, re-injecting, re-registering — whenever the process
 * dies, never exiting.
 *
 * The bind recipe (action = the interface descriptor, package, `BIND_AUTO_CREATE`) and the
 * rebind-on-death lifecycle mirror the SOTER SDK's own `SoterCoreTreble`, so the daemon
 * connects exactly as a real client would. Everything runs on a dedicated [HandlerThread]
 * so it never stalls keystore init or `Looper.loop()` in [org.matrix.TEESimulator.App].
 *
 * Observability (the checkpoint's mandatory gate): every lifecycle event — bind, connect,
 * inject ok/fail, handshake, respawn — is logged via [SystemLogger], debug-gated. It never
 * gates the forge.
 */
object SoterProcessSupervisor {

    /** soterserver hosts the package's own process (recon 2026-06-26: process == package). */
    private const val SOTER_PACKAGE = "com.tencent.soter.soterserver"

    /** Reuses the daemon's native injector + `entry`, PID-resolved by the target package. */
    private const val INJECTION_COMMAND =
        "exec ./inject `pidof $SOTER_PACKAGE` libTEESimulator.so entry"

    private const val REBIND_DELAY_MS = 1000L

    private val started = AtomicBoolean(false)

    private lateinit var context: Context
    private lateinit var handler: Handler

    /** Delivers bind callbacks onto the supervisor thread so nothing touches the main looper. */
    private val executor = Executor { command -> handler.post(command) }

    /**
     * Starts supervising on a dedicated thread and returns immediately. Idempotent. [context]
     * must be able to bind services (the daemon's system context); supplied by the App wiring.
     */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        this.context = context
        handler = Handler(HandlerThread("soter-supervisor").apply { start() }.looper)
        handler.post { bind() }
    }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                SystemLogger.debug("SOTER service connected; mounting forge")
                service?.let(::mount)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                SystemLogger.debug("SOTER service disconnected (process died); rebinding")
                rebind()
            }

            override fun onBindingDied(name: ComponentName?) {
                SystemLogger.debug("SOTER binding died; rebinding")
                rebind()
            }
        }

    private fun bind() {
        val intent = Intent(SoterServiceInterceptor.DESCRIPTOR).setPackage(SOTER_PACKAGE)
        val bound =
            runCatching {
                    context.bindService(intent, Context.BIND_AUTO_CREATE, executor, connection)
                }
                .getOrElse {
                    SystemLogger.debug { "SOTER bindService threw: $it" }
                    false
                }
        if (bound) {
            SystemLogger.debug("SOTER bind requested (on-demand poke)")
        } else {
            SystemLogger.debug("SOTER bindService returned false; retrying")
            handler.postDelayed({ rebind() }, REBIND_DELAY_MS)
        }
    }

    private fun rebind() {
        runCatching { context.unbindService(connection) }
        handler.postDelayed({ bind() }, REBIND_DELAY_MS)
    }

    /** Confirms injection via the `0xdeadbeef` handshake, injecting first if absent, then registers. */
    private fun mount(soterBinder: IBinder) {
        var backdoor = BinderInterceptor.getBackdoor(soterBinder)
        if (backdoor == null) {
            SystemLogger.debug("SOTER backdoor absent; injecting libTEESimulator.so")
            if (!injectLibrary()) {
                SystemLogger.debug("SOTER injection failed; will retry on next (re)bind")
                return
            }
            backdoor = BinderInterceptor.getBackdoor(soterBinder)
        }
        if (backdoor == null) {
            SystemLogger.debug("SOTER backdoor handshake failed after injection")
            return
        }
        BinderInterceptor.register(
            backdoor,
            soterBinder,
            SoterServiceInterceptor,
            SoterServiceInterceptor.interceptedCodes,
        )
        SystemLogger.debug("SOTER forge mounted; handshake ok")
    }

    private fun injectLibrary(): Boolean =
        runCatching {
                Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", INJECTION_COMMAND)).waitFor() == 0
            }
            .getOrElse {
                SystemLogger.debug { "SOTER inject exec failed: $it" }
                false
            }
}
