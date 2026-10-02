;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.runtime.camera
  "Standard load, pan and zoom bodies for renderer benchmark cases.

  ## Concepts

  A scene is a recipe for shapes. A case pairs a scene with one measured
  action. Its `:run!` body receives `rtx`, a browser runtime map. The browser
  builds and uploads the scene snapshot before calling a body.

  `rtx` contains:

  - `:case`: plain case data, including its `:operation` settings.
  - `:scene`: the prepared shape snapshot.
  - `:module`: the WASM renderer handle.
  - `:view`: the starting `{:scale :x :y}` camera view. The fields are zoom
    scale and two camera offsets.
  - `:hooks`: clock, scheduling and renderer functions.

  'Full' (`FRAME_TYPE_FULL`) is the renderer frame type that ends a progressive
  render (it does not mean the frame is visible on screen, though!).
  A cached preview updates a view and calls `_render_from_cache` once, without
  draining to `Full`.
  A camera session spans camera start, previews, settling and the final `Full`
  drain. A measurement is a map of render slices and elapsed times.

  ## Work and timing

  `load!` drains the first render to `Full` after upload. `Full` marks renderer
  completion. Pan and zoom read settings from `[:case :operation]`. `pan!` adds
  `:dx` and `:dy` to the starting view. `zoom!` multiplies its `:scale` by
  `:factor`. Both submit a number `:steps` of interpolated views. Each view
  waits for the browser's next animation frame and makes one cached preview.
  They then request a `:settle-ms` pause, in milliseconds, and drain the final
  view to `Full`.

  Each body returns a promise of a measurement map. For reused cases,
  the browser restores the starting view outside interaction timing. Pan and
  zoom calculate their target before starting that timer.

  ## Related code

  Scene declarations such as `scenes/rects.cljs` call these functions from
  their `:run!` bodies. `declarations.cljs` registers those bodies.
  `browser/bridge.cljs` supplies `rtx` and prepares reused cases. `runtime/protocol.cljs`
  owns the render drain and camera session; its `Metrics reported` section
  defines the measurement fields. Node reads case declarations without running
  their bodies. This namespace loads there because renderer effects come
  through the browser-supplied `:hooks`."
  (:require
   [benches.render-wasm.runtime.protocol :as protocol]))

(defn load!
  "Drains the uploaded scene until the renderer reports Full completion.
  `rtx` supplies the clock and renderer hooks. Returns a promise of first-render
  slices and completion times."
  [rtx]
  (let [hooks (:hooks rtx)]
    (protocol/drain hooks {:flags 0
                           :origin ((:now hooks))
                           :immediate false})))

(defn pan!
  "Moves the starting `{:scale :x :y}` view by the case's `:dx` and `:dy`.
  Submits `:steps` views, each with one cached renderer preview after an
  animation frame. Requests a `:settle-ms` pause, then drains until the renderer
  reports Full completion.
  Returns a promise of interaction measurements."
  [rtx]
  (let [view (:view rtx)
        {:keys [steps dx dy settle-ms]} (get-in rtx [:case :operation])
        target (-> view
                   (update :x + dx)
                   (update :y + dy))]
    (-> (protocol/start-camera! rtx {:settle-ms settle-ms})
        (protocol/animate-view! {:to target :steps steps})
        (protocol/finish-camera!))))

(defn zoom!
  "Multiplies the starting `{:scale :x :y}` view's scale by `:factor`.
  Submits `:steps` views, each with one cached renderer preview after an
  animation frame. Requests a `:settle-ms` pause, then drains until the renderer
  reports Full completion.
  Returns a promise of interaction measurements."
  [rtx]
  (let [view (:view rtx)
        {:keys [steps factor settle-ms]} (get-in rtx [:case :operation])
        target (update view :scale * factor)]
    (-> (protocol/start-camera! rtx {:settle-ms settle-ms})
        (protocol/animate-view! {:to target :steps steps})
        (protocol/finish-camera!))))
