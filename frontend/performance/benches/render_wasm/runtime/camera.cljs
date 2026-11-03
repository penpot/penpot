;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.runtime.camera
  "Control rendering and camera movement in a scene case.

  Recap: A `scene` is a recipe for shapes, and a `snapshot` is just an instance
  of the scene. A `case` is what we benchmark: a set of operations executed on
  a snapshot. This namespace provides operations to control rendering and camera.

  ## Runtime data

  A view contains zoom `:scale`, offsets `:x` and `:y`, and optional viewport
  settings for canvas dimensions and device pixel ratio. Hooks are functions
  that supply clocks, scheduling and renderer operations. A guard returns to
  authorize work or throws an error to reject it. A command context contains:

  - `:case`: the case descriptor, which contains its data settings.
  - `:scene`: the prepared snapshot of shapes and references.
  - `:view`: the current view.
  - `:hooks`: the clock, scheduling, renderer and execution guard functions.

  The runtime context for a case operation also includes `:module`, the
  instantiated WebAssembly renderer. `pan!` and `zoom!` read movement settings
  from `[:case :operation]`.

  ## Rendering and camera sessions

  A frame type describes the progress of a renderer call. `Full`
  (`FRAME_TYPE_FULL`) marks the renderer's completion of the request. GPU
  execution and display proceed asynchronously after renderer submission.

  A cached preview applies a view and draws once from cached tiles. A camera
  session starts the interaction, submits interpolated views, waits for the
  requested settling time, ends the interaction and renders until Full.
  Each view update waits for the next animation frame and draws one cached
  preview. `:settle-ms` gives the requested settling wait in milliseconds.

  `render-sync` renders synchronously. `pan` receives explicit offsets, step
  count and settling time, and returns the context with its updated view.
  These commands accept a command context or its Promise and return a Promise
  of the next context. Their measured functions, `render-sync*` and `pan*`,
  require a command execution, which runs a function over a command context and
  collects its measured calls. Each call records an event with the command's
  metric values and optional traces. A metric names a measured quantity, such
  as elapsed time.

  `load!` renders an uploaded snapshot until Full. `pan!` adds `:dx` and `:dy`
  to the starting offsets; `zoom!` multiplies the starting scale by `:factor`.
  Both submit `:steps` interpolated views and finish a camera session. These
  operations resolve to the recorded render calls and measured durations.

  ## Timing

  Each trace entry records a render call's timestamp, flags, frame type and
  duration. Camera measurements also contain cached previews, requested and
  observed waits, and completion durations. `:end-ms` is the clock reading
  immediately after the final renderer call returns Full.

  `pan!` and `zoom!` calculate their target view before camera timing starts.
  The `:interact-ms` duration runs from camera start through the final renderer
  return. `pan*` measures its command body, including the target calculation,
  through that same endpoint. Command input resolution and argument validation
  precede the measured interval. Output validation and event recording follow
  the endpoint."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.runtime.commands :refer [defcommand]]
   [benches.render-wasm.runtime.protocol :as protocol]))

(defcommand render-sync
  "Renders the prepared scene synchronously and returns the command context."
  [rtx]
  ((get-in rtx [:hooks :render-sync]))
  rtx)

(def ^:private valid-pan?
  (sm/validator
   [:map {:closed true}
    [:dx ::sm/safe-number]
    [:dy ::sm/safe-number]
    [:steps [:int {:min 1}]]
    [:settle-ms [:and ::sm/safe-number [:>= 0]]]]))

(defn- run-camera
  [rtx target {:keys [steps settle-ms]}]
  (-> (protocol/start-camera! rtx {:settle-ms settle-ms})
      (protocol/animate-view! {:to target :steps steps})
      (protocol/finish-camera!)))

(defcommand pan
  "Moves the view by `:dx` and `:dy` through `:steps` cached previews.
  Waits `:settle-ms` milliseconds, ends the interaction and renders until Full.
  Resolves to the command context with its updated view. Steps must be positive;
  the settling wait must be nonnegative."
  {:async [complete fail]}
  [rtx {:keys [dx dy] :as options}]
  {:pre [(valid-pan? options)]}
  (let [target (-> (:view rtx)
                   (update :x + dx)
                   (update :y + dy))]
    (-> (run-camera rtx target options)
        (.then (fn [evidence]
                 (complete (assoc rtx :view target)
                           {:end-ms (:end-ms evidence)
                            :metrics {:renderer/call-elapsed (mapv :duration-ms (:slices evidence))
                                      :renderer/cache-call-elapsed (mapv :duration-ms (:cached-slices evidence))
                                      :camera/finalization-to-full [(:time-to-full-ms evidence)]}
                            :trace evidence})))
        (.catch fail))))

(defn load!
  "Renders the uploaded snapshot until the renderer returns Full.
  Waits for an animation frame before the first render call. Returns a Promise
  with recorded render calls, completion durations and the final clock reading
  `:end-ms`. The context supplies clock, scheduling and renderer hooks."
  [rtx]
  (let [hooks (:hooks rtx)]
    (protocol/drain hooks {:flags 0
                           :origin ((:now hooks))
                           :immediate false})))

(defn pan!
  "Moves the starting view by the offsets in `[:case :operation]`.
  `:dx` and `:dy` give the offsets; `:steps` gives the number of interpolated
  views. Each update waits for an animation frame and draws a cached preview.
  After the `:settle-ms` wait, ends the interaction and renders until Full.
  Returns a Promise with recorded render calls, waits and camera durations."
  [rtx]
  (let [view (:view rtx)
        {:keys [steps dx dy settle-ms]} (get-in rtx [:case :operation])
        target (-> view
                   (update :x + dx)
                   (update :y + dy))]
    (run-camera rtx target {:steps steps :settle-ms settle-ms})))

(defn zoom!
  "Changes the starting scale using settings in `[:case :operation]`.
  `:factor` multiplies the scale; `:steps` gives the number of interpolated
  views. Each update waits for an animation frame and draws a cached preview.
  After the `:settle-ms` wait, ends the interaction and renders until Full.
  Returns a Promise with recorded render calls, waits and camera durations."
  [rtx]
  (let [view (:view rtx)
        {:keys [steps factor settle-ms]} (get-in rtx [:case :operation])
        target (update view :scale * factor)]
    (run-camera rtx target {:steps steps :settle-ms settle-ms})))
