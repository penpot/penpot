;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.logic.sidebar-transform-coalescing-test
  "Regression tests for the sidebar measures panel transform coalescing
  (React error #185): a burst of numeric-input gestures (held arrow key,
  wheel, scrub) must collapse to a handful of commits, and the trailing
  flush must land the exact final value."
  (:require
   [app.common.geom.rect :as grc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.transforms :as-alias dwt]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before (fn [] (thp/reset-idmap!) (thw/setup-wasm-mocks!))
   :after (fn [] (thw/teardown-wasm-mocks!))})

(def ^:private flush-wait-ms
  "How long to keep the store running after a burst so the 50 ms
  trailing flush fires before checking the final state."
  150)

(defn- count-events
  "Return an atom counting how many events of `type` get emitted on the
  store input stream (i.e. the real commits triggered by the coalescer)."
  [store type]
  (let [counter (atom 0)]
    (->> (ptk/input-stream store)
         (rx/filter (ptk/type? type))
         (rx/tap (fn [_] (swap! counter inc)))
         (rx/subs! (fn [_] nil)))
    counter))

(defn- run-store-timed
  "Like `ths/run-store`, but emits `:the/end` `wait-ms` after `events`
  so the timer-based coalescing (throttle/debounce) gets to fire."
  [store done events wait-ms completed-cb]
  (->> (ptk/input-stream store)
       (rx/filter #(= :the/end %))
       (rx/take 1)
       (rx/tap (fn [_] (completed-cb @store)))
       (rx/subs! (fn [_] nil)
                 (fn [cause]
                   (done)
                   (t/do-report {:type :error :message "Stream error" :actual cause}))
                 (fn [_] (done))))
  (doseq [event events]
    (ptk/emit! store event))
  (js/setTimeout (fn [] (ptk/emit! store :the/end)) wait-ms))

(defn- burst
  "A burst of `n` events built with `make-event`, like the stream of
  calls a held arrow key or a scrub gesture produces."
  [n make-event]
  (mapv make-event (range 1 (inc n))))

;; --- Positions (update-positions, coalesced in place) -----------------

(t/deftest update-positions-burst-commits-exact-final-value-wasm
  (t/async
    done
    (let [file    (-> (cthf/sample-file :file1)
                      (ctho/add-frame :frame1 :x 0 :y 0 :width 100 :height 100))
          store   (ths/setup-store file)
          frame1  (cths/get-shape file :frame1)
          commits (count-events store ::dwt/update-position)
          events  (burst 20 (fn [i] (dw/update-positions [(:id frame1)] {:x (+ 100 i)})))]
      (run-store-timed
       store done events flush-wait-ms
       (fn [new-state]
         (let [file'   (ths/get-file-from-state new-state)
               frame1' (cths/get-shape file' :frame1)
               x       (-> frame1' :points grc/points->rect :x)]
           ;; The trailing flush lands the exact final value...
           (t/is (= 120 x))
           ;; ...and the 20-event burst collapsed to a handful of commits.
           (t/is (<= @commits 3))))))))

(t/deftest update-positions-burst-commits-exact-final-value-svg
  (t/async
    done
    (let [file    (-> (cthf/sample-file :file1)
                      (ctho/add-frame :frame1 :x 0 :y 0 :width 100 :height 100))
          store   (ths/setup-store file {:renderer :svg})
          frame1  (cths/get-shape file :frame1)
          commits (count-events store ::dwt/update-position)
          events  (burst 20 (fn [i] (dw/update-positions [(:id frame1)] {:x (+ 100 i)})))]
      (run-store-timed
       store done events flush-wait-ms
       (fn [new-state]
         (let [file'   (ths/get-file-from-state new-state)
               frame1' (cths/get-shape file' :frame1)
               x       (-> frame1' :points grc/points->rect :x)]
           (t/is (= 120 x))
           (t/is (<= @commits 3))))))))

(t/deftest update-positions-burst-merges-x-and-y
  (t/async
    done
    (let [file    (-> (cthf/sample-file :file1)
                      (ctho/add-frame :frame1 :x 0 :y 0 :width 100 :height 100))
          store   (ths/setup-store file)
          frame1  (cths/get-shape file :frame1)
          commits (count-events store ::dwt/update-position)
          events  (into (burst 10 (fn [i] (dw/update-positions [(:id frame1)] {:x (+ 100 i)})))
                        (burst 10 (fn [i] (dw/update-positions [(:id frame1)] {:y (+ 200 i)}))))]
      (run-store-timed
       store done events flush-wait-ms
       (fn [new-state]
         (let [file'   (ths/get-file-from-state new-state)
               frame1' (cths/get-shape file' :frame1)
               rect    (-> frame1' :points grc/points->rect)]
           ;; Partial position maps of the same shape merge, so the last
           ;; value of each attribute lands.
           (t/is (= 110 (:x rect)))
           (t/is (= 210 (:y rect)))
           (t/is (<= @commits 3))))))))

;; --- Dimensions (update-dimensions-coalesced) --------------------------

(t/deftest update-dimensions-burst-commits-exact-final-value-wasm
  (t/async
    done
    (let [file    (-> (cthf/sample-file :file1)
                      (ctho/add-rect :rect1 :x 0 :y 0 :width 100 :height 100))
          store   (ths/setup-store file)
          rect1   (cths/get-shape file :rect1)
          commits (count-events store ::dwt/update-dimensions)
          events  (burst 20 (fn [i] (dw/update-dimensions-coalesced [(:id rect1)] :width (+ 100 i))))]
      (run-store-timed
       store done events flush-wait-ms
       (fn [new-state]
         (let [file'  (ths/get-file-from-state new-state)
               rect1' (cths/get-shape file' :rect1)
               width  (-> rect1' :points grc/points->rect :width)]
           (t/is (= 120 width))
           (t/is (<= @commits 3))))))))

(t/deftest update-dimensions-burst-commits-exact-final-value-svg
  (t/async
    done
    (let [file    (-> (cthf/sample-file :file1)
                      (ctho/add-rect :rect1 :x 0 :y 0 :width 100 :height 100))
          store   (ths/setup-store file {:renderer :svg})
          rect1   (cths/get-shape file :rect1)
          commits (count-events store ::dwt/update-dimensions)
          events  (burst 20 (fn [i] (dw/update-dimensions-coalesced [(:id rect1)] :width (+ 100 i))))]
      (run-store-timed
       store done events flush-wait-ms
       (fn [new-state]
         (let [file'  (ths/get-file-from-state new-state)
               rect1' (cths/get-shape file' :rect1)
               width  (-> rect1' :points grc/points->rect :width)]
           (t/is (= 120 width))
           (t/is (<= @commits 3))))))))

(t/deftest update-dimensions-burst-merges-width-and-height
  (t/async
    done
    (let [file    (-> (cthf/sample-file :file1)
                      (ctho/add-rect :rect1 :x 0 :y 0 :width 100 :height 100))
          store   (ths/setup-store file)
          rect1   (cths/get-shape file :rect1)
          commits (count-events store ::dwt/update-dimensions)
          events  (into (burst 10 (fn [i] (dw/update-dimensions-coalesced [(:id rect1)] :width (+ 100 i))))
                        (burst 10 (fn [i] (dw/update-dimensions-coalesced [(:id rect1)] :height (+ 200 i)))))]
      (run-store-timed
       store done events flush-wait-ms
       (fn [new-state]
         (let [file'  (ths/get-file-from-state new-state)
               rect1' (cths/get-shape file' :rect1)
               rect   (-> rect1' :points grc/points->rect)]
           ;; Each attribute keeps its own latest queued value.
           (t/is (= 110 (:width rect)))
           (t/is (= 210 (:height rect)))
           ;; At most 3 flushes; the trailing one commits both pending
           ;; attributes, hence 4 commit events.
           (t/is (<= @commits 4))))))))

;; --- Rotation (increase-rotation-coalesced) ----------------------------

(t/deftest increase-rotation-burst-commits-exact-final-value-wasm
  (t/async
    done
    (let [file    (-> (cthf/sample-file :file1)
                      (ctho/add-rect :rect1 :x 0 :y 0 :width 100 :height 100))
          store   (ths/setup-store file)
          rect1   (cths/get-shape file :rect1)
          commits (count-events store ::dwt/increase-rotation)
          events  (burst 20 (fn [i] (dw/increase-rotation-coalesced [(:id rect1)] (* i 3))))]
      (run-store-timed
       store done events flush-wait-ms
       (fn [new-state]
         (let [file'  (ths/get-file-from-state new-state)
               rect1' (cths/get-shape file' :rect1)]
           (t/is (= 60 (:rotation rect1')))
           (t/is (<= @commits 3))))))))

(t/deftest increase-rotation-burst-commits-exact-final-value-svg
  (t/async
    done
    (let [file    (-> (cthf/sample-file :file1)
                      (ctho/add-rect :rect1 :x 0 :y 0 :width 100 :height 100))
          store   (ths/setup-store file {:renderer :svg})
          rect1   (cths/get-shape file :rect1)
          commits (count-events store ::dwt/increase-rotation)
          events  (burst 20 (fn [i] (dw/increase-rotation-coalesced [(:id rect1)] (* i 3))))]
      (run-store-timed
       store done events flush-wait-ms
       (fn [new-state]
         (let [file'  (ths/get-file-from-state new-state)
               rect1' (cths/get-shape file' :rect1)]
           (t/is (= 60 (:rotation rect1')))
           (t/is (<= @commits 3))))))))
