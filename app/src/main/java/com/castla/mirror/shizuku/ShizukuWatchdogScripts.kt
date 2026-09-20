package com.castla.mirror.shizuku

/** Shell templates kept separate from binder lifecycle and UI recovery state. */
internal object ShizukuWatchdogScripts {
    fun inner(
        shizukuApk: String,
        libDir: String,
        innerPidFile: String,
        heartbeatFile: String,
    ): String = """
#!/bin/sh
trap '' HUP TERM INT QUIT
APK_PATH="$shizukuApk"
LIB_DIR="$libDir"
export LD_LIBRARY_PATH="${'$'}LIB_DIR"
echo ${'$'}${'$'} > $innerPidFile
echo -900 > /proc/${'$'}${'$'}/oom_score_adj 2>/dev/null

while true; do
    date +%s > $heartbeatFile 2>/dev/null
    if ! pidof shizuku_server > /dev/null 2>&1; then
        log -t shizuku_watchdog "server down, restarting"
        setsid nohup app_process -Djava.class.path="${'$'}APK_PATH" /system/bin \
            --nice-name=shizuku_server rikka.shizuku.server.ShizukuService \
            </dev/null >/dev/null 2>&1 &
        sleep 3
        for PID in ${'$'}(pidof shizuku_server 2>/dev/null); do
            echo -900 > /proc/${'$'}PID/oom_score_adj 2>/dev/null
        done
        sleep 12
    fi
    sleep 5
done
""".trimIndent()

    fun outer(
        innerScript: String,
        innerPidFile: String,
        outerPidFile: String,
    ): String = """
#!/bin/sh
trap '' HUP TERM INT QUIT
INNER=$innerScript
echo ${'$'}${'$'} > $outerPidFile
echo -900 > /proc/${'$'}${'$'}/oom_score_adj 2>/dev/null

while true; do
    ALIVE=0
    if [ -f $innerPidFile ]; then
        IPID=${'$'}(cat $innerPidFile 2>/dev/null)
        case "${'$'}IPID" in
            ''|*[!0-9]*) ;;
            *)
                if tr '\0' '\n' < /proc/${'$'}IPID/cmdline 2>/dev/null | grep -Fxq "$innerScript"; then
                    ALIVE=1
                fi
                ;;
        esac
    fi
    if [ ${'$'}ALIVE -eq 0 ]; then
        log -t shizuku_watchdog "inner down, respawning"
        setsid nohup sh "${'$'}INNER" </dev/null >/dev/null 2>&1 &
    fi
    sleep ${'$'}((5 + ${'$'}${'$'} % 5))
done
""".trimIndent()
}
