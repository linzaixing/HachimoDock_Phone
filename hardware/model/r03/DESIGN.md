# R03 spring-return clamp

Approved direction: pull either jaw to open both sides equally, then release to
clamp a phone. Two opposed racks and a central pinion establish the centerline;
two enclosed extension springs provide the return force. The lower toes carry
the phone weight. Keep the R02 bottom assembly, 6805 bearing, 45-degree phone
pose, and USB-C cable entry. Do not overwrite R02 CAD or its finish study.

## Design and acceptance

- 62–90 mm target phone width (including case), 18 mm target thickness, 1 mm
  pads per side. Design hard-stop positions are 60 and 94 mm pad-to-pad to give
  preload at the narrow phone and insertion room at the wide phone.
- Replace the wedge and jaws, and add a front cover and pinion. Keep the six
  other printable parts on the R02 dimensions. Mechanically retain the sliders
  at both ends; fasteners must not squeeze the sliding parts.
- Put the springs above and below the rack pair under the cover. Include
  purchased parts as reference envelopes, separate from print files. Select springs by measured
  force, available extension, and hook fit, not from the CAD image.
- Sample mesh clearance, hard stops, phone/pad alignment, spring cavity and
  cable entry; export editable STEP, print STLs and an inspectable CAD preview.
  Actual clamp force, insertion feel, printer-fit calibration, cable routing and
  reliability remain bench-test items.

## Implementation plan

1. Add `test_model.py` for R02 part reuse, contact locations, rack travel,
   gear and spring clearances, stop capture, and the cable opening; observe
   failure before adding the R03 builder.
2. Add `build.py` using R02's CadQuery helpers; model the local clamp before
   transforming it to the existing 45-degree phone plane. Export 60, 62, 76,
   90 and 94 mm named configuration STEP files plus STL and GLB.
3. Reopen and check the exports; generate a readable plan view of the open
   mechanism and document the hardware, assembly, and bench checks in `README.md`.
