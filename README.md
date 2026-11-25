# Buds Popup

An AirPods-style battery card for the Galaxy Buds4 Pro on Android. Open the case and a card slides up from the bottom of the screen: a 3D case opens its lid, the two buds come out, settle on each side and keep spinning, with the left, right and case battery levels under them.

Personal project, not on the Play Store.

## How it works

1. Opening the case reconnects the buds. A manifest receiver catches `ACL_CONNECTED` for the chosen buds.
2. It starts a short-lived foreground service (`connectedDevice`). A companion device association lets it start from the background.
3. The service opens RFCOMM on the buds' SPP service (`2e73a4ad-332d-41fc-90e2-16bef06523f2`) and reads the status messages (`0x60` and `0x61`). Those carry the battery levels, where each bud is (worn, idle, in the case) and the colour.
4. While a bud is in the case, an overlay window shows the card: Jetpack Compose for the card, Filament for the 3D model.
5. The card goes away when a bud is worn, when the case closes, when you swipe it down or tap outside it, or after 60 s.

The model is white or black, picked from the colour the buds report.

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

- 3D model: ["SUMSUNG Buds 4 Pro"](https://sketchfab.com/3d-models/none-67456ae938ac40d8999b27266a59189d) by [zhe_kan](https://sketchfab.com/zhe_kan), licensed [CC BY-NC-SA 4.0](http://creativecommons.org/licenses/by-nc-sa/4.0/). In `buds.glb` the logo is removed, the earbuds are split, the lid pivots on its hinge and the animation is added. `model/` and `buds.glb` keep the same license.
- Lighting: [Studio Small 09](https://polyhaven.com/a/studio_small_09) from Poly Haven (CC0), turned into `studio_ibl.ktx` with Filament's `cmgen`.
- Message layout: [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient) docs and test captures. No code was copied from it.

Galaxy Buds is a Samsung trademark. This app is not affiliated with Samsung.
