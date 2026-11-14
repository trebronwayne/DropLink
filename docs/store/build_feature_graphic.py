"""Renders feature-graphic.html to feature-graphic.png and .jpg at exactly 1024x500 (Google Play feature graphic).
Needs: pip install playwright pillow && playwright install chromium.  Run from this folder: python build_feature_graphic.py"""
import io, pathlib
from PIL import Image
from playwright.sync_api import sync_playwright

here = pathlib.Path(__file__).resolve().parent
with sync_playwright() as p:
    browser = p.chromium.launch()
    page = browser.new_page(viewport={"width": 1024, "height": 500}, device_scale_factor=2)  # render at 2x, then downsample for smooth edges
    page.goto((here / "feature-graphic.html").as_uri())
    page.wait_for_timeout(800)
    png = page.screenshot(clip={"x": 0, "y": 0, "width": 1024, "height": 500})
    browser.close()
image = Image.open(io.BytesIO(png)).convert("RGB").resize((1024, 500), Image.LANCZOS)
assert image.size == (1024, 500)
image.save(here / "feature-graphic.png", optimize=True)
image.save(here / "feature-graphic.jpg", quality=95, optimize=True, subsampling=0)
print("saved", image.size)
