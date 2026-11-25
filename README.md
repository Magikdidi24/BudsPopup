# Buds Popup

An AirPods-style battery card for the Galaxy Buds4 Pro on Android, laid out like the iPhone one. Open the case and a card slides up from the bottom of the screen with a 3D case that opens its lid, turning slowly like on a turntable, with the case's battery level. Take a bud out and it rises from the case and joins the pair on the left while the case slides right, with its own battery level under it. Put it back and it flies back into its slot. Each bud moves on its own.

It also has a card for Sony WH-1000XM4 headphones: when they connect, the headphones turn in 3D with their battery ring under them, like the AirPods Max card.

Personal project, not on the Play Store. Tested with Galaxy Buds4 Pro on a Galaxy S25+ (Android 17, One UI 9). It needs Android 12 or later. The message offsets and the 3D model are for the Buds4 Pro, so other Galaxy Buds would need changes.

## How it works

1. Opening the case reconnects the buds. A manifest receiver catches `ACL_CONNECTED` for the chosen buds.
2. It starts a short-lived foreground service (`connectedDevice`). A companion device association lets it start from the background.
3. The service opens RFCOMM on the buds' SPP service (`2e73a4ad-332d-41fc-90e2-16bef06523f2`) and reads the status messages (`0x60` and `0x61`). Those carry the battery levels, where each bud is (worn, idle, in the case) and the colour.
4. While a bud is in the case, an overlay window shows the card: Jetpack Compose for the card, Filament for the 3D model.
5. The card goes away when the case closes with the buds inside (the buds disconnect), 1 s after both buds are in your ears (an empty case closing sends nothing, so that is the cue), 30 s after both buds are out but not worn, when you swipe it down or tap outside it, or after 5 min.

The model is white or black, picked from the colour the buds report. The case's ring always shows; a bud's ring shows once that bud is out. Rings go green, orange then red as the battery drops, with a bolt inside while charging.

On Samsung phones, Samsung's own buds manager wants the same SPP channel and only one app can hold it. It tries three times in the first ~6 s, and each try drops our link before it gives up. The service takes the link back 0.3 s after each drop while the card is up. Until the next time the buds reconnect, Samsung's manager does not have the link.

## Known limits

- The card is an overlay window, so it does not show on the lock screen.
- Samsung's own buds popup still appears next to this one.
- While the card is up, Samsung's buds manager has no SPP link, so the Buds settings in One UI are not live until the buds reconnect.
- An empty case closing sends nothing. If the buds are out but never worn, the card stays up to 30 s unless you swipe it away.

## Build

```bash
./gradlew installDebug   # phone plugged in with USB debugging
./gradlew test lint
adb logcat -s BudsPopup
```

In the app, grant the permissions, pick the buds in the list and accept the pairing prompt. The "Blanc" and "Noir" buttons under "Tester la popup" play a scripted card with fake values: the left bud comes out, then the right one, then both go back.

To rebuild the animated model after changing `tools/build_model.py` (Blender 5.3):

```bash
blender -b --factory-startup --python tools/build_model.py -- \
    model/buds4pro_source.glb app/src/main/assets/buds.glb
```

## Credits

- 3D model: ["SUMSUNG Buds 4 Pro"](https://sketchfab.com/3d-models/none-67456ae938ac40d8999b27266a59189d) by [zhe_kan](https://sketchfab.com/zhe_kan), licensed [CC BY-NC-SA 4.0](http://creativecommons.org/licenses/by-nc-sa/4.0/). In `model/` the logo is removed. In `buds.glb` the earbuds are also split, the lid pivots on its hinge and the case and lid animation is added. `model/` and `buds.glb` keep the same license.
- Lighting: [Studio Small 09](https://polyhaven.com/a/studio_small_09) from Poly Haven (CC0), turned into `studio_ibl.ktx` with Filament's `cmgen`.
- Headphones model: ["Sony WH-1000XM4 - Black and brown"](https://sketchfab.com/3d-models/sony-wh-1000xm4-black-and-brown-18e9aa472d884f95897a3e70ee38026b) by [La_Grekula](https://sketchfab.com/La_Grekula), licensed [CC BY 4.0](http://creativecommons.org/licenses/by/4.0/). In `headphones.glb` the logos are removed, the mesh is lighter and the leather is black. `tools/build_headphones.py` builds it from the downloaded source, which is not in the repo (30 MB).
- Message layout: [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient) docs and test captures for the buds, [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) for the Sony headphones. No code was copied from either.

Galaxy Buds is a Samsung trademark and WH-1000XM4 a Sony one. This app is not affiliated with either.
