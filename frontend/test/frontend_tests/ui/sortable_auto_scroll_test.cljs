;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.sortable-auto-scroll-test
  (:require
   [app.common.math :as mth]
   [app.main.ui.hooks :as hooks]
   [app.util.timers :as ts]
   [cljs.test :as t :include-macros true]))

(def ^:private auto-scroll-speed @#'hooks/auto-scroll-speed)
(def ^:private auto-scroll-state @#'hooks/auto-scroll-state)
(def ^:private start-auto-scroll! @#'hooks/start-auto-scroll!)
(def ^:private update-auto-scroll! @#'hooks/update-auto-scroll!)
(def ^:private end-auto-scroll! @#'hooks/end-auto-scroll!)

(t/use-fixtures :each {:before #(reset! auto-scroll-state nil)
                       :after  #(reset! auto-scroll-state nil)})

;; A 400px tall list, with the 56px edge zones at [0, 56] and [344, 400].
(def ^:private rect {:top 0 :bottom 400})

(t/deftest auto-scroll-speed-test
  (t/testing "does not scroll when the pointer is away from the edges"
    (t/is (= 0 (auto-scroll-speed rect 200)))
    (t/is (= 0 (auto-scroll-speed rect 56)))
    (t/is (= 0 (auto-scroll-speed rect 344))))

  (t/testing "does not scroll when the pointer is outside the container"
    (t/is (= 0 (auto-scroll-speed rect -10)))
    (t/is (= 0 (auto-scroll-speed rect 410))))

  (t/testing "scrolls upwards near the top edge and downwards near the bottom"
    (t/is (neg? (auto-scroll-speed rect 10)))
    (t/is (pos? (auto-scroll-speed rect 390))))

  (t/testing "scrolls faster the closer the pointer gets to the edge"
    (t/is (< (auto-scroll-speed rect 5) (auto-scroll-speed rect 50) 0))
    (t/is (< 0 (auto-scroll-speed rect 350) (auto-scroll-speed rect 395)))))

(t/deftest auto-scroll-speed-short-list-test
  ;; In a 100px list each zone is capped at 25px, a quarter of the height.
  (let [rect {:top 0 :bottom 100}]
    (t/testing "the middle half of the list never scrolls"
      (t/is (= 0 (auto-scroll-speed rect 25)))
      (t/is (= 0 (auto-scroll-speed rect 50)))
      (t/is (= 0 (auto-scroll-speed rect 75))))

    (t/testing "the capped zones still scroll"
      (t/is (neg? (auto-scroll-speed rect 10)))
      (t/is (pos? (auto-scroll-speed rect 90))))))

(defn- scroll-container
  "A fake 400px tall scroll container that records the scrolled pixels."
  [scrolled]
  #js {:contains (fn [_] true)
       :getBoundingClientRect (fn [] #js {:top 0 :bottom 400})
       :scrollBy (fn [_ y] (swap! scrolled conj y))})

(defn- start-drag!
  [element]
  (reset! auto-scroll-state {:element element}))

(t/deftest start-auto-scroll-without-container-test
  (start-auto-scroll! #js {:parentElement nil})
  (t/is (= {:element nil} @auto-scroll-state)))

(t/deftest start-auto-scroll-stops-previous-loop-test
  ;; The previous drag ended without a dragend, so its loop is still running.
  (let [cancelled (atom [])]
    (with-redefs [ts/cancel-af! (fn [frame] (swap! cancelled conj frame))]
      (reset! auto-scroll-state {:element #js {} :frame 9})
      (start-auto-scroll! #js {:parentElement nil})
      (t/is (= [9] @cancelled))
      (t/is (= {:element nil} @auto-scroll-state)))))

(t/deftest update-auto-scroll-starts-one-loop-test
  (let [scheduled (atom [])
        element   (scroll-container (atom []))]
    (with-redefs [ts/raf (fn [f] (swap! scheduled conj f) (count @scheduled))]
      (start-drag! element)
      (update-auto-scroll! #js {} 10)
      (update-auto-scroll! #js {} 5)
      (update-auto-scroll! #js {} 1)
      (t/is (= 1 (count @scheduled)) "dragover events reuse the running loop")
      (t/is (= 1 (:frame @auto-scroll-state)))
      (t/is (= (auto-scroll-speed rect 1) (:speed @auto-scroll-state))
            "the loop uses the latest pointer position"))))

(t/deftest update-auto-scroll-away-from-edges-does-not-start-test
  (let [scheduled (atom [])
        element   (scroll-container (atom []))]
    (with-redefs [ts/raf (fn [f] (swap! scheduled conj f) 1)]
      (start-drag! element)
      (update-auto-scroll! #js {} 200)
      (t/is (empty? @scheduled)))))

(t/deftest auto-scroll-tick-scrolls-and-continues-test
  (let [scheduled (atom [])
        scrolled  (atom [])
        element   (scroll-container scrolled)]
    (with-redefs [ts/raf (fn [f] (swap! scheduled conj f) (count @scheduled))]
      (start-drag! element)
      (update-auto-scroll! #js {} 0)
      ((last @scheduled) (js/performance.now))
      (t/is (= [(auto-scroll-speed rect 0)] @scrolled))
      (t/is (= 2 (count @scheduled)) "the next frame is scheduled")
      (t/is (= 2 (:frame @auto-scroll-state))))))

(t/deftest auto-scroll-stops-when-speed-goes-back-to-zero-test
  (let [scheduled (atom [])
        cancelled (atom [])
        element   (scroll-container (atom []))]
    (with-redefs [ts/raf        (fn [f] (swap! scheduled conj f) 7)
                  ts/cancel-af! (fn [frame] (swap! cancelled conj frame))]
      (start-drag! element)
      (update-auto-scroll! #js {} 10)
      (update-auto-scroll! #js {} 200)
      (t/is (= [7] @cancelled) "the pending frame is cancelled")
      (t/is (nil? (:frame @auto-scroll-state)))
      (t/is (identical? element (:element @auto-scroll-state))
            "the container is kept for the rest of the drag")

      (update-auto-scroll! #js {} 10)
      (t/is (= 2 (count @scheduled)) "it starts again near the edge"))))

(t/deftest auto-scroll-stops-on-drag-end-test
  (let [cancelled (atom [])
        element   (scroll-container (atom []))]
    (with-redefs [ts/raf        (fn [_] 3)
                  ts/cancel-af! (fn [frame] (swap! cancelled conj frame))]
      (start-drag! element)
      (update-auto-scroll! #js {} 10)
      (end-auto-scroll!)
      (t/is (= [3] @cancelled))
      (t/is (nil? @auto-scroll-state)))))

(t/deftest auto-scroll-stale-guard-test
  (let [scheduled (atom [])
        scrolled  (atom [])
        element   (scroll-container scrolled)]
    (with-redefs [ts/raf        (fn [f] (swap! scheduled conj f) 1)
                  ts/cancel-af! (fn [_])]
      (start-drag! element)
      (update-auto-scroll! #js {} 10)
      (let [updated-at (:updated-at @auto-scroll-state)]
        ;; No dragover for more than 500ms, as when a dragend is lost.
        ((last @scheduled) (+ updated-at 501)))
      (t/is (empty? @scrolled) "it does not scroll on a stale frame")
      (t/is (= 1 (count @scheduled)) "no further frames are scheduled")
      (t/is (nil? @auto-scroll-state)))))

(defn- scroll-for-two-60hz-frames
  "Runs the loop for the time of two 60 Hz frames, at `frames` frames per
  60 Hz frame, and returns the scrolled pixels. The first frame has no
  previous one to measure from, so it counts as a full 60 Hz frame."
  [frames]
  (let [scheduled (atom [])
        scrolled  (atom [])
        frame-ms  (/ 1000 60 frames)]
    (with-redefs [ts/raf (fn [f] (swap! scheduled conj f) 1)]
      (reset! auto-scroll-state nil)
      (start-drag! (scroll-container scrolled))
      (update-auto-scroll! #js {} 0)
      (let [start (:updated-at @auto-scroll-state)]
        (dotimes [i (inc frames)]
          ((last @scheduled) (+ start (* i frame-ms))))))
    (reduce + @scrolled)))

(t/deftest auto-scroll-speed-does-not-depend-on-refresh-rate-test
  (let [at-60hz  (scroll-for-two-60hz-frames 1)
        at-120hz (scroll-for-two-60hz-frames 2)]
    (t/is (neg? at-60hz))
    (t/is (<= (mth/abs (- at-120hz at-60hz)) 1)
          "a 120 Hz screen scrolls the same distance in the same time")))
