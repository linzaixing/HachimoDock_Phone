"""Render the exported CAD GLB, not the original concept geometry."""

import argparse
import json
import math
from pathlib import Path

import bpy
from mathutils import Vector
from PIL import Image, ImageDraw, ImageFont


def material(name, color, roughness=.45, metallic=0):
    result = bpy.data.materials.new(name)
    result.use_nodes = True
    shader = result.node_tree.nodes.get("Principled BSDF")
    shader.inputs["Base Color"].default_value = (*color, 1)
    shader.inputs["Roughness"].default_value = roughness
    shader.inputs["Metallic"].default_value = metallic
    return result


def aim(obj, target):
    obj.rotation_euler = (Vector(target)-obj.location).to_track_quat("-Z", "Y").to_euler()


def light(name, position, power, size, target):
    data = bpy.data.lights.new(name, "AREA")
    data.energy = power
    data.shape = "DISK"
    data.size = size
    obj = bpy.data.objects.new(name, data)
    bpy.context.collection.objects.link(obj)
    obj.location = position
    aim(obj, target)


def preview(model, out):
    inventory = json.loads((model/"inventory.json").read_text())
    p = inventory["parameters"]
    out.mkdir(parents=True, exist_ok=True)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(model/"HachimoDock-R02.glb"))
    meshes = {obj.name: obj for obj in bpy.context.scene.objects if obj.type == "MESH"}
    assert set(meshes) == set(inventory["parts"]), "Preview must contain exactly the CAD parts"
    originals = {name: obj.location.copy() for name, obj in meshes.items()}
    orange = material("Printed orange", (.74,.16,.035))
    graphite = material("Printed graphite", (.055,.063,.069))
    for name, obj in meshes.items():
        obj.data.materials.clear()
        obj.data.materials.append(orange if name[:2] in {"01","02","04","05","06"} else graphite)

    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 16
    scene.cycles.use_denoising = True
    scene.render.resolution_x = 1000
    scene.render.resolution_y = 800
    scene.render.resolution_percentage = 100
    scene.render.threads_mode = "FIXED"
    scene.render.threads = 2
    scene.render.image_settings.file_format = "PNG"
    scene.world = bpy.data.worlds.new("Studio")
    scene.world.use_nodes = True
    scene.world.node_tree.nodes["Background"].inputs[0].default_value = (.78,.81,.83,1)
    scene.world.node_tree.nodes["Background"].inputs[1].default_value = .4
    scene.view_settings.view_transform = "AgX"
    scene.view_settings.exposure = -1.5
    bpy.ops.mesh.primitive_plane_add(size=200, location=(0,0,-.001))
    ground = bpy.context.object
    ground.name = "Studio_floor_not_a_part"
    ground.data.materials.append(material("Ground", (.77,.79,.80), .75))
    light("Key", (.15,-.30,.40), 16, .35, (0,0,.07))
    light("Fill", (-.28,-.10,.22), 8, .25, (0,0,.07))
    light("Rim", (.10,.28,.35), 13, .25, (0,0,.08))

    camera_data = bpy.data.cameras.new("Camera")
    camera = bpy.data.objects.new("Camera", camera_data)
    bpy.context.collection.objects.link(camera)
    camera_data.type = "ORTHO"
    scene.camera = camera
    offset = p["base_height"]+8.4+.65+6-56.05

    # The phone is only an assembly envelope, and is excluded from every CAD/STL file.
    bpy.ops.mesh.primitive_cube_add(size=1)
    phone = bpy.context.object
    phone.name = "Reference_phone_not_a_part"
    y, z = -6-p["phone_thickness_max"]/2, 92.8
    phone.location = (0, (-48+(y+z)/math.sqrt(2))*.001,
                      (57+(z-y)/math.sqrt(2)+offset)*.001)
    phone.dimensions = (p["phone_width"]*.001, p["phone_thickness_max"]*.001, .15)
    phone.rotation_euler.x = -math.pi/4
    phone.data.materials.append(material("Phone envelope", (.21,.25,.27), .5))
    bevel = phone.modifiers.new("Envelope edge", "BEVEL")
    bevel.width = .003
    bevel.segments = 3

    views = [
        ("assembly", "01  ASSEMBLY / PHONE ENVELOPE", (.30,-.40,.27), (0,0,.092), .30, True),
        ("front", "02  FRONT / ADJUSTABLE JAWS", (0,-.50,.115), (0,0,.095), .27, False),
        ("right", "03  RIGHT / FIXED 45 DEG", (.50,0,.095), (0,0,.095), .28, True),
        ("exploded", "04  EXPLODED / 9 PRINTED PARTS", (.30,-.42,.35), (0,0,.16), .44, False),
        ("top", "05  TOP / ROTATING PLATFORM", (0,0,.60), (0,0,0), .25, False),
        ("rear", "06  REAR / CABLE OUTLET", (-.26,.38,.26), (0,0,.09), .29, False),
    ]
    images = []
    for key, label, position, target, scale, show_phone in views:
        for name, obj in meshes.items():
            obj.location = originals[name].copy()
        phone.hide_render = not show_phone
        if key == "exploded":
            lifts = {"02_Base_Lid":.07, "03_Rotor":.10, "04_Wedge_45deg":.16,
                     "05_Jaw_Left":.16, "06_Jaw_Right":.16,
                     "07_Driven_48T":.035, "08_Servo_24T":.035, "09_Shaft_Spacer":.055}
            for name, lift in lifts.items():
                meshes[name].location.z += lift
            meshes["05_Jaw_Left"].location.x -= .035
            meshes["06_Jaw_Right"].location.x += .035
        camera.location = position
        camera_data.ortho_scale = scale
        aim(camera, target)
        path = out/f"{key}.png"
        scene.render.filepath = str(path)
        bpy.ops.render.render(write_still=True)
        with Image.open(path) as image:
            images.append((image.convert("RGB"), label))

    font = ImageFont.load_default(size=23)
    small = ImageFont.load_default(size=20)
    board = Image.new("RGB", (2040,2650), "#f4f6f7")
    draw = ImageDraw.Draw(board)
    draw.text((25,20), "HACHIMODOCK R02 / ACTUAL CAD GEOMETRY", fill="#20282a", font=font)
    draw.text((25,55), "mm | clamp 62-90 | fixed 45 deg | 9 printable parts", fill="#505c60", font=small)
    for i, (image, label) in enumerate(images):
        x, y = 20+(i%2)*1000, 110+(i//2)*840
        board.paste(image, (x,y))
        draw.text((x+10,y+805), label, fill="#20282a", font=small)
    board.save(out/"CAD-preview.png")
    print(f"Rendered actual CAD preview to {out.resolve()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, default=Path("model"))
    parser.add_argument("--out", type=Path, default=Path("model/preview"))
    args = parser.parse_args()
    preview(args.model, args.out)
