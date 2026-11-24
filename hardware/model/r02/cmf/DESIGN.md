# R02 Silver / Graphite CMF Rendering

## Approved Direction

The user requested Apple-inspired visual restraint and approved a silver/white
primary finish plus a graphite comparison. Translate neutral material grouping,
soft studio reflections, quiet typography, and black functional areas without
copying an Apple logo or a specific product silhouette.

## State Card

- CONFIRMED: desktop phone dock; existing R02 CAD; 45-degree wedge; adjustable jaws.
- LOCKED: nine CAD parts, dimensions, interfaces, part boundaries, existing files.
- LOCKED: silver body, white jaws, dark turntable; graphite comparison.
- ASSUMED: finish study, not a new manufacturing process or aluminum conversion.
- UNKNOWN: coating supplier, cost, scratch resistance, final print surface quality.
- Rendering-only props: example phone, fitted screw heads, and foam pads.

## Implementation Plan

**Goal:** Three consistent raster images using the exact source CAD.

**Architecture:** Read the existing GLB; assign two explicit palettes in Blender.
Render a paired hero and isolated component views from the same meshes. Compose
the orthographic sheet and CMF board with Pillow. Store source hashes in a manifest
and confirm no existing STEP, STL, GLB, or dimensions file changed.

**Tech Stack:** Blender bpy 4.5, Pillow, Python unittest.

- [x] Verify palette assignment, CAD mesh preservation, and framing.
  Run `python -m unittest test_render.py -v` in the Blender Python environment.
- [x] Add `render.py` without changing `build.py` or `render_preview.py`.
  Import `model/HachimoDock-R02.glb`; require exactly the nine inventory part names.
- [x] Render and inspect a reduced hero before rendering final-resolution output.
- [x] Render the silver front and graphite rear together, then check framing.
- [x] Render isolated silver and graphite views. Crop the paired render for
  the jaw, turntable, and cable-exit details.
- [x] Use same-scale orthographic CAD line views on white, with correct LEFT/RIGHT.
- [x] Compose exactly `01-orthographic.png`, `02-design-board.png`, `03-hero.png`.
  Final sheets are 1800 x 2400 pixels; intermediate renders are not final deliverables.
- [x] Run tests, inspect the three sheets, verify image dimensions and contrast,
  compare all tracked CAD hashes, and package the three final images.

Delivery checks: six tests passed; all 25 tracked CAD source and metadata files
matched Git HEAD byte-for-byte. The orthographic PNG also matched a fresh
projection byte-for-byte. The three final PNGs passed the ZIP integrity check.

## Rebuild

Run from the repository root with `uv` and Noto Sans CJK installed:

```bash
cmf=hardware/model/r02/cmf
uv run --no-project --with-requirements "$cmf/requirements-cad.txt" python "$cmf/orthographic.py"
uv run --no-project --with-requirements "$cmf/requirements.txt" python "$cmf/render.py" --view hero --samples 20
uv run --no-project --with-requirements "$cmf/requirements.txt" python "$cmf/render.py" --view silver --samples 20 --scale .8
uv run --no-project --with-requirements "$cmf/requirements.txt" python "$cmf/render.py" --view graphite --samples 20 --scale .48
for view in front side rear; do
  uv run --no-project --with-requirements "$cmf/requirements.txt" python "$cmf/render.py" --view "$view" --samples 20 --scale .45
done
uv run --no-project --with-requirements "$cmf/requirements.txt" python "$cmf/compose.py"
```

The three delivery PNGs are in `assets/r02-cmf/` and bundled in
`HachimoDock-R02-design.zip`. The `work/` directory holds intermediate images.
See `manifest.json` for the settings and source hashes of the delivered run.

To check the renderer, compositor, and orthographic projector:

```bash
cmf=hardware/model/r02/cmf
for suite in test_render.py test_compose.py; do
  uv run --no-project --with-requirements "$cmf/requirements.txt" python -m unittest discover -s "$cmf" -p "$suite" -v
done
uv run --no-project --with-requirements "$cmf/requirements-cad.txt" python -m unittest discover -s "$cmf" -p test_orthographic.py -v
```

Keep the two dependency environments separate: Blender and CadQuery require
different NumPy versions.

## Quality Boundaries

No deleted holes, thinner base, relocated ports, manufactured-material claims,
new electronics, unverified performance copy, or Apple branding. Shader normal
softening is a finish visualization only; it does not change the CAD vertices.
Do not overwrite the previous orange CAD preview or reference images.
