;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.logic.nudge-selected-shapes-test
  "Regression tests for the keyboard-nudge transform stream.

  Holding an arrow key on a selection drives `nudge-selected-shapes`
  through OS key-repeat. Before the throttle introduced for issue #10726,
  every key-repeat was mapped 1:1 into a `set-modifiers`/`set-wasm-modifiers`
  store write, which could starve the renderer and trip React error #185
  (Maximum update depth exceeded).

  These tests do NOT reproduce the render storm (that requires real React
  renders and real OS key-repeat timing the unit harness cannot simulate).
  They guard the invariant the throttle must preserve: after a burst of
  `move-selected` events, the final committed shape position equals exactly
  `(event-count) * nudge-step` — i.e. the throttle drops no displacement.
  The legacy (non-WASM) branch case specifically guards the new un-sampled
  `rx/last` commit substream against `sample` dropping the tail value on
  completion."
  (:require
   [app.common.geom.rect :as grc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.transforms :as dwt]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]))

(t/use-fixtures :each
  {:before (fn []
             (cthi/reset-idmap!)
             ;; The Node test environment has no real WASM binary, so the
             ;; WASM-branch cases below would throw inside `wasm.api/*`
             ;; calls. Install lightweight mocks that let the CLJS-side
             ;; logic (`apply-wasm-modifiers`'s `dwsh/update-shapes`)
             ;; run normally while stubbing the WASM heap boundary.
             (thw/setup-wasm-mocks!))
   :after  (fn []
             ;; Teardown runs after the async test's `done` fires.
             (thw/teardown-wasm-mocks!))})

(def ^:private nudge-event-count
  "Number of `move-selected` events emitted in a burst. Chosen large enough
  that, with a 16 ms `sample` cadence and the unit harness's synchronous
  dispatch, the throttled live-preview substream would coalesce emissions
  were it not for the final `rx/last` commit honoring the exact cumulative
  position."
  20)

(def ^:private run-store-timeout-ms
  "Delay before `run-store` invokes its completion callback.

  `nudge-selected-shapes` does not complete its transform streams on source
  completion alone; its internal stopper fires either after 1000 ms of idle
  time or 250 ms after a key-up event (see `transforms.cljs`). The unit
  harness emits no keyboard events, so only the 1000 ms idle timer can fire.
  We let the harness wait long enough for that timer to fire and run the
  final `apply-modifiers`/`finish-transform` before reading the store."
  1500)

(defn- run-nudge-store
  "Like `ths/run-store` but the outer subscription completes on a fixed
  timer instead of immediately on `:the/end`, giving the nudge's idle
  stopper time to fire and commit the cumulative displacement."
  [store done events completed-cb]
  (ths/run-store store done events completed-cb (fn [_stream] (rx/timer run-store-timeout-ms))))

(defn- shape-x
  "Read a shape's committed x position from its points-derived rect."
  [file label]
  (let [shape (cths/get-shape file label)
        rect  (grc/points->rect (:points shape))]
    (:x rect)))

(defn- shape-y
  "Read a shape's committed y position from its points-derived rect."
  [file label]
  (let [shape (cths/get-shape file label)
        rect  (grc/points->rect (:points shape))]
    (:y rect)))

(defn- make-file
  []
  (-> (cthf/sample-file :file1)
      (ctho/add-rect :rect1 :x 100 :y 200 :width 50 :height 50)))

(defn- burst-move-events
  "Builds the event sequence: select rect, then `n` `move-selected` events
  in the given `direction` (with `shift?` controlling the nudge big/small
  step size — false = 1 px, true = 10 px)."
  [file direction shift? n]
  (let [rect (cths/get-shape file :rect1)]
    (into [(dws/select-shape (:id rect))]
          (repeat n (dwt/move-selected direction shift?)))))

;; ---------------------------------------------------------------------------
;; Default (WASM) branch — `render-wasm/v1` active (helpers/state default).
;; ---------------------------------------------------------------------------

(t/deftest nudge-burst-wasm-branches-commits-exact-final-position-right-small
  (t/async
    done
    (let [file   (make-file)
          store  (ths/setup-store file)
          events (burst-move-events file :right false nudge-event-count)]
      (run-nudge-store
       store done events
       (fn [new-state]
         (let [file'    (ths/get-file-from-state new-state)
               final-x  (shape-x file' :rect1)]
           ;; nudge small = 1 px, direction :right => +x.
           (t/is (= (+ 100 nudge-event-count) final-x)
                 "WASM branch: final x equals original + N * small-nudge after a throttled burst")))))))

(t/deftest nudge-burst-wasm-branches-commits-exact-final-position-up-big
  (t/async
    done
    (let [file   (make-file)
          store  (ths/setup-store file)
          events (burst-move-events file :up true nudge-event-count)]
      (run-nudge-store
       store done events
       (fn [new-state]
         (let [file'    (ths/get-file-from-state new-state)
               final-y  (shape-y file' :rect1)]
           ;; nudge big = 10 px, direction :up => -y.
           (t/is (= (- 200 (* 10 nudge-event-count)) final-y)
                 "WASM branch: final y equals original - N * big-nudge after a throttled burst")))))))

;; ---------------------------------------------------------------------------
;; Legacy (non-WASM) branch — :renderer :svg disables render-wasm/v1.
;; This case specifically guards the new `rx/last` commit substream added
;; alongside the live-preview sample: if `sample` drops the tail value on
;; completion, the un-sampled `rx/last` write still delivers the exact
;; cumulative position to `apply-modifiers`.
;; ---------------------------------------------------------------------------

(t/deftest nudge-burst-legacy-branch-commits-exact-final-position-right-small
  (t/async
    done
    (let [file   (make-file)
          store  (ths/setup-store file {:renderer :svg})
          events (burst-move-events file :right false nudge-event-count)]
      (run-nudge-store
       store done events
       (fn [new-state]
         (let [file'    (ths/get-file-from-state new-state)
               final-x  (shape-x file' :rect1)]
           (t/is (= (+ 100 nudge-event-count) final-x)
                 "Legacy branch: final x equals original + N * small-nudge after a throttled burst")))))))

(t/deftest nudge-burst-legacy-branch-commits-exact-final-position-up-big
  (t/async
    done
    (let [file   (make-file)
          store  (ths/setup-store file {:renderer :svg})
          events (burst-move-events file :up true nudge-event-count)]
      (run-nudge-store
       store done events
       (fn [new-state]
         (let [file'    (ths/get-file-from-state new-state)
               final-y  (shape-y file' :rect1)]
           (t/is (= (- 200 (* 10 nudge-event-count)) final-y)
                 "Legacy branch: final y equals original - N * big-nudge after a throttled burst")))))))