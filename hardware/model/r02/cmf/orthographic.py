"""Create equal-scale raster line views from the original STEP assembly."""

import argparse
import io
from pathlib import Path

import cadquery as cq
import cairosvg
from cadquery.occ_impl.exporters.svg import getSVG
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[3]
REGULAR = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
BOLD = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"


def line_view(solid, projection, upright=False):
    bb = solid.BoundingBox()
    # OCCT's default frame puts world Z along screen X for front/side projections.
    projected_width = bb.zlen if upright else bb.xlen
    svg = getSVG(solid, {"projectionDir":projection, "showAxes":False,
                        "showHidden":False, "strokeColor":(55,58,63),
                        "strokeWidth":.4, "width":projected_width*3.15+80,
                        "height":None, "marginLeft":40, "marginTop":40})
    raw = cairosvg.svg2png(bytestring=svg.encode(), background_color="#ffffff")
    with Image.open(io.BytesIO(raw)) as image:
        result = image.convert("RGB")
    return result.rotate(-90,expand=True) if upright else result


def draw_sheet(model, out):
    shape = cq.importers.importStep(str(model/"HachimoDock-R02.step")).val()
    assert shape.isValid() and len(shape.Solids()) == 9
    out.mkdir(parents=True, exist_ok=True)
    work = out/"work"
    work.mkdir(parents=True, exist_ok=True)
    board = Image.new("RGB", (1800,2400), "white")
    draw = ImageDraw.Draw(board)
    title = ImageFont.truetype(BOLD, 66)
    label = ImageFont.truetype(REGULAR, 27)
    small = ImageFont.truetype(REGULAR, 25)
    draw.text((110,105), "HachimoDock", fill="#25272A", font=title)
    draw.text((110,207), "R02  /  六向正投影", fill="#70747A", font=label)
    draw.line((110,285,1690,285), fill="#D8DADE", width=2)
    views = [
        ("front","01  正视 / FRONT",shape,(0,-1,0),True),
        ("rear","02  后视 / REAR",shape.rotate((0,0,0),(0,0,1),180),(0,-1,0),True),
        ("left","03  左视 / LEFT",shape.rotate((0,0,0),(0,0,1),90),(0,-1,0),True),
        ("right","04  右视 / RIGHT",shape.rotate((0,0,0),(0,0,1),-90),(0,-1,0),True),
        ("top","05  俯视 / TOP",shape,(0,0,1),False),
        ("bottom","06  仰视 / BOTTOM",shape,(0,0,-1),False),
    ]
    for i, (name,text,solid,projection,upright) in enumerate(views):
        view = line_view(solid,projection,upright)
        view.save(work/f"line-{name}.png")
        x, y = 110+(i%2)*825, 350+(i//2)*625
        board.paste(view, (x+(755-view.width)//2, y+(555-view.height)//2))
        draw.text((x+25,y+560), text, fill="#4B4F55", font=label)
        print(f"PROJECTED {name}", flush=True)
    draw.line((110,2255,1690,2255), fill="#D8DADE", width=2)
    draw.text((110,2290), "同一 CAD · 同比例 · 不含手机、软垫与外购件", fill="#747980", font=small)
    board.save(out/"01-orthographic.png")
    print(f"CREATED {out/'01-orthographic.png'}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model",type=Path,default=ROOT.parent/"model")
    parser.add_argument("--out",type=Path,default=REPO/"assets/r02-cmf")
    args=parser.parse_args()
    draw_sheet(args.model,args.out)
