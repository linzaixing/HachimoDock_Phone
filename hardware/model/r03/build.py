"""R03 spring clamp, built on the unmodified R02 bottom assembly."""

import argparse
import importlib.util
import json
import math
from pathlib import Path

import cadquery as cq
import trimesh

ROOT = Path(__file__).resolve().parent
R02 = ROOT.parent / "r02"
spec = importlib.util.spec_from_file_location("r02_cad", R02 / "build.py")
r02 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r02)

CLOSED, OPEN = 60, 94
WIDTHS = (CLOSED, 62, 76, 90, OPEN)
REUSED = ("01_Base", "02_Base_Lid", "03_Rotor", "07_Driven_48T", "08_Servo_24T", "09_Shaft_Spacer")
MODULE, TEETH, PRESSURE = .8, 20, math.radians(20)
PITCH_RADIUS = MODULE * TEETH / 2
PITCH = math.pi * MODULE
GEAR_Z = 36
FRONT, BACK = -3.6, 1.15
STOP_X, STOP_Z = (7, 28), 47.8
SPRING_Z = 54
FIXED_EYE_X, MOVING_EYE_X, SPRING_Y = -15, 7, -1.2
COVER_POSTS = ((-24,32),(24,32),(-24,40),(24,40))
RACK_EDGE = 50.55


def parameters():
    return r02.load(R02 / "dimensions.json")


def travel(width):
    if not CLOSED <= width <= OPEN:
        raise ValueError(f"Gap must be in {CLOSED}–{OPEN} mm")
    return (width-CLOSED)/2


def spring_span(width):
    return MOVING_EYE_X - FIXED_EYE_X + travel(width)


def to_dock(part, p):
    return r02.slope(part).translate((0, 0, r02.lip_parameters(p)))


def opposite(part):
    return part.rotate((0, 0, GEAR_Z), (0, 1, GEAR_Z), 180)


def xz_profile(points, y0=FRONT, y1=BACK):
    return cq.Workplane("XZ", origin=(0, y1, 0)).polyline(points).close().extrude(y1-y0).val()


def pinion():
    root, tip = PITCH_RADIUS-1.25*MODULE, PITCH_RADIUS+MODULE
    base = PITCH_RADIUS*math.cos(PRESSURE)
    pitch_inv = math.tan(PRESSURE)-PRESSURE
    radii = [base, *(base+(tip-base)*i/8 for i in range(1, 9))]
    flank = []
    for radius in radii:
        t = math.sqrt((radius/base)**2-1)
        flank.append((radius, math.pi/(2*TEETH)+pitch_inv-(t-math.atan(t))))
    flank.insert(0, (root, flank[0][1]+.015))
    points = []
    for i in range(TEETH):
        angle = math.tau*i/TEETH
        for radius, offset in [*((r,-a) for r,a in flank), *reversed(flank)]:
            a = angle+offset
            points.append((radius*math.sin(a), GEAR_Z+radius*math.cos(a)))
    return xz_profile(points).cut(r02.cylinder(1.63, FRONT-.1, BACK+.1, 0, GEAR_Z, "y")).clean()


def right_rack():
    pitch_z = GEAR_Z + PITCH_RADIUS
    half = PITCH/4 - .12
    a = half + math.tan(PRESSURE)*MODULE*1.25
    b = half - math.tan(PRESSURE)*MODULE
    root_z, tip_z = pitch_z+1.25*MODULE, pitch_z-MODULE
    # At CLOSED a tooth of the pinion sits in the rack gap at x=0.
    xs = [(i+.5)*PITCH for i in range(-12, 12)]
    edge = [(xs[0]-a-.35, root_z)]
    for x in xs:
        edge.extend(((x-a,root_z),(x-b,tip_z),(x+b,tip_z),(x+a,root_z)))
    edge.extend(((35.8,root_z),(35.8,RACK_EDGE),(xs[0]-a-.35,RACK_EDGE)))
    rack = xz_profile(edge)
    cuts = [r02.rslot(x-17+.175,x-.175,STOP_Z,1.675,FRONT-.1,BACK+.1) for x in STOP_X]
    return rack.cut(*cuts).clean()


def right_upper():
    return right_rack().fuse(
        r02.block(MOVING_EYE_X-1.9, MOVING_EYE_X+1.9, FRONT, -2.85, 50.0, SPRING_Z+1.6)
    ).cut(r02.cylinder(.85, FRONT-.1, BACK+.1, MOVING_EYE_X, SPRING_Z, "y")).clean()


def toe(p):
    x = CLOSED/2 + p["jaw_pad"]
    y = -(7+p["phone_thickness_max"])
    return r02.block(x,x+5,y,-.2,17,34).fuse(
        r02.block(x-12,x+5,y-3,-3,12.7,17.5),
        r02.block(x-12,x+5,y-3,y,17.1,21.7),
    ).clean()


def jaws(p):
    x = CLOSED/2 + p["jaw_pad"]
    right_base = toe(p).cut(r02.block(x-.1,x+2,-5.81,.1,16.9,34.1),
                            r02.block(x-.1,x+5.1,-2.95,.1,19.85,21.5))
    right = right_upper().fuse(right_base, r02.block(x+2.05,x+4.8,-3.6,-.25,31,48.5)).clean()
    left = opposite(right_upper()).fuse(right_base.mirror("YZ"),
            r02.block(-x-4.8,-x-2.05,-3.6,-.25,26.5,33)).clean()
    return left, right


def wedge(p):
    def local(part):
        return to_dock(part, p)

    body = r02.make_wedge(p).fuse(*(
        local(r02.cylinder(1.7, 1.35, 7.15, sign*r02.jaw_screw_x(p),
                           34+p["slide_fit"], "y"))
        for sign in (-1,1)
    ))
    body = body.cut(local(r02.block(-37.1,37.1,-5.9,1.5,15.1,57.4)))
    back = r02.block(-37,37,1.5,5.3,15.1,57.4)
    # The long slide faces limit yaw independently of tooth contact.
    guide = r02.block(-36.5,36.5,-2.72,1.8,RACK_EDGE+.18,RACK_EDGE+1.35)
    bores = []
    for x in STOP_X:
        pilot = r02.cylinder(1.15,1.49,5.12,x,STOP_Z,"y")
        bores.extend((pilot,opposite(pilot)))
    for x,z in COVER_POSTS:
        bores.append(r02.cylinder(.95,1.49,5.1,x,z,"y"))
    for x,z in ((FIXED_EYE_X,SPRING_Z),(-FIXED_EYE_X,2*GEAR_Z-SPRING_Z)):
        bores.append(r02.cylinder(.85,1.45,5.1,x,z,"y"))
    bores.append(r02.cylinder(1.2,1.49,5.12,0,GEAR_Z,"y"))
    return body.fuse(local(back),local(guide),local(opposite(guide))).cut(
        *(local(bore) for bore in bores)).clean()


def cover():
    cap = r02.block(-32,32,-5.8,-4,15.1,57.4)
    for x0,x1 in ((-32.1,-18.7),(18.7,32.1)):
        cap = cap.cut(r02.block(x0,x1,-5.9,-3.9,15,17.85))
    for x,z in COVER_POSTS:
        cap = cap.fuse(r02.cylinder(1.9,-4.1,1.5,x,z,"y"))
        countersink = cq.Solid.makeCone(1.95,1.06,.99,cq.Vector(x,-5.8,z),cq.Vector(0,1,0))
        cap = cap.cut(r02.cylinder(1.08,-5.9,1.61,x,z,"y"),countersink)
    return cap.clean()


def hardware(width):
    pieces = {}
    for i,x in enumerate(STOP_X):
        stem = r02.cylinder(1.5,-3.87,1.49,x,STOP_Z,"y").fuse(
            r02.cylinder(1.12,1.46,4.6,x,STOP_Z,"y"))
        pieces[f"REF_Stop_Right_{i+1}"] = stem
        pieces[f"REF_Stop_Left_{i+1}"] = opposite(stem)
    pieces["REF_Pinion_Shaft"] = r02.cylinder(1.59,-3.85,1.49,0,GEAR_Z,"y").fuse(
        r02.cylinder(1.17,1.46,4.6,0,GEAR_Z,"y"))
    for i,(x,z) in enumerate(COVER_POSTS,1):
        head = cq.Solid.makeCone(1.86,1.0,.86,cq.Vector(x,-5.75,z),cq.Vector(0,1,0))
        pieces[f"REF_Cover_Screw_{i}"] = head.fuse(r02.cylinder(.91,-4.92,4.6,x,z,"y"))
    # Cylinders describe the moving coil envelopes, not the hooks or the spring force.
    end = MOVING_EYE_X + travel(width)
    spring = cq.Solid.makeCylinder(1.6, spring_span(width)-4.4,
                 cq.Vector(FIXED_EYE_X+2.2,SPRING_Y,SPRING_Z), cq.Vector(1,0,0))
    pieces["REF_Spring_Right"] = spring
    pieces["REF_Spring_Left"] = opposite(spring)
    for name,x in (("Fixed",FIXED_EYE_X),("Moving",end)):
        if name == "Fixed":
            peg = r02.cylinder(.8,-2.7,3.2,x,SPRING_Z,"y").fuse(
                r02.cylinder(1.35,-2.8,-2.3,x,SPRING_Z,"y"))
        else:
            peg = r02.cylinder(.8,-3.82,.7,x,SPRING_Z,"y").fuse(
                r02.cylinder(1.2,-3.9,-3.61,x,SPRING_Z,"y"),
                r02.cylinder(1.35,.45,.8,x,SPRING_Z,"y"))
        pieces[f"REF_{name}_Eye_Right"] = peg
        pieces[f"REF_{name}_Eye_Left"] = opposite(peg)
    return pieces


def make_parts(p):
    base = r02.make_parts(p)
    left, right = jaws(p)
    return {**{name:base[name] for name in REUSED},
            "04_Wedge_45deg":wedge(p), "05_Jaw_Left":left, "06_Jaw_Right":right,
            "10_Clamp_Cover":cover(), "11_Clamp_Pinion":pinion()}


def gear_at(shape, width):
    return shape.rotate((0,0,GEAR_Z),(0,1,GEAR_Z),math.degrees(travel(width)/PITCH_RADIUS))


def mesh_sections(width):
    rack = right_rack().translate((travel(width),0,0))
    return gear_at(pinion(),width), rack, opposite(rack)


def placed(parts, p, width, references=False):
    shift = travel(width)
    result = {name:parts[name] for name in (*REUSED, "04_Wedge_45deg")}
    result.update({
        "05_Jaw_Left": to_dock(parts["05_Jaw_Left"].translate((-shift,0,0)),p),
        "06_Jaw_Right": to_dock(parts["06_Jaw_Right"].translate((shift,0,0)),p),
        "10_Clamp_Cover": to_dock(parts["10_Clamp_Cover"],p),
        "11_Clamp_Pinion": to_dock(gear_at(parts["11_Clamp_Pinion"],width),p),
    })
    if references:
        result.update({name:to_dock(shape,p) for name,shape in hardware(width).items()})
    return result


def print_pose(name, solid):
    if name in REUSED:
        return r02.print_pose(name,solid)
    if name != "04_Wedge_45deg":
        solid = solid.rotate((0,0,0),(1,0,0),90 if name == "10_Clamp_Cover" else -90)
    x0,y0,z0,x1,y1,_ = r02.exact_bounds(solid)
    return solid.translate((-(x0+x1)/2,-(y0+y1)/2,-z0))


def color(name):
    if "Spring" in name:
        return (.12,.57,.70)
    if name.startswith("REF") or name == "11_Clamp_Pinion":
        return (.33,.41,.47)
    if name == "05_Jaw_Left":
        return (.43,.65,.76)
    if name == "06_Jaw_Right":
        return (.93,.69,.34)
    if name in ("01_Base","02_Base_Lid","04_Wedge_45deg","10_Clamp_Cover"):
        return (.78,.80,.82)
    return (.29,.32,.34)


def assembly(shapes, path, name="HachimoDock_R03"):
    model = cq.Assembly(name=name)
    for key,solid in shapes.items():
        model.add(solid, name=key, color=cq.Color(*color(key)))
    model.export(str(path))


def export(out):
    p = parameters()
    parts = make_parts(p)
    for dirname in ("parts-step","print-stl","configurations"):
        (out/dirname).mkdir(parents=True,exist_ok=True)
    inventory = {"units":"mm","r02_parts_reused":list(REUSED),"parts":{}, "configurations":[]}
    for name,solid in parts.items():
        assert solid.isValid() and len(solid.Solids()) == 1, name
        part = print_pose(name,solid)
        step, stl = f"parts-step/{name}.step", f"print-stl/{name}.stl"
        cq.exporters.export(part,str(out/step))
        if not part.exportStl(str(out/stl),tolerance=.025,angularTolerance=.12):
            raise RuntimeError(f"STL export failed: {name}")
        inventory["parts"][name] = {"step":step,"stl":stl,"dimensions_mm":r02.dimensions(part)}
    for width in WIDTHS:
        filename = f"configurations/Gap-{width}mm.step"
        assembly(placed(parts,p,width,references=True),out/filename)
        inventory["configurations"].append({"nominal_pad_gap_mm":width,"step":filename,
                                             "spring_eye_span_mm":spring_span(width)})
    installed = placed(parts,p,76,references=True)
    assembly(installed,out/"HachimoDock-R03.step")
    opened = {k:v for k,v in installed.items() if k not in (*REUSED,"10_Clamp_Cover")
              and not k.startswith("REF_Cover_Screw")}
    assembly(opened,out/"R03-Clamp-Cover-Off.step","R03_Clamp_Cover_Off")
    scene = trimesh.Scene()
    for name,solid in installed.items():
        vertices,faces = solid.tessellate(.06,.15)
        mesh = trimesh.Trimesh([v.toTuple() for v in vertices],faces)
        mesh.visual.vertex_colors = [round(c*255) for c in color(name)] + [255]
        mesh.apply_scale(.001)
        mesh.apply_transform(trimesh.transformations.rotation_matrix(-math.pi/2,(1,0,0)))
        scene.add_geometry(mesh,geom_name=name,node_name=name)
    scene.export(str(out/"HachimoDock-R03.glb"))
    inventory["reference_parts"] = list(hardware(76))
    (out/"inventory.json").write_text(json.dumps(inventory,indent=2)+"\n",encoding="utf-8")
    print(f"Exported {len(parts)} printable parts and {len(WIDTHS)} opening positions.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=ROOT/"model")
    export(parser.parse_args().out)
