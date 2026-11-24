# R02 CAD Validation

Date: 2026-11-24. These are digital geometry checks, not a physical test report.

## Results

Both configurations were rebuilt from their JSON files and passed `verify.py`:

| Configuration | Target phone widths | Phone thickness envelope | Base footprint |
|---|---|---|---|
| `dimensions.json` | 62 / 76 / 90 mm | 18 mm | 150 x 154 mm |
| `dimensions-example.json` | 60 / 82 / 94 mm | 20 mm | 156 x 160 mm |

For each configuration:

- Nine parts are valid, single B-Rep solids.
- All nine individual STEP exports reopen and match source volume and dimensions.
- All nine STL exports are connected, watertight, consistently wound volumes,
  placed on Z=0; exported dimensions and volume match the source within tolerance.
- Three phone-width envelopes clear all printed parts.
- Side-pad gaps and locking-screw shafts clear at all three clamp positions.
- The declared servo body envelope clears the relieved base mounting posts.
- The hollow-spindle cable probe clears the lid, rotor, and driven pulley.
- Moving printed parts clear fixed printed parts at yaw samples
  -40, -20, 0, 20, and 40 degrees for all three clamp positions.
- Width-configuration assemblies and the meter/Y-up GLB reopen successfully.

`python -m unittest test_model.py -v` passed all six regression tests. These cover
servo clearance, extended slider travel, dimensions independent of preview
tessellation, inventory, width assemblies, and GLB units/part count.

Six actual-CAD views were rendered and visually inspected. The gray phone is a
reference envelope and is excluded from every exported CAD/STL part.

## Corrections Made During Verification

- Aligned the screw-check probe to the slide-clearance-dependent hole center.
- Relieved servo mounting posts to avoid the declared body envelope.
- Moved locking-hole positions automatically for larger clamp travel, preventing
  slider plates from overlapping at the smallest phone width.
- Measured exact B-Rep bounds without cached display triangles, preventing
  tessellation tolerances from changing dimensional metadata.

## Limits

No physical print, printer-tolerance calibration, actual-SG90 fit, belt tooth/tension
test, complete-electronics/fastener fit, stress analysis, tip-over test, motor thermal
test, continuous swept-motion proof, or flexible-cable routing test was performed.
The servo envelope is a declared design assumption, not a measurement of the user's
servo. Native SolidWorks import, features, mates, and motion have not been tested.
Android and ESP32 control software were not changed for the single-axis 2:1 drive.

The supplied STL kit uses the default configuration. The alternative configuration
was tested in a temporary output directory; rebuild it separately if required.
