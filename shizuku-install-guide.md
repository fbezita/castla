# Shizuku setup for Castla

Castla uses Shizuku to create trusted virtual displays, launch apps on those displays, inject input, and configure display IME behavior. Shizuku must be running before the Castla server starts.

## Install Shizuku

Install Shizuku from one of its official distribution channels:

- [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)
- [Official GitHub releases](https://github.com/RikkaApps/Shizuku/releases)

## Start with wireless debugging

The exact Settings labels vary by Android version and manufacturer.

1. Enable **Developer options** by tapping **Build number** seven times in the phone's software information screen.
2. Open **Developer options** and enable **Wireless debugging** while connected to Wi-Fi.
3. Open Shizuku and choose **Pairing** under wireless debugging.
4. In Android's **Wireless debugging** screen, choose **Pair device with pairing code**.
5. Enter the displayed code through the Shizuku pairing notification.
6. Return to Shizuku and tap **Start**.

Shizuku should report that the service is running.

## Grant Castla access

1. Open Castla.
2. Approve the Shizuku permission request.
3. Complete any remaining setup items shown by Castla.
4. Start the mirroring server.

After restarting the phone, Shizuku normally needs to be started again unless the device has a separate supported startup method.

## Troubleshooting

- If Castla reports that Shizuku is unavailable, open Shizuku and confirm that its service is running.
- If pairing fails, keep the phone connected to Wi-Fi, disable and re-enable wireless debugging, then pair again.
- If permission was denied, open Shizuku's authorized applications list and allow Castla.
- If Castla was reinstalled, open it once and approve Shizuku access again.

For current Shizuku instructions, use the [official user guide](https://shizuku.rikka.app/guide/setup/).
