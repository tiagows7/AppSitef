package com.appsitef.smartpos.tef

import android.app.Activity
import android.content.Context
import android.util.Log
import br.com.softwareexpress.sitef.android.CliSiTef

/**
 * GPOS700: o pinpad interno sobe pelo pinpad virtual da CliSiTef ([CliSiTef.setActivity]).
 * Não chama PP_Open — isso aborta a sessão no GPOS700.
 */
object GertecTefPinpad {

    private const val TAG = "GertecTefPinpad"
    private const val PINPAD_FRAGMENT_TAG = "Pinpad"

    /**
     * GPOS700: a impressora não é inicializada no começo da venda.
     */
    fun shouldInitGediBeforeSale(): Boolean = false

    /**
     * GPOS700: não sondar configure/liberate com abort antes do start —
     * essas chamadas nativas abortam a venda (mesmo padrão seguro do TLS no 720).
     */
    fun shouldProbeAndLiberateBeforeSale(): Boolean = false

    /** GPOS700: closePinPad antes do start aborta / corrompe a sessão. */
    fun allowsClosePinPadBeforeStart(): Boolean = false

    /**
     * No GPOS700 o DialogFragment é o pinpad interno. Escondê-lo no menu de
     * débito/crédito faz o SiTef devolver erro 31 na escolha.
     */
    fun shouldHidePinpadDuringAppMenu(): Boolean = false

    /**
     * O GetPIN nativo reabre o pinpad se ele ainda estiver no modo do cartão.
     * Essa reabertura no meio do pedido devolve erro 40. A tela numérica sobe antes.
     */
    fun shouldOpenNumericPinpadBeforeContinue(): Boolean = false

    fun prepare(activity: Activity) {
        ensureFactoryService(activity)
        loadPinpadLib(activity, "kemvs")
        loadPinpadLib(activity, "emvcl")
        loadPinpadLib(activity, "ppcomp")
    }

    fun probePinKeys(context: Context) = Unit

    /**
     * Abre o pinpad numérico e espera ele ficar visível, fora do GetPIN.
     * PP_SetKbd aqui, com a tela do cartão ainda aberta, também devolve erro 40.
     */
    fun preparePinEntry(activity: Activity, cliSiTef: CliSiTef) {
        try {
            val frag = findFragment(activity, cliSiTef)
            if (frag == null) {
                TefTraceLog.write(activity, "pinpadUI numeric fragment ausente")
                return
            }
            val show = frag.javaClass.methods.firstOrNull {
                it.name == "showPinpadNumericOnly" && it.parameterTypes.isEmpty()
            }
            if (show == null) {
                TefTraceLog.write(activity, "pinpadUI numeric metodo ausente")
                return
            }
            TefTraceLog.write(activity, "pinpadUI numeric begin")
            val worker = Thread({
                try {
                    show.invoke(frag)
                    TefTraceLog.write(activity, "pinpadUI numeric shown")
                } catch (error: Throwable) {
                    Log.w(TAG, "showPinpadNumericOnly", error)
                    TefTraceLog.write(
                        activity,
                        "pinpadUI numeric FAIL ${error.javaClass.simpleName}: ${error.message}",
                    )
                }
            }, "gpos700-show-numeric")
            worker.start()
            worker.join(8_000)
            if (worker.isAlive) {
                TefTraceLog.write(activity, "pinpadUI numeric timeout")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "preparePinEntry", error)
            TefTraceLog.write(
                activity,
                "pinpadUI numeric FAIL ${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }

    /**
     * Após À Vista: liga o teclado ao GEDI ([PP_SetKbd]) e habilita as teclas.
     * Não chama showPinpadNumericOnly (já sobe via DSP / patch do showPinpad).
     */
    fun ensureNumericPinpadForPin(activity: Activity, cliSiTef: CliSiTef) {
        Thread({
            try {
                if (activity.isFinishing) return@Thread
                val fromStart = System.currentTimeMillis()
                // Retries: fragment sobe no GetPIN; DSP Text desabilita teclas ~3s.
                val atMs = longArrayOf(200L, 600L, 1500L, 3500L, 5500L)
                for (target in atMs) {
                    val wait = target - (System.currentTimeMillis() - fromStart)
                    if (wait > 0) Thread.sleep(wait)
                    if (activity.isFinishing) return@Thread
                    val frag = findFragment(activity, cliSiTef)
                    if (frag == null) {
                        TefTraceLog.write(activity, "pinpadUI t=${target}ms fragment ausente")
                        continue
                    }
                    invokeEnabled(frag, true)
                    val ok = invokeSetKbd(frag)
                    TefTraceLog.write(
                        activity,
                        "pinpadUI t=${target}ms bindKbd=$ok ${frag.javaClass.simpleName}",
                    )
                }
            } catch (error: Throwable) {
                Log.w(TAG, "ensureNumericPinpadForPin", error)
                TefTraceLog.write(
                    activity,
                    "pinpadUI FAIL ${error.javaClass.simpleName}: ${error.message}",
                )
            }
        }, "gpos700-pinpad-ui").start()
    }

    @Suppress("DEPRECATION")
    private fun findFragment(activity: Activity, cliSiTef: CliSiTef): Any? {
        activity.fragmentManager.findFragmentByTag(PINPAD_FRAGMENT_TAG)?.let { return it }
        findViaStaticDriver()?.let { return it }
        val clisitefi = accessibleField(cliSiTef, "clisitefi")
            ?: accessibleField(cliSiTef, "cliSiTefI")
            ?: return null
        val pinpadCtrl = accessibleField(clisitefi, "pinpad") ?: return null
        return findPpFragmentDeep(pinpadCtrl)
    }

    private fun findViaStaticDriver(): Any? {
        return try {
            val ctrlClass = Class.forName("br.com.softwareexpress.sitef.android.PinPadCtrl")
            val driverField = ctrlClass.getDeclaredField("driver").apply { isAccessible = true }
            val driver = driverField.get(null) ?: return null
            findPpFragmentDeep(driver)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findPpFragmentDeep(root: Any, depth: Int = 0): Any? {
        if (depth > 5) return null
        if (root.javaClass.name.contains("PPFragment")) return root
        for (field in root.javaClass.declaredFields) {
            field.isAccessible = true
            val child = runCatching { field.get(root) }.getOrNull() ?: continue
            when {
                child.javaClass.name.contains("PPFragment") -> return child
                child.javaClass.name.contains("PPCompLib") -> {
                    accessibleField(child, "ppFragment")?.let { return it }
                }
                child.javaClass.name.contains("PinPad") ||
                    child.javaClass.name.contains("Driver") ||
                    child.javaClass.name.contains("PPComp") -> {
                    findPpFragmentDeep(child, depth + 1)?.let { return it }
                }
            }
        }
        var sc: Class<*>? = root.javaClass.superclass
        while (sc != null && sc != Any::class.java) {
            for (field in sc.declaredFields) {
                field.isAccessible = true
                val child = runCatching { field.get(root) }.getOrNull() ?: continue
                if (child.javaClass.name.contains("PPCompLib")) {
                    accessibleField(child, "ppFragment")?.let { return it }
                }
                if (child.javaClass.name.contains("PPFragment")) return child
            }
            sc = sc.superclass
        }
        return null
    }

    private fun accessibleField(owner: Any, name: String): Any? {
        val field = runCatching { owner.javaClass.getDeclaredField(name) }.getOrNull() ?: return null
        field.isAccessible = true
        return runCatching { field.get(owner) }.getOrNull()
    }

    private fun invokeEnabled(fragment: Any, enabled: Boolean) {
        runCatching {
            fragment.javaClass.methods.first { it.name == "setPinpadEnabled" }
                .invoke(fragment, enabled)
        }
    }

    private fun invokeSetKbd(fragment: Any): Boolean {
        return try {
            val gposClass = Class.forName(
                "br.com.softwareexpress.sitef.android.PPFragmentGpos700",
            )
            if (!gposClass.isInstance(fragment)) return false
            val access = runCatching {
                gposClass.getDeclaredMethod("access\$000", gposClass).apply { isAccessible = true }
            }.getOrNull()
            if (access != null) {
                access.invoke(null, fragment)
                return true
            }
            val setKbd = gposClass.getDeclaredMethod("setKbd").apply { isAccessible = true }
            setKbd.invoke(fragment)
            true
        } catch (error: Throwable) {
            Log.w(TAG, "invokeSetKbd", error)
            false
        }
    }

    private fun ensureFactoryService(activity: Activity) {
        try {
            val intent = android.content.Intent("br.com.gertec.service.FactoryService").setPackage(
                "br.com.gertec.factory_service",
            )
            val bound = activity.applicationContext.bindService(
                intent,
                factoryServiceConnection,
                Activity.BIND_AUTO_CREATE,
            )
            TefTraceLog.write(activity, "FactoryService bind=$bound")
        } catch (error: Throwable) {
            TefTraceLog.write(
                activity,
                "FactoryService FAIL ${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }

    private val factoryServiceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(
            name: android.content.ComponentName?,
            service: android.os.IBinder?,
        ) = Unit

        override fun onServiceDisconnected(name: android.content.ComponentName?) = Unit
    }

    private fun loadPinpadLib(activity: Activity, name: String) {
        try {
            System.loadLibrary(name)
            TefTraceLog.write(activity, "loadLibrary $name ok")
        } catch (error: Throwable) {
            TefTraceLog.write(
                activity,
                "loadLibrary $name FAIL ${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }
}
