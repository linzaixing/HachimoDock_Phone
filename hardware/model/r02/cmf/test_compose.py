import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageColor

from compose import BG, picture


class CompositionTests(unittest.TestCase):
    def test_render_edges_blend_into_board_without_changing_center(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "render.png"
            Image.new("RGBA", (200, 160), "#3A3C42").save(path)
            image = picture(path)
        background = ImageColor.getrgb(BG)
        self.assertEqual(image.getpixel((0, 80)), background)
        self.assertEqual(image.getpixel((199, 80)), background)
        self.assertEqual(image.getpixel((100, 0)), background)
        self.assertEqual(image.getpixel((100, 159)), background)
        self.assertEqual(image.getpixel((100, 80)), (58, 60, 66))


if __name__ == "__main__":
    unittest.main()
