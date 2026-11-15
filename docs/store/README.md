# Store assets

| File | What it is |
|---|---|
| `feature-graphic.png` / `.jpg` | Google Play feature graphic, exactly 1024 x 500 px (24-bit, no transparency). |
| `feature-graphic.html` | The source of the graphic: device frames drawn in CSS around the screenshots. Edit text, devices or screens here. |
| `build_feature_graphic.py` | Renders the HTML to the PNG and JPG. `pip install playwright pillow && playwright install chromium`, then `python build_feature_graphic.py`. |
| `screens/android-hosting.png` | Original screenshot of the hosting screen (720 x 1600). |
| `screens/android-hosting-store.png` | The same screenshot without the status bar, used in the phone frame. |
| `screens/desktop-grid-light.png`, `tv-player-dark.png`, `laptop-player-light.png`, `iphone-browse-light.png` | Web client screens shown on the desktop, TV, laptop and iPhone. They come from `docs/screenshots/` and use sample files. |

To change what a device shows, replace its image in `screens/` and run the build script again.
