;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.runtime.protocol
  "Rendering until completion and timing camera movement.

  A frame type describes the progress of one renderer call. A render drain is
  a sequence of calls that ends when the renderer returns Full. Each call
  produces a slice: its timestamp, flags, frame type and duration. Partial and
  ViewportReady schedule another call on the next animation frame. Full marks
  renderer submission of the completed request. GPU execution and display
  proceed asynchronously.

  A view contains zoom `:scale` and offsets `:x` and `:y`. A cached preview
  draws from existing tiles, the fixed regions of the scene that the renderer
  caches. A camera session starts an interaction, applies views with cached
  previews, waits for the requested settling time, ends the interaction and
  renders until Full. Each preview waits for an animation frame. Explicit
  pauses can extend the active interval between camera start and settling.
  Camera finalization starts immediately before ending the interaction and
  ends at the final renderer return.

  ## Hooks and errors

  Hooks are functions that supply clock, scheduling and renderer operations:

  - `:now` returns a clock reading in milliseconds.
  - `:frame` resolves a Promise with the next animation frame's timestamp.
  - `:sleep` accepts a requested wait in milliseconds and returns its Promise.
  - `:check` is a guard. Returning authorizes work; throwing rejects it. It can
    report cancellation, an expired deadline, loss of the WebGL2 graphics
    context or replacement of the active renderer owner. An owner is the
    execution authorized to use the renderer.
  - `:render` accepts a timestamp and flags and returns a frame type.
  - `:render-from-cache` draws one cached preview.
  - `:set-view` accepts a view containing scale and both offsets.
  - `:set-view-start` and `:set-view-end` start and end the interaction.

  Guards run before work, after awaited frames and waits, and around render
  calls. Guard and renderer errors reject the returned Promises. Errors keep
  available traces of render calls and pauses under `:partial`.

  Frame types come from `app.common.render-wasm.wasm`. Partial and ViewportReady
  continue rendering, Full completes it, and other values reject with
  `::unexpected-frame-type`. Each subsequent call receives the preceding frame
  type as its flags. SyncTiles asks the renderer to update which tiles the
  current view requires.
  View restoration and camera finalization use that flag for their first call
  and start immediately. A drain can also wait for an animation frame before
  its first call. Later calls wait for animation frames.

  ## Render calls and measured durations

  A trace records individual calls and waits. Durations use milliseconds and
  preserve raw numeric values, including zero. A drain origin is the clock
  reading from which its completion durations start.

  - `:slices` contains one entry per renderer call:
    - `:timestamp`: the animation frame's timestamp or the current clock
      reading for an immediate call.
    - `:flags`: the flags passed to the renderer.
    - `:frame-type`: the returned frame type.
    - `:duration-ms`: elapsed time during the call.
  - `:cached-slices` contains one entry per cached preview:
    - `:timestamp`: the animation frame's timestamp for the view update.
    - `:duration-ms`: elapsed time during the cached render call.
    - `:view-and-preview-ms`: elapsed time for the view update and preview.
  - `:viewport-ready-ms` measures from the drain origin to the first call that
    returns ViewportReady or Full. It equals `:full-ms` when the first such
    call returns Full.
  - `:full-ms` measures from the drain origin to the call that returns Full.
  - `:end-ms` is the clock reading immediately after that final call returns.
  - `:interact-ms` measures from immediately before camera start through the
    final renderer return.
  - `:active-ms` measures from camera start through the last cached preview and
    its guard check, or through an explicit pause that follows the preview.
  - `:authored-sleeps` contains requested and observed durations of explicit
    pauses during the active interval.
  - `:settling-requested-ms` is the requested settling wait.
  - `:settling-actual-ms` measures from the end of the active interval to the
    start of camera finalization.
  - `:set-view-end-ms` measures the call that ends the interaction.
  - `:time-to-viewport-ready-ms` measures from camera finalization to the first
    ViewportReady or Full result. `:time-to-full-ms` ends at Full. Both include
    the call that ends the interaction.
  - `:last-input-to-full-ms` measures from immediately before the last view
    update to the final renderer return. The initial input time equals camera
    start; each view update replaces it."
  (:require-macros
   [benches.render-wasm.runtime.protocol :refer [resolve-or-reject resolve-or-reject-with]])
  (:require
   [app.common.render-wasm.wasm :as wasm]
   [benches.render-wasm.errors :as errors]))


(defn- partial-error
  "Returns an error with completed traces under `:partial`.
  Trace fields already present in the error take precedence over `evidence`."
  [cause evidence]
  (errors/with-context cause (errors/message-of cause "renderer protocol failed")
    {:partial evidence}))

(defn- next-render-timestamp
  "Resolves to the current clock reading for an immediate render, or to the
  next animation frame's timestamp."
  [{:keys [now frame]} immediate?]
  (if immediate?
    (js/Promise.resolve (now))
    (frame)))


(defn- render-slice
  "Returns the updated drain state, frame type and endpoint for one render call.
  The state includes the completed call in its `:slices` trace."
  [{:keys [now check render]}
   {:keys [flags] :as state}
   timestamp]
  (check)
  (let [started-ms (now)
        frame-type (render timestamp flags)
        ended-ms   (now)
        slice       {:timestamp   timestamp
                     :flags       flags
                     :frame-type  frame-type
                     :duration-ms (- ended-ms started-ms)}]
    (try
      (check)
      (catch :default cause
        (throw (partial-error cause {:slices (conj (:slices state) slice)}))))
    {:state      (update state :slices conj slice)
     :frame-type frame-type
     :ended-ms   ended-ms}))


(defn- valid-frame-type?
  "Recognizes the progressive and final renderer frame types."
  [frame-type]
  (or (= frame-type wasm/FRAME_TYPE_PARTIAL)
      (= frame-type wasm/FRAME_TYPE_VIEWPORT_READY)
      (= frame-type wasm/FRAME_TYPE_FULL)))


(defn- viewport-ready-time
  "Returns the elapsed time to the first ViewportReady or Full result."
  [current-ready-ms frame-type ended-ms origin-ms]
  (or current-ready-ms
      (when (or (= frame-type wasm/FRAME_TYPE_VIEWPORT_READY)
                (= frame-type wasm/FRAME_TYPE_FULL))
        (- ended-ms origin-ms))))


(defn- advance-drain-state
  "Updates the continuation flags and completion time for a returned frame type.
  An unexpected type throws an error containing the completed render calls."
  [{:keys [viewport-ready-ms] :as state}
   frame-type
   ended-ms
   origin-ms]
  (when-not (valid-frame-type? frame-type)
    (throw (ex-info (str "unexpected frame type: " (pr-str frame-type))
                    {:type ::unexpected-frame-type
                     :frame-type frame-type
                     :partial (select-keys state [:slices :viewport-ready-ms])})))
  (assoc state
         :flags frame-type
         :viewport-ready-ms
         (viewport-ready-time viewport-ready-ms
                              frame-type
                              ended-ms
                              origin-ms)))

(defn- drain-complete?
  "Returns whether the renderer reported Full completion."
  [frame-type]
  (= frame-type wasm/FRAME_TYPE_FULL))

(defn- drain-result
  "Returns traces of render calls, the final clock reading and completion durations
  measured from the drain origin."
  [{:keys [slices viewport-ready-ms]}
   ended-ms
   origin-ms]
  {:slices            slices
   :end-ms            ended-ms
   :viewport-ready-ms viewport-ready-ms
   :full-ms           (- ended-ms origin-ms)})


(defn drain
  "Renders until a call returns Full and resolves to its traces and durations.

  Options:
  - `:flags` supplies the first render call's flags.
  - `:origin` supplies the starting clock reading for completion durations.
  - `:immediate` starts the first call immediately when true. Otherwise, the
    first call waits for an animation frame. Subsequent calls wait for frames.

  The result contains `:slices`, the traces of render calls; `:end-ms`, the final
  renderer clock reading; and `:viewport-ready-ms` and `:full-ms`, durations
  measured from `:origin`. Partial and ViewportReady continue with the returned
  frame type as the next flags. A guard failure or unexpected frame type
  rejects the Promise with completed traces in the error's `:partial` field."
  [{:keys [check] :as hooks}
   {:keys [flags origin immediate]}]
  (resolve-or-reject
   (check)
   (letfn [(step [state immediate?]
             (-> (next-render-timestamp hooks immediate?)
                 (.then  ;; recurse or drain
                  (fn [timestamp]
                    (let [{:keys [frame-type ended-ms] :as rendered}
                          (render-slice hooks state timestamp)
                          next-state
                          (advance-drain-state (:state rendered)
                                               frame-type
                                               ended-ms
                                               origin)]

                      (if (drain-complete? frame-type)
                        (drain-result next-state ended-ms origin)
                        (step next-state false)))))
                 (.catch (fn [cause]
                           (throw (partial-error cause (select-keys state [:slices :viewport-ready-ms])))))))]
     (step {:flags             flags
            :slices            []
            :viewport-ready-ms nil}
           immediate))))

(defn restore
  "Restores `view` and renders its tiles until Full.
  The first call uses SyncTiles and starts immediately. Completion durations
  begin after restoring the view and ending the interaction. Returns a Promise
  with recorded render calls, completion durations and the final clock reading
  `:end-ms`."
  [{:keys [now check set-view set-view-start set-view-end] :as hooks} view]
  (resolve-or-reject
   (check)
   (set-view-start)
   (set-view view)
   (set-view-end)
   (drain hooks {:flags     wasm/RENDER_FLAG_SYNC_TILES
                 :origin    (now)
                 :immediate true})))

(defn start-camera!
  "Starts a camera session from the context's current `:view` and `:hooks`.
  Resolves to the session with its start time and requested settling wait.
  The initial active interval covers the operation that starts the camera.
  `:settle-ms` must give a finite, nonnegative duration in milliseconds."
  [{:keys [hooks view]} {:keys [settle-ms]}]
  (resolve-or-reject
   (when-not (and (number? settle-ms)
                  (js/Number.isFinite settle-ms)
                  (<= 0 settle-ms))
     (throw (ex-info "settle-ms must be a nonnegative finite number"
                     {:type ::invalid-settle-ms :settle-ms settle-ms})))
   (let [{:keys [now check set-view-start]} hooks]
     (check)
     (let [started-ms (now)]
       (set-view-start)
       {:hooks          hooks
        :view           view
        :settle-ms      settle-ms
        :started-ms     started-ms
        :last-input-ms  started-ms
        :active-end-ms  (now)
        :cached-slices  []
        :authored-sleeps []}))))

(defn preview-view!
  "Updates the view and draws one cached preview on the next animation frame.
  Resolves to the session with the new view and measured preview durations.
  Errors retain completed previews and pauses under `:partial`."
  [session-p view]
  (-> (js/Promise.resolve session-p)
      (.then (fn [{:keys [hooks] :as session}]
               (let [{:keys [now frame check render-from-cache set-view]} hooks
                     evidence (select-keys session [:cached-slices :authored-sleeps])]
                 (resolve-or-reject-with cause
                   (partial-error cause evidence)
                   (check)
                   (-> (frame)
                       (.then (fn [timestamp]
                                (check)
                                (let [input-ms (now)
                                      call-start (now)]
                                  (set-view view)
                                  (let [cache-start (now)]
                                    (render-from-cache)
                                    (let [cache-end (now)
                                          slices (conj (:cached-slices session)
                                                       {:timestamp timestamp
                                                        :duration-ms (- cache-end cache-start)
                                                        :view-and-preview-ms (- cache-end call-start)})]
                                      (try
                                        (check)
                                        (catch :default cause
                                          (throw (partial-error cause (assoc evidence :cached-slices slices)))))
                                      (assoc session :view view :last-input-ms input-ms
                                             :active-end-ms (now) :cached-slices slices))))))
                       (.catch (fn [cause] (throw (partial-error cause evidence)))))))))))

(defn animate-view!
  "Moves to `to` through `steps` linearly interpolated views in sequence.
  Each update waits for an animation frame and draws one cached preview.
  Resolves to the session with its final view. Steps must be a positive integer;
  errors retain completed previews and pauses under `:partial`."
  [session-p {:keys [to steps]}]
  (-> (js/Promise.resolve session-p)
      (.then (fn [{from :view :as session}]
               (when-not (and (integer? steps) (pos? steps))
                 (throw (ex-info "camera steps must be a positive integer"
                                 {:type ::invalid-steps :steps steps
                                  :partial (select-keys session [:cached-slices :authored-sleeps])})))
               (reduce (fn [session-p i]
                         (let [t (/ i steps)]
                           (preview-view! session-p
                                          {:scale (+ (:scale from) (* t (- (:scale to) (:scale from))))
                                           :x (+ (:x from) (* t (- (:x to) (:x from))))
                                           :y (+ (:y from) (* t (- (:y to) (:y from))))})))
                       (js/Promise.resolve session)
                       (range 1 (inc steps)))))))

(defn sleep!
  "Pauses the camera session and records the requested and observed wait.
  The wait must be finite and nonnegative. The pause extends the active
  interval. Resolves to the updated session;
  errors retain completed previews and pauses under `:partial`."
  [session-p requested-ms]
  (-> (js/Promise.resolve session-p)
      (.then (fn [{:keys [hooks] :as session}]
               (let [{:keys [now sleep check]} hooks
                     evidence (select-keys session [:cached-slices :authored-sleeps])]
                 (resolve-or-reject-with cause
                   (partial-error cause evidence)
                   (when-not (and (number? requested-ms)
                                  (js/Number.isFinite requested-ms)
                                  (<= 0 requested-ms))
                     (throw (ex-info "sleep duration must be a nonnegative finite number"
                                     {:type ::invalid-sleep :requested-ms requested-ms})))
                   (check)
                   (let [started-ms (now)]
                     (-> (sleep requested-ms)
                         (.then (fn [_]
                                  (check)
                                  (let [ended-ms (now)]
                                    (-> session
                                        (assoc :active-end-ms ended-ms)
                                        (update :authored-sleeps conj
                                                {:requested-ms requested-ms
                                                 :actual-ms (- ended-ms started-ms)})))))
                         (.catch (fn [cause] (throw (partial-error cause evidence))))))))))))

(defn- finish-interaction
  "Ends the interaction and resolves to camera durations and recorded render calls.
  Errors retain completed previews and pauses under `:partial`."
  [{:keys [now check set-view-end] :as hooks}
   {:keys [settle-ms started-ms cached-slices last-input-ms active-end-ms authored-sleeps]}]
  (check)

  (let [finalization-start (now)
        t0                 (now)]
    (set-view-end)

    (let [set-view-end-ms (- (now) t0)]
      (-> (drain hooks
                 {:flags     wasm/RENDER_FLAG_SYNC_TILES
                  :origin    finalization-start
                  :immediate true})
          (.then
           (fn [{:keys [slices end-ms viewport-ready-ms full-ms]}]
             {:cached-slices             cached-slices
              :slices                    slices
              :end-ms                    end-ms
              :interact-ms               (- end-ms started-ms)
              :active-ms                 (- active-end-ms started-ms)
              :authored-sleeps           authored-sleeps
              :settling-requested-ms     settle-ms
              :settling-actual-ms        (- finalization-start active-end-ms)
              :set-view-end-ms           set-view-end-ms
              :time-to-viewport-ready-ms viewport-ready-ms
              :time-to-full-ms           full-ms
              :last-input-to-full-ms     (- end-ms last-input-ms)}))
          (.catch (fn [cause]
                    (throw (partial-error cause {:cached-slices cached-slices
                                                 :authored-sleeps authored-sleeps}))))))))


(defn finish-camera!
  "Waits for settling, ends the interaction and renders until Full.
  Resolves to cached previews, traces of final render calls, pauses and measured
  camera durations. `:end-ms` is the clock reading at the final renderer return.
  Errors retain completed previews and pauses under `:partial`."
  [session-p]
  (-> (js/Promise.resolve session-p)
      (.then (fn [{:keys [hooks settle-ms] :as session}]
               (let [evidence (select-keys session [:cached-slices :authored-sleeps])]
                 (resolve-or-reject-with cause
                   (partial-error cause evidence)
                   ((:check hooks))
                   (-> ((:sleep hooks) settle-ms)
                       (.then (fn [_] (finish-interaction hooks session)))
                       (.catch (fn [cause] (throw (partial-error cause evidence)))))))))))
