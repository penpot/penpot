;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.measurements-test
  (:require
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.types.shape :as cts]
   [app.main.ui.measurements :as msr]
   [cljs.test :as t :include-macros true]))

;; At zoom 1 the badge is 16 high, has 6 of horizontal padding, 6.5 per
;; character, and sits 8 away from the shape: its center is 16 away.
;; A flat path selrect has a minimum side of 0.01, which the measures
;; inputs show too.

(def ^:private big-vbox {:x -1000 :y -1000 :width 5000 :height 5000})

(defn- rect
  [x y width height]
  (cts/setup-shape {:type :rect :x x :y y :width width :height height}))

(defn- polyline
  "A path through `points`, each a [x y] pair."
  [& points]
  (let [[[x0 y0] & others] points]
    (cts/setup-shape
     {:type :path
      :content (into [{:command :move-to :params {:x x0 :y y0}}]
                     (map (fn [[x y]] {:command :line-to :params {:x x :y y}}))
                     others)})))

(defn- close-layout?
  [expected layout]
  (and (some? layout)
       (every? (fn [[k v]]
                 (let [actual (get layout k)]
                   (if (number? v)
                     (mth/close? v actual 0.001)
                     (= v actual))))
               expected)))

;; --- Plain shapes

(t/deftest rect-badge-sits-centered-below-the-shape
  (let [layout (msr/size-badge-layout [(rect 0 0 100 50)] 1 big-vbox)]
    ;; "100 x 50" has 8 characters: 8 * 6.5 + 2 * 6
    (t/is (close-layout? {:text "100 x 50" :width 64 :height 16
                          :cx 50 :cy 66 :rot 0}
                         layout))))

(t/deftest rect-narrower-than-the-badge-hides-it
  (t/is (nil? (msr/size-badge-layout [(rect 0 0 40 40)] 1 big-vbox))))

(t/deftest rect-lower-than-the-badge-hides-it
  (t/is (nil? (msr/size-badge-layout [(rect 0 0 200 10)] 1 big-vbox))))

(t/deftest badge-size-scales-with-the-zoom
  ;; At zoom 2 the badge is 28.75 x 8, so a 40 x 40 rect fits it.
  (let [layout (msr/size-badge-layout [(rect 0 0 40 40)] 2 big-vbox)]
    (t/is (close-layout? {:text "40 x 40" :width 28.75 :height 8 :cy 48}
                         layout))))

(t/deftest rect-badge-moves-above-at-the-vbox-bottom
  (let [vbox   {:x 0 :y 0 :width 1000 :height 60}
        layout (msr/size-badge-layout [(rect 0 0 100 50)] 1 vbox)]
    (t/is (close-layout? {:cx 50 :cy -16 :rot 0} layout))))

(t/deftest multiple-shapes-show-the-bounding-box
  (let [layout (msr/size-badge-layout [(rect 0 0 100 50) (rect 200 100 100 50)]
                                      1 big-vbox)]
    (t/is (close-layout? {:text "300 x 150" :cx 150 :cy 166} layout))))

;; --- Paths

(t/deftest path-badge-sits-centered-below-the-path
  ;; Three points, so not a straight line: it uses the box badge.
  (let [layout (msr/size-badge-layout [(polyline [0 0] [100 100] [200 0])]
                                      1 big-vbox)]
    (t/is (close-layout? {:text "200 x 100" :cx 100 :cy 116 :rot 0}
                         layout))))

(t/deftest path-lower-than-the-badge-hides-it
  (t/is (nil? (msr/size-badge-layout [(polyline [0 0] [100 10] [200 0])]
                                     1 big-vbox)))
  ;; A flat path, too: its height is the 0.01 minimum.
  (t/is (nil? (msr/size-badge-layout [(polyline [0 100] [100 100] [200 100])]
                                     1 big-vbox))))

(t/deftest path-narrower-than-the-badge-hides-it
  (t/is (nil? (msr/size-badge-layout [(polyline [0 0] [10 100] [20 0])]
                                     1 big-vbox))))

(t/deftest path-badge-reads-the-selrect-like-the-inputs
  ;; The points enclose a larger box than the selrect, as they do on a
  ;; rotated path. The text must match the measures inputs.
  (let [path   (-> (polyline [0 0] [100 50] [200 0])
                   (assoc :points (grc/rect->points (grc/make-rect 0 0 300 300))))
        layout (msr/size-badge-layout [path] 1 big-vbox)]
    (t/is (= "200 x 50" (:text layout)))))

;; --- Straight two-point lines

(t/deftest horizontal-line-badge-runs-below-the-line
  (let [layout (msr/size-badge-layout [(polyline [0 50] [200 50])] 1 big-vbox)]
    (t/is (close-layout? {:text "200" :cx 100 :cy 66 :rot 0} layout))))

(t/deftest line-drawn-right-to-left-keeps-the-text-upright
  (let [layout (msr/size-badge-layout [(polyline [200 50] [0 50])] 1 big-vbox)]
    (t/is (close-layout? {:cx 100 :cy 66 :rot 0} layout))))

(t/deftest vertical-line-badge-runs-along-the-line
  (let [down (msr/size-badge-layout [(polyline [0 0] [0 200])] 1 big-vbox)
        up   (msr/size-badge-layout [(polyline [0 200] [0 0])] 1 big-vbox)]
    (t/is (close-layout? {:text "200" :cx -16 :cy 100 :rot 90} down))
    (t/is (close-layout? {:cx -16 :cy 100 :rot 90} up))))

(t/deftest diagonal-line-badge-follows-the-line-angle
  (let [layout (msr/size-badge-layout [(polyline [0 0] [100 100])] 1 big-vbox)
        offset (/ 16 (mth/sqrt 2))]
    (t/is (close-layout? {:text "141.42" :cx (- 50 offset) :cy (+ 50 offset) :rot 45}
                         layout))))

(t/deftest line-shorter-than-the-badge-hides-it
  ;; "20" needs a 25 wide badge.
  (t/is (nil? (msr/size-badge-layout [(polyline [0 0] [20 0])] 1 big-vbox))))

(t/deftest line-badge-shows-the-length-with-two-decimals-at-most
  (t/is (= "70.71" (:text (msr/size-badge-layout [(polyline [0 0] [50 50])] 1 big-vbox))))
  (t/is (= "50" (:text (msr/size-badge-layout [(polyline [0 0] [30 40])] 1 big-vbox)))))

(t/deftest diagonal-line-uses-its-length-to-fit-the-badge
  ;; Both sides (33) are narrower than the badge for "46.67" (44.5), but
  ;; the line is about 46.67 long, so the badge fits along it.
  (t/is (some? (msr/size-badge-layout [(polyline [0 0] [33 33])] 1 big-vbox))))

(t/deftest line-badge-moves-to-the-other-side-at-the-vbox-bottom
  (let [vbox   {:x 0 :y 0 :width 1000 :height 60}
        layout (msr/size-badge-layout [(polyline [0 50] [200 50])] 1 vbox)]
    (t/is (close-layout? {:cx 100 :cy 34 :rot 0} layout))))
