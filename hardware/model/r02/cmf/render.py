"""Render silver/graphite finish studies from the unchanged R02 CAD meshes."""

import argparse
import hashlib
import json
import math
from pathlib import Path

import bpy
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Vector


ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[3]
S = .01
PALETTES = json.loads((ROOT/"palettes.json").read_text())


def srgb(color):
    values = [int(color[i:i+2], 16)/255 for i in (1, 3, 5)]
    return tuple(v/12.92 if v <= .04045 else ((v+.055)/1.055)**2.4 for v in values)


def finish_spec(name, palette):
    key = ("jaw" if name[:2] in {"05", "06"} else
           "rotor" if name[:2] == "03" else
           "body" if name[:2] in {"01", "02", "04"} else "internal")
    return PALETTES[palette][key]


def finish(name, spec, grain=True):
    result = bpy.data.materials.new(name)
    result.use_nodes = True
    nodes = result.node_tree.nodes
    links = result.node_tree.links
    shader = nodes.get("Principled BSDF")
    shader.inputs["Base Color"].default_value = (*srgb(spec["color"]), 1)
    shader.inputs["Roughness"].default_value = spec["roughness"]
    shader.inputs["Metallic"].default_value = spec["metallic"]
    shader.inputs["Coat Weight"].default_value = .09 if not spec["metallic"] else 0
    shader.inputs["Coat Roughness"].default_value = .35
    edge = nodes.new("ShaderNodeBevel")
    edge.inputs["Radius"].default_value = .0015
    edge.samples = 3
    if grain:
        coordinates = nodes.new("ShaderNodeTexCoord")
        noise = nodes.new("ShaderNodeTexNoise")
        noise.inputs["Scale"].default_value = 700
        noise.inputs["Detail"].default_value = 2
        bump = nodes.new("ShaderNodeBump")
        bump.inputs["Strength"].default_value = .075
        bump.inputs["Distance"].default_value = .0005
        links.new(coordinates.outputs["Object"], noise.inputs["Vector"])
        links.new(noise.outputs["Fac"], bump.inputs["Height"])
        links.new(edge.outputs["Normal"], bump.inputs["Normal"])
        links.new(bump.outputs["Normal"], shader.inputs["Normal"])
    else:
        links.new(edge.outputs["Normal"], shader.inputs["Normal"])
    return result


def import_dock(model):
    inventory = json.loads((model/"inventory.json").read_text())
    before = set(bpy.context.scene.objects)
    bpy.ops.import_scene.gltf(filepath=str(model/"HachimoDock-R02.glb"))
    objects = set(bpy.context.scene.objects)-before
    parts = {obj.name: obj for obj in objects if obj.type == "MESH"}
    assert set(parts) == set(inventory["parts"]), "Import must have exactly nine CAD parts"
    for obj in parts.values():
        obj.scale *= 10
        obj.location *= 10
    bpy.context.view_layer.update()
    return parts


def assign_finish(parts, palette):
    materials = {}
    for name, obj in parts.items():
        spec = finish_spec(name, palette)
        key = tuple(spec.values())
        if key not in materials:
            materials[key] = finish(f"{palette}/{name}", spec)
        obj.data.materials.clear()
        obj.data.materials.append(materials[key])
        for polygon in obj.data.polygons:
            polygon.use_smooth = True
        obj.data.set_sharp_from_angle(angle=math.radians(35))
        modifier = obj.modifiers.new("CAD shading only", "WEIGHTED_NORMAL")
        modifier.keep_sharp = True


def aim(obj, target):
    obj.rotation_euler = (Vector(target)-obj.location).to_track_quat("-Z", "Y").to_euler()


def setup_studio(width, height, samples):
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = samples
    scene.cycles.use_denoising = True
    scene.cycles.max_bounces = 6
    scene.render.threads_mode = "FIXED"
    scene.render.threads = 4
    scene.render.resolution_x, scene.render.resolution_y = width, height
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.film_transparent = True
    scene.world = bpy.data.worlds.new("Neutral studio")
    scene.world.use_nodes = True
    scene.world.node_tree.nodes["Background"].inputs[0].default_value = (.7,.72,.75,1)
    scene.world.node_tree.nodes["Background"].inputs[1].default_value = .3
    scene.view_settings.view_transform = "AgX"
    scene.view_settings.look = "AgX - Medium High Contrast"
    for name, position, power, size in [
        ("Key / large softbox", (-220,-240,390), 340, 300),
        ("Fill / reflection panel", (240,-170,260), 130, 210),
        ("Rim / overhead", (20,230,360), 320, 250),
    ]:
        data = bpy.data.lights.new(name, "AREA")
        data.energy, data.shape, data.size = power, "DISK", size*S
        obj = bpy.data.objects.new(name, data)
        scene.collection.objects.link(obj)
        obj.location = Vector(position)*S
        aim(obj, (0,0,.8))
    data = bpy.data.cameras.new("Studio camera")
    camera = bpy.data.objects.new("Studio camera", data)
    scene.collection.objects.link(camera)
    data.type = "ORTHO"
    scene.camera = camera
    return scene, camera


def world_points(objects):
    bpy.context.view_layer.update()
    return [obj.matrix_world@Vector(point) for obj in objects for point in obj.bound_box]


def frame_camera(camera, objects, position, margin=1.2):
    points = world_points(objects)
    center = Vector([(min(p[i] for p in points)+max(p[i] for p in points))/2
                     for i in range(3)])
    camera.location = Vector(position)*S
    aim(camera, center)
    bpy.context.view_layer.update()
    local = [camera.matrix_world.inverted()@p for p in points]
    xmin, xmax = min(p.x for p in local), max(p.x for p in local)
    ymin, ymax = min(p.y for p in local), max(p.y for p in local)
    camera.location += camera.rotation_euler.to_matrix()@Vector(
        ((xmin+xmax)/2, (ymin+ymax)/2, 0)
    )
    scene = bpy.context.scene
    aspect = scene.render.resolution_x/scene.render.resolution_y
    camera.data.ortho_scale = max(ymax-ymin, (xmax-xmin)/aspect)*margin
    bpy.context.view_layer.update()


def camera_bounds(scene, camera, objects):
    points = [world_to_camera_view(scene, camera, p) for p in world_points(objects)]
    return min(p.x for p in points), min(p.y for p in points), \
        max(p.x for p in points), max(p.y for p in points)


def rounded_box(name, size, position, material, parent=None, bevel=.004):
    bpy.ops.mesh.primitive_cube_add(size=1)
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = Vector(size)*S
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    obj.location = Vector(position)*S
    if parent is not None:
        obj.parent = parent
    obj.data.materials.append(material)
    if bevel:
        modifier = obj.modifiers.new("Reference accessory corners", "BEVEL")
        modifier.width, modifier.segments = bevel, 4
        modifier = obj.modifiers.new("Reference accessory normals", "WEIGHTED_NORMAL")
        modifier.keep_sharp = True
    return obj


def surface(name, points, material, parent, uvs=None):
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([tuple(Vector(p)*S) for p in points], [], [tuple(range(len(points)))])
    mesh.materials.append(material)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    obj.parent = parent
    if uvs:
        layer = mesh.uv_layers.new()
        for loop in mesh.loops:
            layer.data[loop.index].uv = uvs[loop.vertex_index]
    return obj


def rounded_outline(width, height, radius):
    points = []
    for xsign, zsign, start in ((1,1,0),(-1,1,90),(-1,-1,180),(1,-1,270)):
        for i in range(13):
            angle = math.radians(start+90*i/12)
            points.append(((width/2-radius)*xsign+radius*math.cos(angle),
                           (height/2-radius)*zsign+radius*math.sin(angle)))
    return points


def accessories(p, palette):
    ink = finish("Reference silicone", {"color":"#272A2E","roughness":.62,"metallic":0}, False)
    metal = finish("Reference phone frame", {"color":"#B5B8BD","roughness":.25,"metallic":.8}, False)
    group = bpy.data.objects.new("Reference phone slope / not CAD", None)
    bpy.context.scene.collection.objects.link(group)
    offset = p["base_height"]+8.4+.65+6-56.05
    group.location = Vector((0,-48,57+offset))*S
    group.rotation_euler.x = -math.pi/4
    phone = rounded_box("Reference phone / not CAD", (p["phone_width"],12,150),
                        (0,-12,92.8), metal, group, .04)
    glass = finish("Reference cover glass", {"color":"#090C0F","roughness":.2,"metallic":.1}, False)
    rounded_box("Reference glass / not CAD", (p["phone_width"]-.7,.7,149.2),
                (0,-18.05,92.8), glass, group, .038)
    screen = bpy.data.materials.new("Reference desktop pet screen / not application change")
    screen.use_nodes = True
    nodes, links = screen.node_tree.nodes, screen.node_tree.links
    shader = nodes.get("Principled BSDF")
    texture = nodes.new("ShaderNodeTexImage")
    texture.image = bpy.data.images.load(str(ROOT/"screen-reference.png"), check_existing=True)
    shader.inputs["Roughness"].default_value = .38
    shader.inputs["Specular IOR Level"].default_value = .12
    shader.inputs["Emission Strength"].default_value = .48
    links.new(texture.outputs["Color"], shader.inputs["Base Color"])
    links.new(texture.outputs["Color"], shader.inputs["Emission Color"])
    w, h = p["phone_width"]-5, 144
    outline = rounded_outline(w, h, 8)
    surface("Reference screen / not CAD", [(x,-18.45,z+92.8) for x,z in outline],
            screen, group, [(x/w+.5,z/h+.5) for x,z in outline])
    for sign in (-1, 1):
        rounded_box("Reference back foam / not CAD", (7,1,65),
                    (sign*23,-5.6,85), ink, group, .003)
        rounded_box("Reference side foam / not CAD", (p["jaw_pad"],9,15),
                    (sign*(p["phone_width"]/2+p["jaw_pad"]/2),-12,32),
                    ink, group, .002)
    rounded_box("Reference USB plug / not CAD", (9.5,5,6), (0,-12,15), ink, group, .008)
    screw = finish("Reference screw metal", {"color":"#B4B8BE","roughness":.35,"metallic":.8}, False)
    for x in (-p["base_width"]/2+11, p["base_width"]/2-11):
        for y in (-p["base_depth"]/2+15, p["base_depth"]/2-15):
            bpy.ops.mesh.primitive_cylinder_add(vertices=32, radius=2.75*S, depth=.9*S)
            obj = bpy.context.object
            obj.name = "Reference fitted screw / not CAD"
            obj.location = Vector((x,y,p["base_height"]+8.4-2.1))*S
            obj.data.materials.append(screw)
    bpy.context.view_layer.update()
    return phone


def dock_group(model, palette, position=(0,0,0), yaw=0, phone=True):
    before = set(bpy.context.scene.objects)
    parts = import_dock(model)
    assign_finish(parts, palette)
    if phone:
        accessories(json.loads((model/"inventory.json").read_text())["parameters"], palette)
    objects = set(bpy.context.scene.objects)-before
    root = bpy.data.objects.new(f"{palette} dock / unchanged CAD", None)
    bpy.context.scene.collection.objects.link(root)
    for obj in objects:
        if obj.parent is None:
            obj.parent = root
    root.location = Vector(position)*S
    root.rotation_euler.z = math.radians(yaw)
    bpy.context.view_layer.update()
    return [obj for obj in objects if obj.type == "MESH"], parts


def add_ground():
    mat = finish("Neutral studio floor", {"color":"#EFF0F2","roughness":.82,"metallic":0}, False)
    bpy.ops.mesh.primitive_plane_add(size=200, location=(0,0,-.001))
    obj = bpy.context.object
    obj.name = "Studio floor / not CAD"
    obj.data.materials.append(mat)
    obj.is_shadow_catcher = True


def image_render(scene, path):
    scene.render.filepath = str(path)
    bpy.ops.render.render(write_still=True)
    print(f"RENDERED {path}", flush=True)


def render_view(model, out, view, samples=32, scale=1):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    paired = view == "hero"
    width, height = ((1800,1700) if paired else (1200,1100))
    scene, camera = setup_studio(round(width*scale), round(height*scale), samples)
    if paired:
        a, _ = dock_group(model, "silver", (-90,-45,0), 0)
        # Duplicate from the same CAD without relying on suffixed object names.
        b, _ = dock_group_duplicate(model, "graphite", (85,65,0), 155)
        objects = a+b
        position = (390,-560,315)
        margin = 1.28
    else:
        palette = "graphite" if view == "graphite" else "silver"
        yaw = 155 if view == "rear" else 0
        objects, _ = dock_group(model, palette, yaw=yaw, phone=True)
        position = {"front":(0,-500,102), "side":(500,0,102),
                    "rear":(310,-440,280)}.get(view, (300,-460,290))
        margin = 1.15
    add_ground()
    frame_camera(camera, objects, position, margin)
    assert all(.005 < c < .995 for c in camera_bounds(scene,camera,objects))
    image_render(scene, out/f"{view}.png")
    (out/f"{view}-manifest.json").write_text(json.dumps({
        "view":view, "source_sha256":source_hashes(model),
        "pixels":[scene.render.resolution_x,scene.render.resolution_y],
        "samples":samples, "camera_bounds":camera_bounds(scene,camera,objects),
        "palettes":PALETTES, "cad_geometry_modified":False,
    },indent=2)+"\n",encoding="utf-8")


def dock_group_duplicate(model, palette, position, yaw):
    # Import into a fresh collection while preserving exact inventory names.
    existing = {obj:obj.name for obj in bpy.context.scene.objects if obj.type == "MESH"}
    for obj in existing:
        obj.name = "prior/"+obj.name
    objects, parts = dock_group(model, palette, position, yaw)
    for obj, name in existing.items():
        obj.name = "first/"+name
    return objects, parts


def source_hashes(model):
    paths = list(model.rglob("*.step"))+list(model.rglob("*.stl"))
    paths += [model/"HachimoDock-R02.glb", model/"inventory.json", model.parent/"dimensions.json"]
    return {str(path.relative_to(model.parent)):hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(paths)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, default=ROOT.parent/"model")
    parser.add_argument("--out", type=Path, default=REPO/"assets/r02-cmf/work")
    parser.add_argument("--view", choices=["hero","silver","graphite","front","side","rear","all"],
                        default="all")
    parser.add_argument("--samples", type=int, default=32)
    parser.add_argument("--scale", type=float, default=1)
    parser.add_argument("--skip-existing", action="store_true")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    hashes = source_hashes(args.model)
    views = ["hero","silver","graphite","front","side","rear"] if args.view == "all" else [args.view]
    for view in views:
        if args.skip_existing and (args.out/f"{view}.png").is_file():
            metadata = args.out/f"{view}-manifest.json"
            if metadata.is_file() and json.loads(metadata.read_text())["source_sha256"] == hashes:
                print(f"REUSING {view}: matching CAD source", flush=True)
                continue
        render_view(args.model, args.out, view, args.samples, args.scale)
    assert source_hashes(args.model) == hashes, "Render must not modify any manufacturing file"
    (args.out/"render-manifest.json").write_text(json.dumps(
        {"source_sha256":hashes, "palettes":PALETTES, "views":views,
         "render_only_reference_accessories":True, "cad_geometry_modified":False},
        indent=2
    )+"\n", encoding="utf-8")
