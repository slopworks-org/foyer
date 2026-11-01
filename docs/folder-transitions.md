# Folder transition semantics

Grouping feedback follows actual drop readiness: the same candidate must remain eligible
for 320 ms and its preview must finish. Entering hover or reaching the dwell deadline
alone is silent. Readiness emits one platform CONFIRM; leaving armed readiness emits
one quieter CLOCK_TICK. View.performHapticFeedback respects system haptic settings.
There is no vibrator override or new visible instruction.

A target switch disarms before starting the new dwell. Scroll, occlusion, cancellation,
removed targets and rejected validation disarm once. Preview animation callbacks never
emit feedback, so stale callbacks cannot arm a different candidate. Accepted release
retains readiness while validation runs and consumes it silently on commit; rejection
exits normally. The injectable callback receives these same readiness edges for tests.

Singleton replacement is one atomic Edits/LayoutStore change, preserving placed ID,
top-left cell and surviving occurrence provenance. Empty folders remain folders. The
presentation snapshot owns the survivor until completion; the real app is hidden.
Cleanup transfers a held/returning survivor to that owner before its source disappears.
Incoming landing and source collapse have independent owners and timelines.

The 340 ms presentation has two contiguous phases with separate fast-out-slow-in easing:

- 0–180 ms: the current surface closes to the regular icon's square at the old folder's
  top-left cell, rounding to a circle. The surviving icon settles into the centered
  38% miniature used by a one-member compact folder. Open panel scrim and title close
  in this phase. Large and sparse folders use this same coherent compact endpoint.
- 180–340 ms: the centered miniature grows to the normal desktop icon while the closed
  folder surface disappears. There is no hold, traveling ghost or survivor opacity fade.

Live measured app bounds are sampled on each animation tick. Incremental interpolation
advances from the last rendered frame rather than reprojecting a fixed origin when
scroll/reflow changes the destination. A slow frame that spans the boundary still passes
through the singleton geometry mathematically. Compose's motion duration scale applies
to both phases; disabled motion completes directly without persisting an intermediate
singleton. New drag ownership or authoritative replacement removes the snapshot, and
identity-checked effect cleanup cannot delete a newer collapse.
