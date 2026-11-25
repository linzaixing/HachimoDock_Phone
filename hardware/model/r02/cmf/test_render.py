import json
import unittest
from pathlib import Path

import bpy

import render


ROOT = Path(__file__).resolve().parent
MODEL = ROOT.parent/"model"


class CmfTests(unittest.TestCase):
    def setUp(self):
        bpy.ops.wm.read_factory_settings(use_empty=True)

    def test_palettes_cover_every_part(self):
        names = json.loads((MODEL/"inventory.json").read_text())["parts"]
        for palette in ("silver", "graphite"):
            for name in names:
                spec = render.finish_spec(name, palette)
                self.assertGreaterEqual(spec["roughness"], .25)
                self.assertLessEqual(spec["metallic"], 1)
            self.assertEqual(render.finish_spec("05_Jaw_Left", palette),
                             render.finish_spec("06_Jaw_Right", palette))

    def test_material_assignment_preserves_cad_meshes(self):
        parts = render.import_dock(MODEL)
        self.assertEqual(len(parts), 9)
        before = {name: [(tuple(v.co)) for v in obj.data.vertices]
                  for name, obj in parts.items()}
        render.assign_finish(parts, "silver")
        after = {name: [(tuple(v.co)) for v in obj.data.vertices]
                 for name, obj in parts.items()}
        self.assertEqual(before, after)
        for obj in parts.values():
            self.assertEqual(len(obj.data.materials), 1)

    def test_finished_camera_frames_all_cad_parts(self):
        parts = render.import_dock(MODEL)
        render.assign_finish(parts, "silver")
        scene, camera = render.setup_studio(900, 1200, 4)
        render.frame_camera(camera, parts.values(), (340, -500, 315), margin=1.18)
        bounds = render.camera_bounds(scene, camera, parts.values())
        self.assertGreater(bounds[0], .02)
        self.assertLess(bounds[2], .98)
        self.assertGreater(bounds[1], .02)
        self.assertLess(bounds[3], .98)


if __name__ == "__main__":
    unittest.main()
