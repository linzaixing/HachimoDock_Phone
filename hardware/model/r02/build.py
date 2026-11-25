import argparse
import json
import math
from pathlib import Path

import cadquery as cq
import trimesh
from OCP.Bnd import Bnd_Box
from OCP.BRepBndLib import BRepBndLib

AXIS_Y = 7
LID_T = 8.4


def load(path):
    p = json.loads(path.read_text())
    for key, default in (("servo_body_length",23.0),("servo_body_width",12.6),
                         ("servo_shaft_from_end",4.5),("servo_clearance",.35)):
        p.setdefault(key, default)
    if not 54 <= p["width_min"] < p["width_max"] <= 96:
        raise ValueError("Clamp travel must stay within 54–96 mm")
    if not p["width_min"] <= p["phone_width"] <= p["width_max"]:
        raise ValueError("Preview width must be inside adjustable travel")
    if not 8 <= p["phone_thickness_max"] <= 23 or not 10 <= p["cable_bore"] <= 17:
        raise ValueError("Thickness must be 8–23 mm and spindle bore 10–17 mm")
    if not 12 <= p["plug_opening"] <= 20:
        raise ValueError("Plug opening must be 12–20 mm")
    if not 142 <= p["base_width"] <= 166 or not 146 <= p["base_depth"] <= 178:
        raise ValueError("Base outside supported size range")
    if not 39 <= p["base_height"] <= 48 or not 3 <= p["wall"] <= 4.5:
        raise ValueError("Base height or wall thickness out of range")
    if not 120 <= p["rotor_diameter"] <= 134 or not 70 <= p["wedge_width"] <= 78:
        raise ValueError("Turntable diameter or wedge width out of range")
    if not .2 <= p["slide_fit"] <= .6 or not 0 <= p["jaw_pad"] <= 2:
        raise ValueError("Slide clearance or jaw pad thickness out of range")
    if not .05 <= p["bearing_fit"] <= .5 or not .05 <= p["shaft_fit"] <= .5:
        raise ValueError("Bearing and shaft fits must be between 0.05 and 0.50 mm")
    if not 39 <= p["servo_center"] <= 42:
        raise ValueError("Servo center must be 39–42 mm from spindle")
    if not 20 <= p["servo_body_length"] <= 24 or not 11 <= p["servo_body_width"] <= 14:
        raise ValueError("Servo body outside supported 20-24 by 11-14 mm envelope")
    if not 4 <= p["servo_shaft_from_end"] <= 6 or not .2 <= p["servo_clearance"] <= .5:
        raise ValueError("Servo shaft offset or body clearance outside supported range")
    return p


def cylinder(r, start, end, x=0, y=0, axis="z"):
    origin, direction = ((x, y, start), (0, 0, 1)) if axis == "z" else ((x, start, y), (0, 1, 0))
    return cq.Solid.makeCylinder(r, end-start, cq.Vector(*origin), cq.Vector(*direction))


def block(x0, x1, y0, y1, z0, z1):
    return cq.Solid.makeBox(x1-x0, y1-y0, z1-z0, cq.Vector(x0,y0,z0))


def rounded(w, d, r, z0, z1):
    return (cq.Workplane("XY").box(w,d,z1-z0,centered=(True,True,False))
            .edges("|Z").fillet(r).val().translate((0,0,z0)))


def yz_poly(points, width):
    return cq.Workplane("YZ", origin=(-width/2,0,0)).polyline(points).close().extrude(width).val()


def phone_plane(v):
    return cq.Vector(v.x, -48 + (v.y+v.z)/math.sqrt(2), 57 + (v.z-v.y)/math.sqrt(2))


def slope(shape):
    return shape.rotate((0,0,0),(1,0,0),-45).translate((0,-48,57))


def screws(p):
    return [(x,y) for x in (-p["base_width"]/2+11,p["base_width"]/2-11)
            for y in (-p["base_depth"]/2+15,p["base_depth"]/2-15)]


def support_screws():
    return [(x,y) for x in (-25,25) for y in (-29,36)]


def jaw_screw_x(p):
    # Keep the two extended slider plates clear at the narrowest setting.
    return max(20.0, (p["width_max"]-p["width_min"])/2+5.4)


def rotor_bottom(p):
    return p["base_height"] + LID_T + .65


def lip_parameters(p):
    move = rotor_bottom(p)+6-56.05
    return move


def servo_body_envelope(p, clearance=0):
    x0 = p["servo_center"]-p["servo_shaft_from_end"]
    return block(x0-clearance, x0+p["servo_body_length"]+clearance,
                 AXIS_Y-p["servo_body_width"]/2-clearance,
                 AXIS_Y+p["servo_body_width"]/2+clearance,
                 3.8, p["base_height"]-21)


def make_base(p):
    w,d,h,wall = (p[key] for key in ("base_width","base_depth","base_height","wall"))
    base = rounded(w,d,22,0,h).cut(rounded(w-2*wall,d-2*wall,22-wall,3.6,h+1))
    base = base.cut(block(20,42,d/2-wall-1,d/2+1,12,23))
    for x,y in screws(p):
        post = cylinder(5.1,3.55,h,x,y)
        web = block(min(x,math.copysign(w/2-2,x))-1,max(x,math.copysign(w/2-2,x))+1,
                    y-2,y+2,3.55,h-1.5)
        base = base.fuse(post,web).cut(cylinder(1.38,h-10,h+1,x,y))
    cx = p["servo_center"]
    mount_top = h-21
    for x in (cx-6.5,cx+20.5):
        base = base.fuse(block(x-3.8,x+3.8,AXIS_Y-6.4,AXIS_Y+6.4,3.55,mount_top))
        base = base.cut(cylinder(1.05,mount_top-7,mount_top+.2,x,AXIS_Y))
    for x in (-49,-19):
        base = base.fuse(block(x-2,x+2,-52,-15,3.55,8))
    for y in (-48,-20):
        for x in (-52,-16):
            base = base.fuse(block(x-2.6,x+2.6,y-3.5,y+3.5,3.55,9.5))
            base = base.cut(block(x-3,x+3,y-1.2,y+1.2,5.4,7.5))
    return base.cut(servo_body_envelope(p, p["servo_clearance"])).clean()


def make_lid(p):
    h,w,d = (p[key] for key in ("base_height","base_width","base_depth"))
    lid = rounded(w,d,22,h,h+LID_T)
    lid = lid.cut(cylinder((37+p["bearing_fit"])/2,h+1.20,h+LID_T+1,0,AXIS_Y),
                  cylinder(13.45,h-.5,h+1.23,0,AXIS_Y))
    for x,y in screws(p):
        lid = lid.cut(cylinder(1.65,h-1,h+LID_T+1,x,y),
                      cylinder(3.1,h+LID_T-2.7,h+LID_T+1,x,y))
    return lid.cut(cylinder(1.4,h+2,h+LID_T+1,0,AXIS_Y-55)).clean()


def arc_slot(z):
    angles = [math.radians(225+90*i/48) for i in range(49)]
    pts = [(r*math.cos(a),AXIS_Y+r*math.sin(a))
           for r,seq in ((58.15,angles),(51.85,angles[::-1])) for a in seq]
    face = cq.Workplane("XY",origin=(0,0,z)).polyline(pts).close().extrude(3.4).val()
    for a in (angles[0],angles[-1]):
        face = face.fuse(cylinder(3.15,z,z+3.4,55*math.cos(a),AXIS_Y+55*math.sin(a)))
    return face


def make_rotor(p):
    h = p["base_height"]
    z = rotor_bottom(p)
    rshaft = (25-p["shaft_fit"])/2
    core = cylinder(rshaft,h-11,z+.25,0,AXIS_Y)
    core = core.cut(block(10.8,15,AXIS_Y-16,AXIS_Y+16,h-13,h-.5),
                    block(-15,-10.8,AXIS_Y-16,AXIS_Y+16,h-13,h-.5))
    disc = cylinder(p["rotor_diameter"]/2,z,z+6,0,AXIS_Y)
    disc = cq.Workplane(obj=disc).edges().fillet(1.0).val()
    collar = cylinder(13.1,h+8.24,z+.2,0,AXIS_Y)
    part = disc.fuse(core,collar).cut(cylinder(p["cable_bore"]/2,h-13,z+7,0,AXIS_Y),
                                      arc_slot(z-.3))
    for x,y in support_screws():
        part = part.cut(cylinder(1.66,z-1,z+7,x,y),
                        cq.Solid.makeCone(3.15,1.65,1.6,cq.Vector(x,y,z-.01)))
    for y in (AXIS_Y-10,AXIS_Y+10):
        part = part.cut(cylinder(1.08,h-12.1,h-6,0,y))
    return part.clean()


def make_wedge(p):
    width,wall = p["wedge_width"],p["wall"]
    outer = yz_poly([(-48,56.05),(56,56.05),(56,127),(48,153),(-48,57)],width)
    inner = yz_poly([(-44,55),(56-wall,55),(56-wall,127),
                     (47,152-wall*math.sqrt(2)),(-44,60.9-wall*math.sqrt(2))],width-2*wall)
    wedge = outer.cut(inner)
    for x,y in support_screws():
        wedge = wedge.fuse(cylinder(4.4,56.05,65,x,y),
                           block(x-4.2,x+4.2,y-4,y+4,56.05,59))
        # Tie the screw pillars into the sidewall.
        outer_x = math.copysign(width/2-1,x)
        wedge = wedge.fuse(block(min(x,outer_x)-.4,max(x,outer_x)+.4,y-2,y+2,56.05,65))
        wedge = wedge.cut(cylinder(1.35,55.9,64,x,y))
    for sign in (-1,1):
        a,b=sorted((sign*11,sign*(width/2-1)))
        wedge = wedge.fuse(slope(block(a,b,-4.8,2.4,26.0,29.0)),
                           slope(block(a,b,-4.8,2.4,39.0+2*p["slide_fit"],42.2+2*p["slide_fit"])))
        wedge = wedge.fuse(slope(cylinder(4.15,0,8,sign*jaw_screw_x(p),34+p["slide_fit"],"y")))
        wedge = wedge.cut(slope(cylinder(1.38,-1,7,sign*jaw_screw_x(p),34+p["slide_fit"],"y")))
        wedge = wedge.fuse(slope(block(sign*23-4,sign*23+4,-5,1.4,52,119)))
    opening=p["plug_opening"]
    port = block(-opening/2,opening/2,-3,8,1.3,12.5)
    return wedge.cut(slope(port)).clean().translate((0,0,lip_parameters(p)))


def rslot(x0,x1,z,r,y0,y1):
    return (block(x0,x1,y0,y1,z-r,z+r)
            .fuse(cylinder(r,y0,y1,x0,z,"y"),cylinder(r,y0,y1,x1,z,"y")).clean())


def make_jaw(p):
    min_edge=p["width_min"]/2+p["jaw_pad"]
    max_edge=p["width_max"]/2+p["jaw_pad"]
    start,end=jaw_screw_x(p)-max_edge-.1,jaw_screw_x(p)-min_edge+.1
    center=34+p["slide_fit"]
    z0,z1=center-5,center+5
    lip_y=-(7+p["phone_thickness_max"])
    plate=block(start-4.7,4,-4.6,-.2,z0,z1)
    outer=block(0,5,lip_y,-5.15,17,z0+.3).fuse(block(0,5,lip_y,-.2,z0,z1))
    toe=block(-12,5,lip_y-3,-3,12.7,17.5)
    lip=block(-12,5,lip_y-3,lip_y,17.1,21.7)
    jaw=plate.fuse(outer,toe,lip)
    jaw=jaw.cut(rslot(start,end,center,1.68,-5,-.05))
    # A loft gives the screw head a continuous countersunk adjustment path.
    plane=cq.Plane(origin=((start+end)/2,-4.61,center),xDir=(1,0,0),normal=(0,1,0))
    recess=(cq.Workplane(plane).slot2D(end-start+6.25,6.25)
            .workplane(offset=1.55).slot2D(end-start+3.36,3.36).loft(combine=True).val())
    return jaw.cut(recess).clean()


def teeth(n,z,x,y):
    pitch=n/math.pi
    root,tip=pitch-.84,pitch-.38
    points=[]
    for i in range(n):
        for frac,r in ((-.50,root),(-.26,root),(-.16,tip),(.16,tip),(.26,root)):
            a=2*math.pi*(i+frac)/n
            points.append((x+r*math.cos(a),y+r*math.sin(a)))
    return cq.Workplane("XY",origin=(0,0,z)).polyline(points).close().extrude(6.5).val()


def make_pulley(p, driven):
    n,x = (48,0) if driven else (24,p["servo_center"])
    z=p["base_height"]-12
    r=n/math.pi+.25
    y=AXIS_Y
    pulley=teeth(n,z+1,x,y).fuse(cylinder(r,z,z+1.1,x,y),cylinder(r,z+7.45,z+8.5,x,y))
    if driven:
        bore=cylinder(12.61,z-1,z+10,x,y)
        bore=bore.intersect(block(-11.03,11.03,y-16,y+16,z-1,z+10))
        pulley=pulley.cut(bore.translate((0,0,1.6)),
                          cylinder(p["cable_bore"]/2,z-1,z+10,x,y))
        for yy in (y-10,y+10):
            pulley=pulley.cut(cylinder(1.36,z-1,z+2,x,yy))
    else:
        pulley=pulley.cut(cylinder(1.22,z-1,z+10,x,y),cylinder(2.65,z+2,z+10,x,y))
        for dx in (-4.7,4.7):
            pulley=pulley.cut(cylinder(.95,z-1,z+5,x+dx,y))
    return pulley.clean()


def make_parts(p):
    jaw=make_jaw(p)
    z=p["base_height"]
    spacer=cylinder(13.19,z-3.48,z+.93,0,AXIS_Y).cut(
        cylinder(12.52,z-4,z+2,0,AXIS_Y)
    )
    return {
        "01_Base":make_base(p),
        "02_Base_Lid":make_lid(p),
        "03_Rotor":make_rotor(p),
        "04_Wedge_45deg":make_wedge(p),
        "05_Jaw_Left":jaw.mirror("YZ"),
        "06_Jaw_Right":jaw,
        "07_Driven_48T":make_pulley(p,True),
        "08_Servo_24T":make_pulley(p,False),
        "09_Shaft_Spacer":spacer,
    }


def jaw_position(shape,p,width,sign):
    return slope(shape.translate((sign*(width/2+p["jaw_pad"]),0,0))).translate((0,0,lip_parameters(p)))


def placed(parts,p,width):
    result = dict(parts)
    for name,sign in (("05_Jaw_Left",-1),("06_Jaw_Right",1)):
        result[name]=jaw_position(parts[name],p,width,sign)
    return result


def phone_envelope(p,width):
    return slope(block(-width/2,width/2,-6-p["phone_thickness_max"],-6,17.8,167.8)).translate((0,0,lip_parameters(p)))


def print_pose(name,part):
    if name == "03_Rotor":
        part = part.rotate((0,0,0),(1,0,0),180)
    if "Jaw" in name:
        part = part.rotate((0,0,0),(0,1,0),90 if "Right" in name else -90)
    xmin,ymin,zmin,xmax,ymax,_ = exact_bounds(part)
    return part.translate((-(xmin+xmax)/2,-(ymin+ymax)/2,-zmin))


def part_color(name):
    return (.87,.31,.12) if name[:2] in {"01","02","04","05","06"} else (.17,.19,.20)


def export_assembly(parts, p, width, path):
    assy = cq.Assembly(name="HachimoDock_R02")
    for name, solid in placed(parts, p, width).items():
        assy.add(solid, name=name, color=cq.Color(*part_color(name)))
    assy.export(str(path))


def exact_bounds(solid):
    box = Bnd_Box()
    # Ignore cached display triangles: their deflection inflates CAD dimensions.
    BRepBndLib.AddOptimal_s(solid.wrapped, box, False, False)
    return box.Get()


def dimensions(solid):
    bounds = exact_bounds(solid)
    return [round(bounds[i+3]-bounds[i], 5) for i in range(3)]


def export(p,out):
    step,stl = out/"parts-step",out/"print-stl"
    step.mkdir(parents=True,exist_ok=True)
    stl.mkdir(parents=True,exist_ok=True)
    parts=make_parts(p)
    inventory = {"units": "mm", "parameters": p, "parts": {}, "assemblies": []}
    for name,solid in parts.items():
        if not solid.isValid() or len(solid.Solids()) != 1:
            raise ValueError(f"{name} is not a single valid solid")
        posed=print_pose(name,solid)
        cq.exporters.export(posed,str(step/f"{name}.step"))
        if not posed.exportStl(str(stl/f"{name}.stl"),tolerance=.025,angularTolerance=.12):
            raise RuntimeError(f"STL export failed: {name}")
        inventory["parts"][name] = {
            "quantity": 1,
            "step": f"parts-step/{name}.step",
            "stl": f"print-stl/{name}.stl",
            "print_dimensions_mm": dimensions(posed),
            "volume_mm3": round(solid.Volume(), 5),
        }
    export_assembly(parts, p, p["phone_width"], out/"HachimoDock-R02.step")
    configurations = out / "configurations"
    configurations.mkdir(parents=True, exist_ok=True)
    for width in sorted({p[key] for key in ("width_min", "phone_width", "width_max")}):
        filename = f"configurations/Clamp-{width:g}mm.step"
        export_assembly(parts, p, width, out/filename)
        inventory["assemblies"].append({"phone_width_mm": width, "step": filename})

    scene = trimesh.Scene()
    for name, solid in placed(parts, p, p["phone_width"]).items():
        vertices, faces = solid.tessellate(.06, .15)
        mesh = trimesh.Trimesh([v.toTuple() for v in vertices], faces)
        mesh.visual.vertex_colors = [round(c*255) for c in part_color(name)] + [255]
        mesh.apply_scale(.001)
        # glTF uses Y-up meters; CAD and STL use Z-up millimeters.
        mesh.apply_transform(trimesh.transformations.rotation_matrix(-math.pi/2, (1,0,0)))
        scene.add_geometry(mesh, geom_name=name, node_name=name)
    scene.export(str(out/"HachimoDock-R02.glb"))
    assembly_shape = cq.Compound.makeCompound(list(placed(parts, p, p["phone_width"]).values()))
    inventory["assembly_dimensions_mm"] = dimensions(assembly_shape)
    (out/"inventory.json").write_text(json.dumps(inventory, indent=2) + "\n", encoding="utf-8")
    print(f"Exported {len(parts)} parts to {out.resolve()}")


if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--config",type=Path,default=Path("dimensions.json"))
    parser.add_argument("--out",type=Path,default=Path("model"))
    args=parser.parse_args()
    export(load(args.config),args.out)
