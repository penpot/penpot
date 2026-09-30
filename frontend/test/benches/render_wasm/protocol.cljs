;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.protocol
  "Shared render-completion protocol for the renderer benchmarks.

  The WASM renderer finishes progressively in tile budgets: one `_render`
  call returns a frame type and the caller keeps calling until `FRAME_TYPE_FULL`.
  This namespace implements a driver loop as three functions over an injected
  `hooks` map. Callers can use the same timing and continuation rules for
  rendering and interaction:

  - `drain` drives `_render` to `FRAME_TYPE_FULL`, collecting one slice per
    call.
  - `restore` puts the camera back to the case's initial view, then drains.
    Callers run it outside timing.
  - `interact` replays a deterministic gesture of small camera moves with
    cheap cached previews, waits out the declared settle, then drains to
    `FRAME_TYPE_FULL`. The caller's interaction timer includes the camera
    start, gesture, settle wait, camera end and final drain.

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
  next call's flags. `restore` and the final drain of `interact` start with
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
    Cached previews have their own shape: no flags or frame type.
  - `:viewport-ready-ms` first Full-or-ViewportReady completion measured
    from the drain origin. An immediate Full supplies both boundaries, so
    they are equal there.
  - `:full-ms` Full completion measured from the drain origin.
  - `:active-ms` from before `_set_view_start` to after the last cached
    preview and its guard check.
  - `:settling-requested-ms` the declared settle wait passed in.
  - `:settling-actual-ms` from the end of the last cached preview to
    finalization start, recorded separately from the declared value.
  - `:set-view-end-ms` wall time of the `_set_view_end` call itself.
    Finalization starts before `_set_view_end`, so the final drain times
    include it; the call time is still reported on its own.
  - `:time-to-viewport-ready-ms` / `:time-to-full-ms` the drain's
    boundaries, renamed at the interaction level.
  - `:last-input-to-full-ms` from the clock read before the last view update
    to the clock read after the final drain promise resolves."
  (:require
   [app.common.render-wasm.wasm :as wasm]))


(defn- next-render-timestamp
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
    (check)
    {:state      (update state :slices conj slice)
     :frame-type frame-type
     :ended-ms   ended-ms}))


(defn- valid-frame-type?
  [frame-type]
  (or (= frame-type wasm/FRAME_TYPE_PARTIAL)
      (= frame-type wasm/FRAME_TYPE_VIEWPORT_READY)
      (= frame-type wasm/FRAME_TYPE_FULL)))


(defn- viewport-ready-time
  [current-ready-ms frame-type ended-ms origin-ms]
  (or current-ready-ms
      (when (or (= frame-type wasm/FRAME_TYPE_VIEWPORT_READY)
                (= frame-type wasm/FRAME_TYPE_FULL))
        (- ended-ms origin-ms))))


(defn- advance-drain-state
  [{:keys [viewport-ready-ms] :as state}
   frame-type
   ended-ms
   origin-ms]
  (when-not (valid-frame-type? frame-type)
    (throw (ex-info (str "unexpected frame type: " (pr-str frame-type))
                    {:type ::unexpected-frame-type
                     :frame-type frame-type})))
  (assoc state
         :flags frame-type
         :viewport-ready-ms
         (viewport-ready-time viewport-ready-ms
                              frame-type
                              ended-ms
                              origin-ms)))

(defn- drain-complete?
  [frame-type]
  (= frame-type wasm/FRAME_TYPE_FULL))

(defn- drain-result
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
                         (step next-state false)))))))]

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

(defn- replay-frame
  [{:keys [now frame check render-from-cache set-view]}
   state
   view
   last-frame?]
  (-> (frame)
      (.then
       (fn [timestamp]
         (check)
         (let [input-ms (now)]
           (set-view view)
           (let [t0          (now)
                 _           (render-from-cache)
                 duration-ms (- (now) t0)]
             (check)
             (cond-> (-> state
                         (assoc :last-input-ms input-ms)
                         (update :cached-slices conj
                                 {:timestamp   timestamp
                                  :duration-ms duration-ms}))
               last-frame?
               (assoc :active-end-ms (now)))))))))


(defn- replay-gesture
  [{:keys [now] :as hooks} frames started-ms]
  (if (empty? frames)
    (js/Promise.resolve
     {:cached-slices []
      :last-input-ms started-ms
      :active-end-ms (now)})

    (let [last-index (dec (count frames))]
      (reduce-kv
       (fn [state-p index view]
         (.then state-p
                #(replay-frame hooks
                               %
                               view
                               (= index last-index))))
       (js/Promise.resolve
        {:cached-slices []
         :last-input-ms started-ms})
       frames))))


(defn- finish-interaction
  [{:keys [now check set-view-end] :as hooks}
   settle-ms
   started-ms
   {:keys [cached-slices last-input-ms active-end-ms]}]
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
              :active-ms                 (- active-end-ms started-ms)
              :settling-requested-ms     settle-ms
              :settling-actual-ms        (- finalization-start active-end-ms)
              :set-view-end-ms           set-view-end-ms
              :time-to-viewport-ready-ms viewport-ready-ms
              :time-to-full-ms           full-ms
              :last-input-to-full-ms     (- (now) last-input-ms)}))))))


(defn- settle-and-finish
  [{:keys [sleep] :as hooks}
   settle-ms
   started-ms
   gesture-state]
  (-> (sleep settle-ms)
      (.then #(finish-interaction hooks
                                  settle-ms
                                  started-ms
                                  gesture-state))))


(defn interact
  "Replays `frames` (a vector of `{:scale :x :y}` views) as one gesture
   and resolves the interaction metrics (see the namespace docstring).

  The flow is:

  interact
    ├─ set-view-start
    ├─ replay-gesture
    │    └─ replay-frame × N
    ├─ settle
    └─ finish-interaction
         ├─ set-view-end
         └─ drain

   Each frame waits for rAF, records the input time, sets the view and
   times one cached preview into `:cached-slices`. Afterwards the declared
   `settle-ms` elapses, `_set_view_end` is timed on its own, and a SyncTiles
   immediate drain runs with its origin at finalization start, so the final
   times include `_set_view_end` while reporting it separately."
  [{:keys [now check set-view-start] :as hooks}
   {:keys [frames settle-ms]}]
  (try
    (let [started-ms (do (check) (now))]
      (set-view-start)
      (-> (replay-gesture hooks (vec frames) started-ms)
          (.then #(settle-and-finish hooks
                                     settle-ms
                                     started-ms
                                     %))))
    (catch :default cause
      (js/Promise.reject cause))))
