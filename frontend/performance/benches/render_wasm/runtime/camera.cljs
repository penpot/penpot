;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.runtime.camera
  "Rendering and camera commands over a prepared snapshot.

  Commands accept a context or its Promise and return a Promise of the next
  context. Pan, zoom and restore update its view. Starred variants record raw
  elapsed time and renderer evidence in the current execution phase.

  Load waits for an animation frame and renders through Full. Pan and zoom
  submit paced cached previews, settle, then render through Full. Restore
  applies a view and renders immediately with SyncTiles. Render-sync uses the
  blocking renderer path. Full denotes renderer submission completion.

  Measured async commands finish at the final renderer return captured in
  `:end-ms`. Input resolution and argument validation precede timing; output
  validation and event recording follow it. Traces retain renderer calls,
  cached previews, waits and protocol endpoints."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.declarations :as decl]
   [benches.render-wasm.runtime.commands :refer [defcommand]]
   [benches.render-wasm.runtime.protocol :as protocol]))

(defcommand render-sync
  "Renders the prepared scene synchronously.

  Args:
    rtx: context or Promise of it.

  Returns:
    A Promise of the context after the blocking renderer call."
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

(def ^:private valid-zoom?
  (sm/validator
   [:map {:closed true}
    [:factor [:and ::sm/safe-number [:> 0]]]
    [:steps [:int {:min 1}]]
    [:settle-ms [:and ::sm/safe-number [:>= 0]]]]))

(def ^:private valid-view? (sm/validator decl/schema:view))

(defn- pan-view
  [view dx dy]
  (-> view (update :x + dx) (update :y + dy)))

(defn- zoom-view
  [view factor]
  (update view :scale * factor))

(defn- render-evidence
  [evidence]
  {:end-ms (:end-ms evidence)
   :metrics {:renderer/call-elapsed (mapv :duration-ms (:slices evidence))}
   :trace evidence})

(defn- camera-evidence
  [evidence]
  (-> (render-evidence evidence)
      (assoc-in [:metrics :renderer/cache-call-elapsed] (mapv :duration-ms (:cached-slices evidence)))
      (assoc-in [:metrics :camera/finalization-to-full] [(:time-to-full-ms evidence)])))

(defn- run-camera
  [rtx target {:keys [steps settle-ms]}]
  (-> (protocol/start-camera! rtx {:settle-ms settle-ms})
      (protocol/animate-view! {:to target :steps steps})
      (protocol/finish-camera!)))

(defcommand pan
  "Moves the view by `:dx` and `:dy` through `:steps` cached previews.
  Waits `:settle-ms` milliseconds, ends the interaction and renders until Full.

  Args:
    rtx: context or Promise of it.
    options: finite offsets, positive step count and nonnegative settling wait.

  Returns:
    A Promise of the context with its updated view."
  {:async [complete fail]}
  [rtx {:keys [dx dy] :as options}]
  {:pre [(valid-pan? options) (valid-view? (pan-view (:view rtx) dx dy))]}
  (let [target (pan-view (:view rtx) dx dy)]
    (-> (run-camera rtx target options)
        (.then #(complete (assoc rtx :view target) (camera-evidence %)))
        (.catch fail))))

(defcommand load
  "Renders the uploaded snapshot until the renderer returns Full.
  Waits for an animation frame before the first renderer call.

  Args:
    rtx: context or Promise of it.

  Returns:
    A Promise of the context after renderer completion."
  {:async [complete fail]}
  [rtx]
  (let [hooks (:hooks rtx)]
    (-> (protocol/drain hooks {:flags 0 :origin ((:now hooks)) :immediate false})
        (.then #(complete rtx (assoc-in (render-evidence %)
                                        [:metrics :renderer/prepared-scene-to-full] [(:full-ms %)])))
        (.catch fail))))

(defcommand zoom
  "Multiplies view scale through paced cached previews, then renders through Full.

  Args:
    rtx: context or Promise of it.
    options: positive finite factor, positive steps and nonnegative settle-ms.

  Returns:
    A Promise of the context with its updated scale."
  {:async [complete fail]}
  [rtx {:keys [factor] :as options}]
  {:pre [(valid-zoom? options) (valid-view? (zoom-view (:view rtx) factor))]}
  (let [target (zoom-view (:view rtx) factor)]
    (-> (run-camera rtx target options)
        (.then #(complete (assoc rtx :view target) (camera-evidence %)))
        (.catch fail))))

(defcommand restore
  "Restores a view and renders immediately through Full with SyncTiles.

  Args:
    rtx: context or Promise of it.
    view: scale and offsets to restore. Its resolved viewport must match rtx.

  Returns:
    A Promise of the context with the restored view."
  {:async [complete fail]}
  [rtx view]
  {:pre [(valid-view? view)
         (= (decl/resolve-viewport view) (decl/resolve-viewport (:view rtx)))]}
  (-> (protocol/restore (:hooks rtx) view)
      (.then #(complete (assoc rtx :view view) (render-evidence %)))
      (.catch fail)))
