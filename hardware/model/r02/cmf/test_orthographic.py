import unittest

import cadquery as cq
from PIL import ImageChops

import orthographic


class ProjectionTests(unittest.TestCase):
    def test_front_projection_keeps_base_below_tower(self):
        base = cq.Workplane("XY").box(120, 70, 20, centered=(True, True, False)).val()
        tower = cq.Workplane("XY").box(30, 30, 90, centered=(True, True, False)).val()
        image = orthographic.line_view(base.fuse(tower), (0, -1, 0), upright=True)
        top = image.crop((0, 80, image.width, 130))
        bottom = image.crop((0, image.height-100, image.width, image.height-40))
        white_top = ImageChops.difference(top, top.copy().point(lambda _: 255))
        white_bottom = ImageChops.difference(bottom, bottom.copy().point(lambda _: 255))
        self.assertGreater(white_bottom.getbbox()[2]-white_bottom.getbbox()[0],
                           white_top.getbbox()[2]-white_top.getbbox()[0])

    def test_equal_scale_is_independent_of_shape_dimensions(self):
        sizes = []
        for width in (50, 100):
            shape = cq.Workplane("XY").box(width, 20, 60).val()
            image = orthographic.line_view(shape, (0, -1, 0), upright=True)
            sizes.append(image.width)
        self.assertLess(abs((sizes[1]-sizes[0])-50*3.15), 2)


if __name__ == "__main__":
    unittest.main()
