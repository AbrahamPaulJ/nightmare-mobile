# Install and check your phone

## Get the app

Nightmare is not on the Play Store. It is installed from its GitHub releases page:

1. On your phone, open **[the latest release](https://github.com/AbrahamPaulJ/nightmare-mobile/releases/latest)**.
2. Under **Assets**, tap `nightmare-<version>.apk` to download it.
3. Open the downloaded file. Android will ask you to allow installs from your browser or file
   manager the first time — allow it, go back, and tap **Install**.

!!! tip "Updating"
    A newer APK installs straight over the old one. Your models, saved flows and results are
    kept. **Do not uninstall first** — uninstalling deletes everything stored inside the app,
    including downloaded models (unless you moved them to `Download/Nightmare`, see
    [Settings](../reference/settings.md#models-folder)).

## Check what your phone can run

Tap the **ⓘ** button at the top right of the main screen. It shows your chip, its NPU
generation ("HTP arch", for example v79 for a Snapdragon 8 Elite) and its fast on-chip memory
("VTCM").

You rarely need these numbers yourself: every model row in **Models** and every card in
**Flows** already says when your phone cannot run it, and why. The table on the
[home page](../index.md#what-your-phone-needs) is the short version.

!!! info "A chip the app does not recognise"
    A brand-new Snapdragon may not be in the app's tables yet. The app then offers you
    everything rather than hiding it, and measures the chip when you open the ⓘ panel. If a
    model then fails to start, that is the reason — please report the chip name.

## Let it run in the background

A render can take from a few seconds to several minutes. Some phones (Samsung especially) stop
apps that are not on screen to save battery, which ends the render.

If Settings → General shows a card titled **Keep rendering in the background**, tap
**Allow in background**. That is all it needs; the card disappears once it is allowed.

## Next

[Your first picture →](first-picture.md)
