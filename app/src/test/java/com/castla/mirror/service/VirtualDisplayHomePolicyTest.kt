package com.castla.mirror.service

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualDisplayHomePolicyTest {
    @Test
    fun `builds the home component with the active application id`() {
        val target = VirtualDisplayHomeTarget.forApplication("com.castla.mirror.client.debug")

        assertEquals("com.castla.mirror.client.debug", target.packageName)
        assertEquals("com.castla.mirror.ui.VirtualDisplayHomeActivity", target.className)
        assertEquals(
            "am start --display 18 -n com.castla.mirror.client.debug/com.castla.mirror.ui.VirtualDisplayHomeActivity",
            target.shellCommand(18),
        )
    }

    @Test
    fun `primes home beneath a remembered app only for a newly created display`() {
        assertEquals(true, VirtualDisplayHomePolicy.shouldPrimeBeforeRestore(true, "com.skt.tmap.ku"))
        assertEquals(false, VirtualDisplayHomePolicy.shouldPrimeBeforeRestore(false, "com.skt.tmap.ku"))
        assertEquals(false, VirtualDisplayHomePolicy.shouldPrimeBeforeRestore(true, ""))
        assertEquals(false, VirtualDisplayHomePolicy.shouldPrimeBeforeRestore(true, "HOME"))
    }

    @Test
    fun `launches standby home only when a remembered app leaves the display empty`() {
        assertEquals(true, VirtualDisplayHomePolicy.shouldLaunchForEmptyDisplay("com.skt.tmap.ku", emptyList()))
        assertEquals(false, VirtualDisplayHomePolicy.shouldLaunchForEmptyDisplay("com.skt.tmap.ku", listOf("task=11658")))
        assertEquals(false, VirtualDisplayHomePolicy.shouldLaunchForEmptyDisplay("HOME", emptyList()))
        assertEquals(false, VirtualDisplayHomePolicy.shouldLaunchForEmptyDisplay("", emptyList()))
        assertEquals(false, VirtualDisplayHomePolicy.shouldLaunchForEmptyDisplay("com.android.settings", emptyList()))
    }

    @Test
    fun `rearms home notification after an app returns above standby home`() {
        val monitor = VirtualDisplayHomeMonitor()
        val home = listOf(
            "com.castla.mirror.client.debug",
            "com.castla.mirror.client.debug/com.castla.mirror.ui.VirtualDisplayHomeActivity",
        )
        val app = listOf(
            "com.skt.tmap.ku",
            "com.skt.tmap.ku/com.skt.tmap.activity.TmapNewMainActivity",
        )

        assertEquals(VirtualDisplayHomeAction.NONE, monitor.evaluate(25, app.last(), app))
        assertEquals(VirtualDisplayHomeAction.REPORT_HOME, monitor.evaluate(25, app.last(), home))
        assertEquals(VirtualDisplayHomeAction.NONE, monitor.evaluate(25, "HOME", home))

        assertEquals(VirtualDisplayHomeAction.NONE, monitor.evaluate(25, "HOME", app + home))
        assertEquals(VirtualDisplayHomeAction.REPORT_HOME, monitor.evaluate(25, "HOME", home))
    }

    @Test
    fun `requests standby launch once an active app leaves a truly empty display`() {
        val monitor = VirtualDisplayHomeMonitor()
        val app = "com.skt.tmap.ku/com.skt.tmap.activity.TmapNewMainActivity"

        assertEquals(VirtualDisplayHomeAction.NONE, monitor.evaluate(25, app, listOf(app)))
        assertEquals(VirtualDisplayHomeAction.LAUNCH_HOME, monitor.evaluate(25, app, emptyList()))
        monitor.markHomeReported(25)
        assertEquals(VirtualDisplayHomeAction.NONE, monitor.evaluate(25, "HOME", emptyList()))
    }
}
