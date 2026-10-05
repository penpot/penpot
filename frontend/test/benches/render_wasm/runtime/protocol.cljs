;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.runtime.protocol
  "Shared render-completion protocol for the renderer benchmarks.

  The WASM renderer finishes progressively in tile budgets: one `_render`
  call returns a frame type and the caller keeps calling until `FRAME_TYPE_FULL`.
  This namespace implements a driver loop and camera sessions over an injected
  `hooks` map. Callers use the same timing and continuation rules for
  rendering and interaction:

  - `drain` drives `_render` to `FRAME_TYPE_FULL`, collecting one slice per
    call.
  - `restore` puts the camera back to the case's initial view, then drains.
    Callers run it outside timing.
  - `start-camera!`, `preview-view!`, `animate-view!`, `sleep!` and
    `finish-camera!` form one Promise-aware camera session.

  `hooks` supplies clock, scheduling and renderer operations. The browser
  supplies real capabilities; tests supply fakes.

  - `:now` zero-arg clock, ms.
  - `:frame` zero-arg promise of the next rAF timestamp.
  - `:sleep` `(fn [ms] ...)` promise.
  - `:check` zero-arg guard. Throws on cancellation, wall deadline,
    context loss or superseded ownership. The protocol checks before work,
    after awaited frames and sleeps, and around render calls. It never
    retries; synchronous stalls cannot be
    interrupted in-page, so Node stays the outer backstop.
  - `:render` `(fn [timestamp flags] ...)` returning a frame type.
  - `:render-from-cache` zero-arg cached-preview call. The protocol times
    the call itself and discards its return value.
  - `:set-view` `(fn [view] ...)` with `view` a `{:scale :x :y}` map.
  - `:set-view-start` / `:set-view-end` zero-arg camera calls.

  Guard and renderer errors reject the returned promises. The browser's
  `:check` guard throws when work is cancelled, exceeds its deadline,
  loses its rendering context or is no longer the active task.

  Frame types come from `app.common.render-wasm.wasm`: FRAME_TYPE_PARTIAL and
  FRAME_TYPE_VIEWPORT_READY continue, FRAME_TYPE_FULL completes, FRAME_TYPE_NONE
  or anything else fails the attempt. The previous returned frame type becomes the
  next call's flags. `restore` and the final camera-session drain start with
  the SyncTiles flag immediately (no rAF); a fresh drain can start with
  different flags and wait for a frame.

  ## Metrics reported

  - `:slices` one map per `_render` call:
    - `:timestamp`: the rAF stamp the call ran under, or the clock value for
      an immediate call without a frame
    - `:flags`: flags passed in
    - `:frame-type`: frame type returned
    - `:duration-ms`: wall time of the call itself
    Raw slices are retained as-is, including zero durations.
  - `:cached-slices` one map per gesture frame:
    - `:timestamp` rAF stamp of the input
    - `:duration-ms`: the timed `_render-from-cache` call.
    - `:view-and-preview-ms`: the view update and cached-preview call span.
    Cached previews have their own shape: no flags or frame type.
  - `:viewport-ready-ms` first Full-or-ViewportReady completion measured
    from the drain origin. An immediate Full supplies both boundaries, so
    they are equal there.
  - `:full-ms` Full completion measured from the drain origin.
  - `:interact-ms` from before `_set_view_start` through final Full.
  - `:active-ms` from before `_set_view_start` to after the last cached
    preview and its guard check, or an authored sleep after that preview.
  - `:authored-sleeps` requested and actual durations of optional pauses.
  - `:settling-requested-ms` the declared settle wait passed in.
  - `:settling-actual-ms` from the end of the active span to finalization
    start, recorded separately from the declared value.
  - `:set-view-end-ms` wall time of the `_set_view_end` call itself.
    Finalization starts before `_set_view_end`, so the final drain times
    include it; the call time is still reported on its own.
  - `:time-to-viewport-ready-ms` / `:time-to-full-ms` the drain's
    boundaries, renamed at the interaction level.
  - `:last-input-to-full-ms` from the clock read before the last view update
    to the clock read after the final drain promise resolves."
  (:require
   [app.common.render-wasm.wasm :as wasm]))


(defn- partial-error
  "Attaches available trace evidence to a rejection without adding clock reads.
  Deeper failure evidence takes precedence over a caller's earlier state."
  [cause evidence]
  (ex-info (or (ex-message cause) "renderer protocol failed")
           (assoc (ex-data cause) :partial (merge evidence (:partial (ex-data cause))))
           (if (some? (ex-data cause)) (ex-cause cause) cause)))

(defn- next-render-timestamp
  "Gets an immediate clock timestamp or awaits the next frame timestamp."
  [{:keys [now frame]} immediate?]
  (if immediate?
    (js/Promise.resolve (now))
    (frame)))


(defn- render-slice
  "Times one render call and adds its slice to the drain state."
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
  "Keeps the first submission boundary that exposes the requested viewport."
  [current-ready-ms frame-type ended-ms origin-ms]
  (or current-ready-ms
      (when (or (= frame-type wasm/FRAME_TYPE_VIEWPORT_READY)
                (= frame-type wasm/FRAME_TYPE_FULL))
        (- ended-ms origin-ms))))


(defn- advance-drain-state
  "Updates progressive completion or rejects with the available raw slices."
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
  "Tests for the renderer's Full submission boundary."
  [frame-type]
  (= frame-type wasm/FRAME_TYPE_FULL))

(defn- drain-result
  "Returns raw slices and completion times from the drain origin."
  [{:keys [slices viewport-ready-ms]}
   ended-ms
   origin-ms]
  {:slices            slices
   :viewport-ready-ms viewport-ready-ms
   :full-ms           (- ended-ms origin-ms)})


(defn drain
  "Drives `_render` to `FRAME_TYPE_FULL` and resolves to
   `{:slices [...] :viewport-ready-ms ... :full-ms ...}`.

  The flow is:

  drain
    └─ step
         ├─ next-render-timestamp
         ├─ render-slice
         ├─ advance-drain-state
         └─ either
              ├─ drain-result
              └─ step

   `opts` contains:
     - `:flags` (first call's flags)
     - `:origin` (required starting moment in ms)
     - `:immediate` (run the first `_render` in the same task instead of waiting
       for a frame; later frames always wait).

   Partial and ViewportReady continue with the returned frame type as the next
   flags. FRAME_TYPE_FULL resolves. Anything else rejects with
  `::unexpected-frame-type`. A failure of the hook `:check` rejects without
  retrying."
  [{:keys [check] :as hooks}
   {:keys [flags origin immediate]}]
  (try
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
            immediate))

    (catch :default cause
      (js/Promise.reject cause))))

(defn restore
  "Puts the camera back to `view` (`{:scale :x :y}`) and drains with the
  SyncTiles flag immediately. Resolves what `drain` resolves. Callers run
  this outside timing: the reset itself is never scored."
  [{:keys [now check set-view set-view-start set-view-end] :as hooks} view]
  (try
    (check)
    (set-view-start)
    (set-view view)
    (set-view-end)
    (drain hooks {:flags     wasm/RENDER_FLAG_SYNC_TILES
                  :origin    (now)
                  :immediate true})
    (catch :default cause
      (js/Promise.reject cause))))

(defn start-camera!
  "Starts a measured camera session after the caller's untimed preparation.
  `rtx` supplies `:hooks` and the current full `:view`."
  [{:keys [hooks view]} {:keys [settle-ms]}]
  (try
    (when-not (and (number? settle-ms)
                   (js/Number.isFinite settle-ms)
                   (<= 0 settle-ms))
      (throw (ex-info "settle-ms must be a nonnegative finite number"
                      {:type ::invalid-settle-ms :settle-ms settle-ms})))
    (let [{:keys [now check set-view-start]} hooks]
      (check)
      (let [started-ms (now)]
        (set-view-start)
        (js/Promise.resolve
         {:hooks          hooks
          :view           view
          :settle-ms      settle-ms
          :started-ms     started-ms
          :last-input-ms  started-ms
          :active-end-ms  (now)
          :cached-slices  []
          :authored-sleeps []})))
    (catch :default cause
      (js/Promise.reject cause))))

(defn preview-view!
  "Awaits a session and submits one cached preview after exactly one rAF.
  The call span includes view update and cache rendering. Failures retain
  completed previews and authored pauses without adding clock reads."
  [session-p view]
  (-> (js/Promise.resolve session-p)
      (.then (fn [{:keys [hooks] :as session}]
               (let [{:keys [now frame check render-from-cache set-view]} hooks
                     evidence (select-keys session [:cached-slices :authored-sleeps])]
                 (try
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
                       (.catch (fn [cause] (throw (partial-error cause evidence)))))
                   (catch :default cause
                     (js/Promise.reject (partial-error cause evidence)))))))))

(defn animate-view!
  "Submits exactly `steps` serial, linearly interpolated full views.
  Invalid steps retain the session's completed previews and pauses."
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
  "Awaits an authored pause inside the active span and records its actual time.
  Rejections retain earlier completed previews and pauses without new timers."
  [session-p requested-ms]
  (-> (js/Promise.resolve session-p)
      (.then (fn [{:keys [hooks] :as session}]
               (let [{:keys [now sleep check]} hooks
                     evidence (select-keys session [:cached-slices :authored-sleeps])]
                 (try
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
                         (.catch (fn [cause] (throw (partial-error cause evidence))))))
                   (catch :default cause
                     (js/Promise.reject (partial-error cause evidence)))))))))

(defn- finish-interaction
  "Ends the camera and returns final metrics, retaining trace evidence on failure."
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
           (fn [{:keys [slices viewport-ready-ms full-ms]}]
             {:cached-slices             cached-slices
              :slices                    slices
              :interact-ms               (- (now) started-ms)
              :active-ms                 (- active-end-ms started-ms)
              :authored-sleeps           authored-sleeps
              :settling-requested-ms     settle-ms
              :settling-actual-ms        (- finalization-start active-end-ms)
              :set-view-end-ms           set-view-end-ms
              :time-to-viewport-ready-ms viewport-ready-ms
              :time-to-full-ms           full-ms
              :last-input-to-full-ms     (- (now) last-input-ms)}))
          (.catch (fn [cause]
                    (throw (partial-error cause {:cached-slices cached-slices
                                                 :authored-sleeps authored-sleeps}))))))))


(defn finish-camera!
  "Awaits a session, settles, ends the camera and drains through Full.
  Failures keep completed cached previews and authored pauses."
  [session-p]
  (-> (js/Promise.resolve session-p)
      (.then (fn [{:keys [hooks settle-ms] :as session}]
               (let [evidence (select-keys session [:cached-slices :authored-sleeps])]
                 (try
                   ((:check hooks))
                   (-> ((:sleep hooks) settle-ms)
                       (.then (fn [_] (finish-interaction hooks session)))
                       (.catch (fn [cause] (throw (partial-error cause evidence)))))
                   (catch :default cause
                     (js/Promise.reject (partial-error cause evidence)))))))))
