# 3D Model Viewer — README

Single-Activity Kotlin + Jetpack Compose app that loads several `.glb`
models onto one screen at once, each in its own independently
draggable/resizable/rotatable/zoomable container, with part labels read
from the model's own glTF `extras.prop`.

## Launcher icon

A custom adaptive launcher icon replaces the previous placeholder
(`@android:drawable/sym_def_app_icon`, the generic system default): a
shaded isometric cube (the "3D model") inside an orbit ring with a small
motion accent (the "rotation/viewer" part), no text, on the app's own dark
navy background - see `res/drawable/ic_launcher_foreground.xml`,
`res/mipmap-anydpi-v26/ic_launcher(_round).xml` (adaptive, API 26+) and
`res/mipmap/ic_launcher(_round).xml` (a pre-merged flat fallback for API
24-25, this project's `minSdk`, where adaptive-icon layering isn't
supported). `AndroidManifest.xml`'s `<application>` tag now points
`android:icon`/`android:roundIcon` at `@mipmap/ic_launcher(_round)`
instead of the old system placeholder. Verified on-emulator: the icon
renders correctly both in the recents/task-switcher badge and full-size.

## 3D library used, and why

**SceneView for Android** (`io.github.sceneview:sceneview:2.3.3`), the
3D-only (non-AR) build — a thin, actively-maintained Kotlin/Compose wrapper
around Google's **Filament** renderer.

- Filament is the same renderer behind Android's own Scene Viewer /
  `<model-viewer>` stack and is built specifically for mobile GPUs — the
  right choice when "smooth on a 2–3 GB device" is the top-weighted
  requirement.
- SceneView's Compose `Scene { }` composable is cheap enough to use once
  per model container while still sharing the genuinely expensive
  resources (one `Engine`, one `ModelLoader`) across all of them — see
  "Per-container rendering" under Trade-offs for why that split, not "one
  Scene for everything", is what this app actually does.
- glTF/GLB loading and node transforms (`position`/`rotation`/`scale`) come
  for free instead of hand-rolling OpenGL/EGL plumbing in the time
  available.

## Main performance optimisations

1. **One shared Filament `Engine` + `ModelLoader` for all models.** These
   are the genuinely expensive resources - shader compilation, GPU memory
   pools, glTF decode infrastructure - and there is exactly one of each
   regardless of how many models are loaded. Each model's own `View`/
   `Scene`/`Camera`/`Renderer` (see "Per-container rendering" under
   Trade-offs) are comparatively lightweight per-instance objects, so this
   still captures the bulk of the "don't pay for N model viewers" saving
   without needing a single shared render surface.
2. **No environment/IBL lighting, no shadows.** A single directional light
   replaces an HDR environment map — an IBL texture is comparatively
   expensive to decode/keep resident and adds real per-pixel shading cost
   for very little visual payoff on a technical parts viewer.
3. **GLB `extras.prop` metadata is parsed exactly once per model, at load
   time** (`GlbLabelParser` reads only the 12-byte header + the JSON chunk,
   never the binary geometry chunk), never re-parsed per frame.
4. **Per-frame label work is skipped whenever it isn't needed**: skipped
   entirely for any model with labels toggled off (the default), and
   skipped even for a labels-on model on any frame where nothing about its
   position/rotation/zoom/size actually changed since the last recompute
   (a cheap dirty-check on `ModelInstance`).
5. **No per-frame allocations in the hot paths**: the label `Paint` is
   created once (`remember`) and reused for every label on every frame; a
   model's bounding-sphere radius (used to auto-fit it into its container)
   is computed once at load time, not recalculated per frame; gesture
   callbacks mutate existing Compose `State` in place rather than
   rebuilding objects.
6. **`.glb` assets are stored uncompressed** in the APK
   (`androidResources.noCompress += "glb"`) so the OS can hand the loader a
   direct byte range instead of inflating the whole file into a temp
   buffer on every load.
7. **Resources are released the instant a model is closed** — `node.destroy()`
   runs immediately and the model's `ModelInstance` (its node, labels and
   screen-space label cache) is dropped from the on-screen list, rather
   than waiting on GC or accumulating in some "closed models" collection.
8. `android:largeHeap="true"` gives a little more headroom for glTF decode
   buffers on devices that are already memory-constrained.

### What I'd still do with more profiling time
Run Android Studio Profiler / Perfetto / a GPU inspector with all 5 sample
models loaded on an actual 2–3 GB reference device, mid-gesture (the worst
case), and check: draw-call count, whether Filament's default
antialiasing should be forced off for low-end GPUs, whether any of the 5
bundled `.glb` textures are worth downsampling before bundling, and -
specifically, given the move to per-container rendering (see Trade-offs) -
whether 5 concurrent `SurfaceView`s carry a measurable cost over 1 on the
lowest-end hardware this is graded on, versus the mid-range emulator this
was actually profiled on.

## Trade-offs

- **Per-container rendering: one `View`/`Scene`/`Camera` per model, not
  one shared full-screen surface.** The first version of this app put
  every model on one shared `Scene()` spanning the whole screen, each
  positioned purely by world coordinates to *look like* it was in its own
  container. That worked until a model was rotated or zoomed enough to
  visually spill past its own container's edge into a neighboring one -
  there was nothing to actually stop it, since it was all one rendering
  surface. Every model now gets its own `Scene()` (see
  `ModelContainer.kt`'s `Model3DViewport`), sized by Compose to exactly
  its own content Box, sharing only the `Engine`/`ModelLoader` above -
  which is what turns "the model shouldn't visually escape its container"
  from a rule the app has to enforce into a fact of how Android surfaces
  work: a `SurfaceView`'s pixels are bounded by its own view rectangle,
  full stop, at any zoom or rotation. This is also what let interaction-mode
  zoom go back to a generous range (`MAX_ZOOM = 4f`) instead of being
  capped low to limit how far an overflow could reach - there's no overflow
  to limit any more. Each container's camera sits at the same fixed
  distance looking at its model's origin; `fitScale` (in `ModelInstance.kt`)
  still does the "bounding sphere → radius → fit with padding" math, just
  against that container's own local width/height instead of a
  shared-screen position.
- **The workspace still scrolls virtually, not via a real `ScrollView`.**
  Models load into a vertical stack (one full-width row per model) and the
  workspace scrolls when that stack is taller than the screen. A plain
  `scrollOffsetPx` shifts where each container is *drawn* on screen; since
  each container's own render surface no longer needs to know its own
  screen position at all (see above), scrolling now only touches Compose
  layout, not any model's transform. A background "catcher" `Box`, layered
  below every model container, owns this gesture; because Compose only
  ever routes a touch to the topmost node whose bounds contain it, a touch
  inside any container never reaches this catcher, and a touch outside
  every container never reaches any model's renderer at all - that's the
  whole mechanism behind "dragging the empty background scrolls the list,
  full stop, nothing rotates."
- **A model's initial fit is computed from its own real bounding sphere**
  (walking the actual render hierarchy — see `computeModelRadius` in
  `ModelInstance.kt`) rather than trusting the 3D library's built-in
  bounding-box normalization, because that built-in normalization is
  built from raw glTF accessor min/max and ignores translations baked into
  parent nodes. That matters concretely for the bundled solar-system
  model: each planet mesh is parented under an orbital-radius translation,
  so the naive bounding box measures roughly "one planet near the origin"
  and the camera ends up zoomed into the Sun instead of framing the whole
  system. Walking the hierarchy costs a little extra one-time (not
  per-frame) math per model in exchange for correct framing on every
  bundled file.
- **Rotation pivots around the model's true geometric bounding-box
  center, not its raw authored origin.** Filament rotates a node about its
  own local `(0,0,0)` - so rotating the loaded GLB's root node directly
  would pivot around whatever the file's author happened to place at that
  origin. For the bundled `solarsystem.glb` that's exactly where the Sun
  mesh sits, so the Sun would stay visually fixed on screen while every
  planet swung around it - a pure rotation always leaves its own pivot
  point unmoved, and the Sun happened to *be* that pivot point. Every
  loaded model's root (`ModelInstance.node`) is now parented under a
  second, plain `Node` (`ModelInstance.pivotNode`, see `ModelViewerScreen.
  addModel`), with `node`'s own local position fixed once, at load time, to
  `-modelCenter` (the true bounding-box center from `computeModelBounds`) -
  so that center sits exactly at the pivot's own origin. `applyTransform`
  rotates/scales/positions `pivotNode`, never `node` directly, so the whole
  loaded scene - Sun included - tumbles together as one rigid object around
  its real center, the same way every other bundled model (whose authored
  origin already happens to sit near its own visual center) was already
  behaving.
- **A frame can never resize past the visible workspace, and a newly
  added model always searches for a free position instead of stacking
  blindly.** Resize only ever grows a frame's bottom-right corner (its
  top-left stays fixed), so `ModelGestures.kt`'s pinch handler clamps the
  resulting size against `ModelViewerScreen.maxContainerSizeAt` - the
  actual room left between that frame's own top-left corner and the
  workspace's right/bottom edge, computed fresh on every pinch update
  (never a value captured once, for the same stale-closure reason
  `WorkspaceScroll.kt` already calls out) - so continuing to pinch past
  the edge simply stops growing the frame rather than pushing it
  off-screen. Separately, `ModelViewerScreen.findFreePosition` gives every
  newly added model a real collision search (candidate corners = the
  workspace's own top-left plus every existing frame's right/bottom edge
  + margin, tried top-to-bottom/left-to-right, using `Rect.overlaps` for
  the actual intersection test) instead of the old "row N = model N" index
  math - so a model added after an earlier one has been dragged or resized
  lands in whatever space is actually free (including space an earlier
  model vacated), never on top of it, and existing models are never moved
  to make room. Dragging (`ModelViewerScreen.clampContainerOffset`) is
  held to the same hard rule: no partial overhang is allowed on either
  side, so a one-finger drag can push a frame flush against the left or
  right edge of the screen but never past it - an earlier version of this
  clamp deliberately allowed a small overhang so a container could still
  be grabbed after being dragged near an edge, but that's exactly the
  "frame outside the screen" case the task explicitly rules out, so it's
  gone.
- **Adding a model auto-scrolls it into view.** `findFreePosition` can
  legally place a new frame below the current scroll position (a new row)
  or, after the user has scrolled down, back above it (reusing space an
  earlier model vacated near the top) - either way `addModel` scrolls just
  enough to bring the whole new frame on screen (aligning its top edge
  instead if it's taller than the viewport), rather than adding a model
  the user can't see without knowing to scroll and find it themselves.
- **A label is only shown while its own 3D part is actually visible.**
  `LabelProjector.project` already had a behind-camera guard (a dot-product
  check against the camera's forward direction) and a check that a node's
  projected anchor falls inside the container; that second check used to
  test against a rect expanded 30% past the container's own edges, so a
  part just outside the visible viewport could still show a label - fixed
  by testing against the real content rect with no expansion, so a part
  that's rotated/panned out of view or turned edge-on has its label
  disappear outright rather than lingering near the edge. Since this whole
  per-label filter re-runs from scratch on every recompute (see
  `ModelInstance.needsLabelRecompute`), a label reappears on its own,
  correctly repositioned, the moment its part is visible again - there's
  no separate "was hidden" state to reset.
- **Labels use a lightweight local collision-avoidance pass** (nudge
  overlapping labels apart, then clamp inside the container) rather than a
  general constraint-based layout solver — enough for the handful of
  labelled parts each bundled model actually has, and cheap enough to run
  every frame a model's labels are visible.

## What I'd improve with more time

- Profile on the actual target hardware class (see above) instead of
  reasoning about cost from the architecture alone.
- Revisit Euler-angle rotation (see Known limitations) if free-spinning
  rotation past the poles becomes a requirement.
- A true minimal bounding *sphere* (Ritter's algorithm or similar) instead
  of "largest distance from the model's own origin" for `computeModelRadius`
  - the current approach is a correct, conservative superset (it can never
  under-count and clip real geometry) but isn't the tightest possible fit
  for a model whose origin sits off-center from its actual visual center.

## Known bugs / limitations

- No persistence — closing the app forgets everything on screen.
- Rotation is Euler-angle based (`Rotation(x, y, z)` degrees), with no
  gimbal-lock protection. Fine for the bounded drag-to-rotate interaction
  this task asks for; would reconsider for an unconstrained free-spinning
  camera.
- SceneView's public API has shifted slightly between 2.3.x point
  releases in the past; if Android Studio can't resolve an import exactly
  as written, Alt+Enter and picking the `io.github.sceneview.*` match
  resolves it — the class/method names themselves are stable across the
  2.x line.

## Device(s) tested on

- **Android emulator, Pixel-class AVD, hardware-accelerated** (host GPU
  passthrough, Filament resolved to a real OpenGL ES 3.0 backend rather
  than a software renderer) — used to verify all 5 bundled models load,
  render and auto-fit correctly; that drag/resize/rotate/zoom/label
  toggling are fully isolated per model with several models loaded at
  once; that the background canvas is inert to touch; and that closing a
  model releases it without affecting the others.
- **Physical device: Redmi Note 8** (1080×2340, density 440, MIUI) —
  installed and launched successfully; confirmed the app starts, the theme
  and empty state render correctly on real hardware. MIUI blocks synthetic
  touch injection over ADB unless "USB debugging (Security settings)" is
  enabled in Developer Options, so the full interaction sweep (drag,
  rotate, zoom, multi-model isolation, close) was exercised on the
  emulator above rather than this unit directly - manually repeating that
  same sweep by hand on this device is the recommended last check before
  submission.
