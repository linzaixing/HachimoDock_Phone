import argparse
import json
from itertools import combinations
from pathlib import Path

import cadquery as cq
import trimesh

import build


def verify(config, out):
    parts = build.make_parts(config)
    wedge_offset = cq.Vector(0, 0, build.lip_parameters(config))
    for name, solid in parts.items():
        assert solid.isValid() and len(solid.Solids()) == 1, name
    servo = build.servo_body_envelope(config, config["servo_clearance"])
    assert servo.intersect(parts["01_Base"]).Volume() < .025, "Servo mount obstructs body"
    widths = sorted({config[key] for key in ("width_min", "phone_width", "width_max")})
    moving_names = ("03_Rotor","04_Wedge_45deg","05_Jaw_Left","06_Jaw_Right",
                    "07_Driven_48T","09_Shaft_Spacer")
    for width in widths:
        installed = build.placed(parts, config, width)
        for (name_a, a), (name_b, b) in combinations(installed.items(), 2):
            if a.intersect(b).Volume() > .025:
                raise AssertionError(f"{width:g} mm: {name_a} intersects {name_b}")
        phone = build.phone_envelope(config, width)
        for name, piece in installed.items():
            assert phone.intersect(piece).Volume() < .025, (width, name)
        pads = (build.phone_plane(cq.Vector(-width/2, -12, 32)),
                build.phone_plane(cq.Vector(width/2, -12, 32)))
        jaws = (installed["05_Jaw_Left"], installed["06_Jaw_Right"])
        for point, jaw in zip(pads, jaws):
            separation = jaw.distance(cq.Vertex.makeVertex(*(point + wedge_offset).toTuple()))
            assert abs(separation - config["jaw_pad"]) < .03, (width, separation)
        screw_x = build.jaw_screw_x(config)
        for point, jaw in zip((-screw_x, screw_x), jaws):
            screw = build.slope(build.cylinder(
                1.49, -8, 6, point, 34 + config["slide_fit"], axis="y"
            )).translate(wedge_offset)
            assert screw.intersect(jaw).Volume() < .025, (width, point, "Width slot blocks M3 screw")
        for yaw in (-40, -20, 20, 40):
            for moving in moving_names:
                rotated = installed[moving].rotate(
                    (0,build.AXIS_Y,0), (0,build.AXIS_Y,1), yaw
                )
                for fixed in set(parts)-set(moving_names):
                    assert rotated.intersect(parts[fixed]).Volume() < .025, (width,yaw,moving,fixed)

    center = build.AXIS_Y
    path = build.cylinder(config["cable_bore"]/2-.15, config["base_height"]-13,
                          build.rotor_bottom(config)+7, 0, center)
    for name in ("02_Base_Lid", "03_Rotor", "07_Driven_48T"):
        assert path.intersect(parts[name]).Volume() < .025, name
    assert abs(parts["03_Rotor"].BoundingBox().xlen - config["rotor_diameter"]) < .025
    for path in sorted((out / "parts-step").glob("*.step")):
        solid = cq.importers.importStep(str(path)).val()
        assert solid.isValid() and len(solid.Solids()) == 1, path
        expected = build.print_pose(path.stem, parts[path.stem])
        assert abs(solid.Volume()-expected.Volume()) < .02, (path, "Stale STEP volume")
        assert max(abs(a-b) for a,b in zip(
            build.dimensions(solid), build.dimensions(expected)
        )) < .01, (path, "Stale STEP dimensions")
    for path in sorted((out / "print-stl").glob("*.stl")):
        mesh = trimesh.load_mesh(path)
        assert mesh.is_watertight and mesh.is_winding_consistent and mesh.is_volume, path
        assert len(mesh.split()) == 1 and abs(mesh.bounds[0][2]) < .02, path
        expected = build.print_pose(path.stem, parts[path.stem])
        assert max(abs(a-b) for a,b in zip(
            mesh.extents, build.dimensions(expected)
        )) < .1, (path, "Stale STL dimensions")
        assert abs(mesh.volume-expected.Volume()) / expected.Volume() < .015, (path, "STL volume")
    for dirname, ext in (("parts-step", "step"), ("print-stl", "stl")):
        assert {p.stem for p in (out/dirname).glob(f"*.{ext}")} == set(parts), dirname
    model = cq.importers.importStep(str(out/"HachimoDock-R02.step")).val()
    assert model.isValid() and len(model.Solids()) == len(parts)
    manifest = json.loads((out/"inventory.json").read_text())
    assert manifest["parameters"] == config and manifest["units"] == "mm"
    assert set(manifest["parts"]) == set(parts)
    assert {item["phone_width_mm"] for item in manifest["assemblies"]} == set(widths)
    for assembly in manifest["assemblies"]:
        solid = cq.importers.importStep(str(out/assembly["step"])).val()
        expected = cq.Compound.makeCompound(
            list(build.placed(parts, config, assembly["phone_width_mm"]).values())
        )
        assert solid.isValid() and len(solid.Solids()) == len(parts)
        assert abs(solid.Volume()-expected.Volume()) < .05, assembly
        assert max(abs(a-b) for a,b in zip(
            build.dimensions(solid), build.dimensions(expected)
        )) < .01, assembly
    preview = trimesh.load(out/"HachimoDock-R02.glb", force="scene")
    assert len(preview.geometry) == len(parts)
    assert abs(preview.extents[0]*1000-config["base_width"]) < .1
    assert abs(preview.extents[2]*1000-config["base_depth"]) < .1
    print(f"{len(parts)} solids; STEP/STL match source; {len(widths)} clamp widths; "
          "yaw samples -40/-20/0/20/40 deg passed; configuration and GLB exports checked.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=Path("dimensions.json"))
    parser.add_argument("--out", type=Path, default=Path("model"))
    args = parser.parse_args()
    verify(build.load(args.config), args.out)
