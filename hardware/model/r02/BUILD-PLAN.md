# R02 CAD Delivery Implementation Plan

> **For agentic workers:** Use the existing CadQuery model, focused regression tests,
> and independent review. Preserve unrelated workspace changes.

**Goal:** Deliver the reference-image phone dock as editable STEP solids,
parameterized source, and individually printable STL parts with adjustable jaws.

**Architecture:** Retain the 45-degree wedge, independently sliding jaws, hollow
spindle, bearing, and single-axis belt-driven turntable. Export dimensional metadata
and multiple jaw positions from one configuration; render previews from those same
CAD solids, not from the original concept scene.

**Tech Stack:** Python 3.11, CadQuery 2.6.1, trimesh 5.2.2, Blender 4.5 for previews.

## Tasks

- [x] Inspect existing source, configuration, nine generated parts, and reference.
- [x] Reopen current STEP/STL and run default geometry verification.
- [x] Add export regression tests before extending the exporter.
  Run `python -m unittest test_model.py -v`; confirm missing manifest/configuration
  outputs cause failures before adding them.
- [x] Export named assemblies at minimum, preview, and maximum clamp widths;
  a millimeter-based inventory; and a meter/Y-up GLB preview of the actual CAD.
- [x] Verify exported geometry against source dimensions and volume, not only
  file existence, and sample turntable yaw at all three clamp widths.
- [x] Rebuild a second configuration with a 60-94 mm clamp range and 20 mm
  phone-thickness envelope; run its complete verification in a temporary directory.
- [x] Render actual-CAD orthographic, assembly, and exploded previews; visually
  inspect generated images.
- [x] Resolve independent review findings within the model's scope.
- [x] Document adjustable-clamp operation, dimensional edits, purchased hardware,
  print orientation, assembly, and STEP/native-SolidWorks limitations.
- [x] Package source, models, previews, and verification results; test ZIP integrity.

## Acceptance

Every printable part must be one valid solid and a connected, consistently wound,
watertight STL. Exported geometry must match source geometry. Clamp extremes must
clear the phone envelope and accept their locking screws. Sampled yaw positions
must clear fixed printed parts. CAD checks do not establish belt fit, motor torque,
cable flexibility, stability, or real printer tolerances.

Native `.SLDPRT` / `.SLDASM` files are not available without SolidWorks. STEP is
the editable geometry exchange delivery; JSON and Python retain source parameters.
