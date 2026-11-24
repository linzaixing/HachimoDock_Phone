import json
import tempfile
import unittest
from pathlib import Path

import cadquery as cq
import trimesh

import build


ROOT = Path(__file__).resolve().parent


class ServoClearanceTests(unittest.TestCase):
    def test_servo_body_has_clearance_below_mounting_ears(self):
        p = build.load(ROOT / "dimensions.json")
        cx = p["servo_center"]
        body = build.block(cx-4.5-.35, cx+18.5+.35,
                           build.AXIS_Y-6.3-.35, build.AXIS_Y+6.3+.35,
                           3.8, p["base_height"]-21)
        collision = body.intersect(build.make_base(p)).Volume()
        self.assertLess(collision, .025, f"Servo clearance overlaps base by {collision:g} mm3")


class DimensionTests(unittest.TestCase):
    def test_dimensions_do_not_change_after_preview_tessellation(self):
        part = build.cylinder(12.345, 0, 10)
        expected = build.dimensions(part)
        part.tessellate(.06, .15)
        self.assertEqual(build.dimensions(part), expected)


class ClampTravelTests(unittest.TestCase):
    def test_wide_travel_jaws_do_not_cross_at_minimum_width(self):
        p = build.load(ROOT/"dimensions-example.json")
        right = build.make_jaw(p)
        left = build.jaw_position(right.mirror("YZ"), p, p["width_min"], -1)
        right = build.jaw_position(right, p, p["width_min"], 1)
        self.assertLess(left.intersect(right).Volume(), .025)


class ExportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = build.load(ROOT / "dimensions.json")
        cls.directory = tempfile.TemporaryDirectory()
        cls.out = Path(cls.directory.name)
        build.export(cls.config, cls.out)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def test_manifest_identifies_units_parts_and_configuration(self):
        manifest = json.loads((self.out / "inventory.json").read_text())
        self.assertEqual(manifest["units"], "mm")
        self.assertEqual(manifest["parameters"], self.config)
        self.assertEqual(set(manifest["parts"]), set(build.make_parts(self.config)))
        for item in manifest["parts"].values():
            self.assertGreater(item["volume_mm3"], 0)
            self.assertEqual(len(item["print_dimensions_mm"]), 3)
            self.assertTrue((self.out / item["step"]).is_file())
            self.assertTrue((self.out / item["stl"]).is_file())

    def test_clamp_position_assemblies_have_all_parts(self):
        manifest = json.loads((self.out / "inventory.json").read_text())
        widths = {self.config[key] for key in ("width_min", "phone_width", "width_max")}
        self.assertEqual({item["phone_width_mm"] for item in manifest["assemblies"]}, widths)
        for item in manifest["assemblies"]:
            shape = cq.importers.importStep(str(self.out / item["step"])).val()
            self.assertEqual(len(shape.Solids()), 9)
            self.assertTrue(shape.isValid())

    def test_glb_contains_real_parts_in_meters(self):
        scene = trimesh.load(self.out / "HachimoDock-R02.glb", force="scene")
        self.assertEqual(len(scene.geometry), 9)
        # GLB uses Y-up meters; CAD uses Z-up millimeters.
        self.assertAlmostEqual(scene.extents[0], self.config["base_width"] / 1000, places=5)
        self.assertAlmostEqual(scene.extents[2], self.config["base_depth"] / 1000, places=5)


if __name__ == "__main__":
    unittest.main()
