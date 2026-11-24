"""Render the delivered GLB with the cover on and off."""

import json
from pathlib import Path
from tempfile import TemporaryDirectory

import bpy
from mathutils import Vector
from PIL import Image, ImageDraw, ImageFont

MODEL = Path(__file__).resolve().parent / "model"


def aim(obj, position, target):
    obj.location = position
    obj.rotation_euler = (Vector(target)-obj.location).to_track_quat("-Z","Y").to_euler()


def preview():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(MODEL/"HachimoDock-R03.glb"))
    meshes = {o.name:o for o in bpy.context.scene.objects if o.type == "MESH"}
    inventory = json.loads((MODEL/"inventory.json").read_text(encoding="utf-8"))
    assert set(meshes) == set(inventory["parts"]) | set(inventory["reference_parts"])
    scene = bpy.context.scene
    scene.world = bpy.data.worlds.new("Studio")
    scene.world.use_nodes = True
    scene.world.node_tree.nodes["Background"].inputs[1].default_value = .42
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 12
    scene.cycles.use_denoising = True
    scene.render.threads_mode = "FIXED"
    scene.render.threads = 2
    scene.render.resolution_x = 920
    scene.render.resolution_y = 780
    scene.render.film_transparent = True
    scene.render.image_settings.color_mode = "RGBA"
    scene.view_settings.view_transform = "AgX"
    scene.view_settings.exposure = -2.4

    for xyz, power, size in (((-.18,-.25,.35),15,.26),((.2,-.09,.19),7,.18),((.05,.2,.28),9,.18)):
        light = bpy.data.lights.new("Softbox","AREA")
        light.energy, light.shape, light.size = power,"DISK",size
        obj = bpy.data.objects.new("Softbox",light)
        scene.collection.objects.link(obj)
        aim(obj,xyz,(0,0,.075))
    data = bpy.data.cameras.new("CAD camera")
    data.type = "ORTHO"
    camera = bpy.data.objects.new("CAD camera",data)
    scene.collection.objects.link(camera)
    scene.camera = camera

    board = Image.new("RGB",(1904,1020),"#F4F6F7")
    d = ImageDraw.Draw(board)
    d.text((34,24),"HachimoDock R03",fill="#182124",font=ImageFont.load_default(size=52))
    d.text((36,91),"SPRING RETURN  /  CAD AT 76 MM NOMINAL PAD GAP",fill="#49585D",
           font=ImageFont.load_default(size=25))
    views = [
        ("01   Assembled", (.23,-.33,.25),(0,.005,.072),.270,False),
        ("02   Cover removed", (.014,-.145,.227),(0,-.021,.092),.170,True),
    ]
    with TemporaryDirectory() as tmp:
        for i,(title,pos,target,scale,open_cover) in enumerate(views):
            for name,obj in meshes.items():
                obj.hide_render = open_cover and (name[:2] in {"01","02","03","07","08","09","10"}
                                                   or name.startswith("REF_Cover_Screw"))
            aim(camera,pos,target)
            data.ortho_scale = scale
            render = Path(tmp)/f"{i}.png"
            scene.render.filepath = str(render)
            bpy.ops.render.render(write_still=True)
            x = 24 + 936*i
            with Image.open(render) as image:
                rgba = image.convert("RGBA")
                board.paste(rgba,(x,135),rgba)
            d.text((x+12,905),title,fill="#182124",font=ImageFont.load_default(size=31))
    d.text((36,965),"Blue / amber: opposed racks    |    Teal: spring envelopes    |    Gray: pinion and pins",
           fill="#49585D",font=ImageFont.load_default(size=23))
    board.save(MODEL/"R03-Clamp-Preview.png")


if __name__ == "__main__":
    preview()
