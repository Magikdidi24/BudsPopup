# Buds Popup

An AirPods-style battery card for the Galaxy Buds4 Pro on Android, laid out like the iPhone one. Open the case and a card slides up from the bottom of the screen with a 3D case that opens its lid, turning slowly like on a turntable, with the case's battery level. Take a bud out and it rises from the case and joins the pair on the left while the case slides right, with its own battery level under it. Put it back and it flies back into its slot. Each bud moves on its own.

Personal project, not on the Play Store.

## How it works

1. Opening the case reconnects the buds. A manifest receiver catches `ACL_CONNECTED` for the chosen buds.
2. It starts a short-lived foreground service (`connectedDevice`). A companion device association lets it start from the background.
3. The service opens RFCOMM on the buds' SPP service (`2e73a4ad-332d-41fc-90e2-16bef06523f2`) and reads the status messages (`0x60` and `0x61`). Those carry the battery levels, where each bud is (worn, idle, in the case) and the colour.
4. While a bud is in the case, an overlay window shows the card: Jetpack Compose for the card, Filament for the 3D model.
5. The card goes away when the case closes with the buds inside (the buds disconnect), 4 s after both buds are out (an empty case closing sends nothing), when you swipe it down or tap outside it, or after 5 min.

The model is white or black, picked from the colour the buds report. Rings go green, orange then red as the battery drops, with a bolt while charging.

On Samsung phones, Samsung's own buds manager wants the same SPP channel and only one app can hold it. It tries three times in the first ~6 s, and each try drops our link before it gives up. The service takes the link back 0.3 s after each drop while the card is up. Until the next time the buds reconnect, Samsung's manager does not have the link.

## Build

```bash
./gradlew installDebug   # phone plugged in with USB debugging
./gradlew test lint
adb logcat -s BudsPopup
```

In the app, grant the permissions, pick the buds in the list and accept the pairing prompt. The two "Tester" buttons show the card with fake values.

To rebuild the animated model after changing `tools/build_model.py` (Blender 5.3):

```bash
blender -b --factory-startup --python tools/build_model.py -- \
    model/buds4pro_source.glb app/src/main/assets/buds.glb
```

## Credits

- 3D model: ["SUMSUNG Buds 4 Pro"](https://sketchfab.com/3d-models/none-67456ae938ac40d8999b27266a59189d) by [zhe_kan](https://sketchfab.com/zhe_kan), licensed [CC BY-NC-SA 4.0](http://creativecommons.org/licenses/by-nc-sa/4.0/). In `model/` the logo is removed. In `buds.glb` the earbuds are also split, the lid pivots on its hinge and the case and lid animation is added. `model/` and `buds.glb` keep the same license.
- Lighting: [Studio Small 09](https://polyhaven.com/a/studio_small_09) from Poly Haven (CC0), turned into `studio_ibl.ktx` with Filament's `cmgen`.
- Message layout: [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient) docs and test captures. No code was copied from it.

Galaxy Buds is a Samsung trademark. This app is not affiliated with Samsung.
