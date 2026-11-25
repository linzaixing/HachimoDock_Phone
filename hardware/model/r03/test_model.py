import unittest
from itertools import combinations

import cadquery as cq

import build


class ClampTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.p = build.parameters()
        cls.parts = build.make_parts(cls.p)

    def test_printable_solids_and_reused_base(self):
        base = build.r02.make_parts(self.p)
        for name, part in self.parts.items():
            self.assertTrue(part.isValid(), name)
            self.assertEqual(len(part.Solids()), 1, name)
            self.assertEqual(len(part.Shells()), 1, name)
            if name in build.REUSED:
                self.assertEqual(build.r02.dimensions(part), build.r02.dimensions(base[name]))
                self.assertAlmostEqual(part.Volume(), base[name].Volume(), places=5)

    def test_jaws_center_phones_at_three_widths(self):
        for width in (62, 76, 90):
            fixed = build.placed(self.parts, self.p, width, references=True)
            phone = build.r02.phone_envelope(self.p, width)
            for name, shape in fixed.items():
                self.assertLess(shape.intersect(phone).Volume(), .01, (width, name))
            for name, sign in (("05_Jaw_Left", -1), ("06_Jaw_Right", 1)):
                vertex = cq.Vertex.makeVertex(sign*width/2, -12, 29)
                distance = fixed[name].distance(build.to_dock(vertex, self.p))
                self.assertAlmostEqual(distance, self.p["jaw_pad"], places=3)
        self.assertLess(build.CLOSED, 62)
        self.assertGreater(build.OPEN, 90)

    def test_clamp_motion_and_spring_cavity(self):
        for width in (60, 62, 67.1, 76, 80.2, 90, 94):
            installed = build.placed(self.parts, self.p, width, references=True)
            for (name_a, a), (name_b, b) in combinations(installed.items(), 2):
                self.assertLess(a.intersect(b).Volume(), .025, (width, name_a, name_b))
            self.assertEqual(build.spring_span(width), 22+(width-60)/2)

    def test_pinion_keeps_mesh_through_travel(self):
        for width in range(60, 95):
            gear, right, left = build.mesh_sections(width)
            for rack in (right, left):
                self.assertLess(gear.intersect(rack).Volume(), .01, width)
                self.assertLess(gear.distance(rack), .24, width)

    def test_stop_pins_capture_jaws_and_cable_port_remains_open(self):
        for width in (60, 94):
            installed = build.placed(self.parts, self.p, width, references=True)
            for side in ("Left", "Right"):
                jaw = installed[f"05_Jaw_{side}" if side == "Left" else f"06_Jaw_{side}"]
                for i in (1, 2):
                    pin = installed[f"REF_Stop_{side}_{i}"]
                    self.assertLess(jaw.distance(pin), .025, (width, side, i))
                    x0,y0,z0,x1,y1,z1 = build.r02.exact_bounds(build.hardware(width)[f"REF_Stop_{side}_{i}"])
                    self.assertGreater(y1, 4)
        for width in (62, 90, 94):
            installed = build.placed(self.parts, self.p, width, references=True)
            usb = build.to_dock(build.r02.block(-7.2, 7.2, -28, 7.75, 1.55, 12.25), self.p)
            for name in ("04_Wedge_45deg","05_Jaw_Left","06_Jaw_Right","10_Clamp_Cover","11_Clamp_Pinion"):
                solid = installed[name]
                self.assertLess(solid.intersect(usb).Volume(), .01, (width, name))
        shaft = build.r02.cylinder(7.35,self.p["base_height"]-13,
                                   build.r02.rotor_bottom(self.p)+7,0,build.r02.AXIS_Y)
        for name in ("02_Base_Lid","03_Rotor","07_Driven_48T"):
            self.assertLess(self.parts[name].intersect(shaft).Volume(), .01, name)

    def test_pin_and_spring_anchors_are_captured(self):
        selected = build.hardware(76)
        self.assertGreater(build.r02.exact_bounds(selected["REF_Pinion_Shaft"])[4], 4)
        jaw = self.parts["06_Jaw_Right"].translate((build.travel(76),0,0))
        moving = selected["REF_Moving_Eye_Right"]
        self.assertGreater(moving.translate((0,.45,0)).intersect(jaw).Volume(), .075)
        self.assertGreater(moving.translate((0,-.5,0)).intersect(
            self.parts["10_Clamp_Cover"]).Volume(), .075)

    def test_outer_guides_resist_reviewed_rocking_pose(self):
        wedge = self.parts["04_Wedge_45deg"]
        jaw = self.parts["06_Jaw_Right"].translate((build.travel(76),0,0))
        for angle in (-.94,.94):
            cocked = jaw.rotate((17.5,0,47.8),(17.5,1,47.8),angle)
            self.assertGreater(build.to_dock(cocked,self.p).intersect(wedge).Volume(), .025)


if __name__ == "__main__":
    unittest.main()
