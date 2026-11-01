# Folder transition semantics

App grouping is immediately ready on entering an eligible icon's center (the inner
70% of its physical bounds). One platform CONFIRM callback runs before the 240 ms
preview morph starts. Recognition, dwell, animation completion and swap grace impose
no eligibility delay. Original full icon bounds retain the candidate to prevent jitter.
A switch emits one CLOCK_TICK exit followed by one CONFIRM for the new target.
Repeated frames are silent. View.performHapticFeedback respects system settings.

Pointer-up refreshes geometry, including a first entry into the center on that event.
Only release requests a mutation; authoritative source, target and installed-app
validation still decide whether it commits. Fast passes do not group. Scroll, occlusion,
removed targets, cancellation and rejected validation disarm once. Successful release
consumes readiness without a cancel pulse. Animation callbacks never arm a target.
The opaque lifted icon hands off continuously to the momentum landing. The preview
continues from its current progress on early release and owns the target until both
landing and morph complete, preventing a full-size target snap or opaque duplicate.

Open-panel member gestures have no press scale or indication. The containing panel
also has no indication: its empty click handler only blocks the dismissal scrim.
Launch, accessibility actions, long press, drag and lift feedback remain available.
There is no instructional text. Folder opening and closing and transfer flight remain.

Small-folder singleton replacement is one atomic Edits/LayoutStore change, preserving
placed ID, top-left cell and surviving occurrence provenance. Empty folders remain
folders. Large folders also retain one occurrence, its absolute
slot, placed ID and 2 × 2 footprint, without collapse provenance. Large folders need
at least two occurrences to shrink; duplicate apps count independently. The
presentation snapshot owns the survivor until completion; the real app is hidden.
Cleanup transfers a held/returning survivor to that owner before its source disappears.
Incoming landing and source collapse have independent owners and timelines.

The 340 ms presentation has two contiguous phases with separate fast-out-slow-in easing:

- 0–180 ms: the current surface closes to the regular icon's square at the old folder's
  top-left cell, rounding to a circle. The surviving icon settles into the centered
  38% miniature used by a one-member compact folder. Open panel scrim and title close
  in this phase.
- 180–340 ms: the centered miniature grows to the normal desktop icon while the closed
  folder surface disappears. There is no hold, traveling ghost or survivor opacity fade.

Live measured app bounds are sampled on each animation tick. Incremental interpolation
advances from the last rendered frame rather than reprojecting a fixed origin when
scroll/reflow changes the destination. A slow frame that spans the boundary still passes
through the singleton geometry mathematically. Compose's motion duration scale applies
to both phases; disabled motion completes directly without persisting an intermediate
singleton. New drag ownership or authoritative replacement removes the snapshot, and
identity-checked effect cleanup cannot delete a newer collapse.

Folder app drops mark the resulting cell with the desktop landing treatment: 4dp
inset, 14% fill, 45% outline and a 1.5dp stroke. Surface-relative color keeps the
frame legible in both themes. Drawing behind the cell preserves icon measurement,
gesture ownership and lazy placement; the panel grid clips the frame while scrolling.
Large slots use the same continuous rounded frame primitive and tokens. They have
no fill and draw entirely behind the opaque drag icon, without backing or a second
outline. Their feedback box is the settled lifted icon diameter (1.12 × icon size) plus 40dp,
centered on the unchanged slot center. The existing 4dp inset and 1.5dp stroke leave
14.5dp clear inside the outline on each side of a centered lifted icon, also enclosing the tested 28%-of-cell
offset without moving the slot center. Feedback
retargets over 100ms and may overflow the dense slot; hit geometry, final member
size, landing center and the folder footprint stay fixed. Their radius is one quarter
of the launcher icon size, matching the smaller cell scale. The shared primitive caps rounding at 40% of the inset
frame’s shorter side to retain straight edges instead of clamping into a capsule;
the open panel retains its accepted half-icon radius below that cap. Cell bounds and sparse positions remain unchanged.
The preview's order and occurrence ID come from the same transfer plan as release,
including fifth-item reflow, duplicate apps, sparse large slots and swaps. Invalid
plans have no landing frame. Release removes the frame immediately while retaining
the pending order captured at release through validation; panel edge scrolling stops
on release, and cancel restores the original order. Adding to an empty small folder outlines
the resulting standalone app cell at the folder's stored top-left position. Empty
large folders instead mark and retain the exact requested absolute slot. Large-slot
frames are clipped out of any overlapping foreground folder panel.
