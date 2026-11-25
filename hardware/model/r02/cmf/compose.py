"""Compose three restrained product-study sheets from the actual CAD renders."""

import argparse
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[3]
BG = "#F5F5F7"
INK = "#26282C"
SECONDARY = "#666B73"
RULE = "#D9DBDF"
REGULAR = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
BOLD = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"


def font(size, bold=False):
    return ImageFont.truetype(BOLD if bold else REGULAR, size)


def picture(path, background=BG):
    with Image.open(path) as image:
        rgba = image.convert("RGBA")
    # The shadow catcher extends past the camera frame; taper it into the paper.
    alpha = rgba.getchannel("A")
    for length, ratio, horizontal in ((rgba.width, .06, True), (rgba.height, .025, False)):
        margin = max(1, round(length*ratio))
        ramp = Image.new("L", (length,1) if horizontal else (1,length))
        weights = [min(i,length-1-i,margin)/margin for i in range(length)]
        ramp.putdata([round(255*t*t*(3-2*t)) for t in weights])
        alpha = ImageChops.multiply(alpha, ramp.resize(rgba.size, Image.Resampling.NEAREST))
    rgba.putalpha(alpha)
    flat = Image.new("RGBA", rgba.size, background)
    flat.alpha_composite(rgba)
    return flat.convert("RGB")


def paste_fit(board, image, box):
    x,y,w,h = box
    fitted = image.copy()
    fitted.thumbnail((w,h), Image.Resampling.LANCZOS)
    board.paste(fitted,(round(x+(w-fitted.width)/2),round(y+(h-fitted.height)/2)))


def label(draw, xy, text, size=28, color=SECONDARY, bold=False):
    draw.text(xy,text,font=font(size,bold),fill=color)


def swatches(draw, x, y, colors, size=50):
    for i, color in enumerate(colors):
        left = x + i*(size+19)
        draw.ellipse((left,y,left+size,y+size),fill=color,outline="#C9CCD1",width=2)


def detail_crop(image, center):
    x, y = (round(fraction*extent) for fraction, extent in zip(center, image.size))
    half_w = round(image.width*.074)
    half_h = round(half_w*106/150)
    return image.crop((x-half_w, y-half_h, x+half_w, y+half_h)).resize(
        (300,212), Image.Resampling.LANCZOS
    )


def create_hero(out):
    board=Image.new("RGB",(1800,2400),BG)
    draw=ImageDraw.Draw(board)
    label(draw,(110,135),"HachimoDock",85,INK,True)
    label(draw,(114,266),"桌面手机底座",37)
    label(draw,(114,338),"R02   /   SILVER + GRAPHITE",26)
    render=picture(out/"work/hero.png")
    paste_fit(board,render,(0,430,1800,1740))
    draw.line((110,2220,1690,2220),fill=RULE,width=2)
    label(draw,(110,2260),"雾银 × 陶瓷白",30,INK)
    label(draw,(1130,2260),"石墨灰 × 银色",30,INK)
    label(draw,(110,2320),"现有 CAD 的配色与表面效果研究",24)
    board.save(out/"03-hero.png")


def create_design_board(out):
    board=Image.new("RGB",(1800,2400),BG)
    draw=ImageDraw.Draw(board)
    label(draw,(110,105),"HachimoDock",67,INK,True)
    label(draw,(112,204),"R02  /  材质与配色",31)
    draw.line((110,285,1690,285),fill=RULE,width=2)
    label(draw,(110,350),"01  /  SILVER + WHITE",26)
    label(draw,(110,410),"雾银 / 陶瓷白",42,INK,True)
    label(draw,(110,497),"银色主体，低光泽白色夹臂。",26)
    label(draw,(110,545),"深灰转盘收束视觉层级。",26)
    swatches(draw,115,640,("#BFC3C7","#EEEFF0","#55595F"))
    label(draw,(110,728),"细哑光 · 柔和反射",27)
    label(draw,(110,881),"150 × 154 mm  底座外廓",27,INK)
    label(draw,(110,932),"62–90 mm  目标手机宽度",27,INK)
    paste_fit(board,picture(out/"work/silver.png"),(610,310,1090,865))
    draw.line((110,1180,1690,1180),fill=RULE,width=2)
    views = (
        ("front","正面","双侧滑动夹臂"),
        ("side","侧面","45° 固定斜撑"),
        ("rear","背面","底座出线口"),
    )
    for i,(name,title,note) in enumerate(views):
        x=110+i*533
        paste_fit(board,picture(out/f"work/{name}.png"),(x,1208,510,395))
        label(draw,(x+20,1620),title,28,INK,True)
        label(draw,(x+20,1665),note,24)
    draw.line((110,1740,1690,1740),fill=RULE,width=2)
    label(draw,(110,1785),"02  /  石墨灰 + 银色",33,INK,True)
    paste_fit(board,picture(out/"work/graphite.png"),(100,1850,520,357))
    swatches(draw,120,2205,("#46494F","#555961","#A5A9AE"),size=36)
    label(draw,(695,1785),"结构细节",33,INK,True)
    hero = picture(out/"work/hero.png")
    details = (
        ((.302,.541),"滑动夹臂","夹距可调，螺钉锁紧"),
        ((.379,.583),"深灰单轴转盘","与银色上盖分区"),
        ((.551,.651),"背部走线口","底座预留线缆开口"),
    )
    for i,(center,title,note) in enumerate(details):
        x=695+i*345
        board.paste(detail_crop(hero,center),(x,1895))
        label(draw,(x,2130),title,27,INK,True)
        label(draw,(x,2180),note,23)
    draw.line((110,2275,1690,2275),fill=RULE,width=2)
    label(draw,(110,2320),"表面效果示意；不改变 CAD 结构或制造材料。",24)
    board.save(out/"02-design-board.png")


if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--out",type=Path,default=REPO/"assets/r02-cmf")
    args=parser.parse_args()
    create_hero(args.out)
    create_design_board(args.out)
    print("COMPOSED 02-design-board.png and 03-hero.png",flush=True)
