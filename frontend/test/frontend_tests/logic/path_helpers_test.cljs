;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL


(ns frontend-tests.logic.path-helpers-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.types.path :as path]
   [app.main.data.workspace.path.helpers :as path.helpers]
   [app.main.data.workspace.path.streams :as path.streams]
   [app.main.store :as st]
   [cljs.test :as t :include-macros true]
   [frontend-tests.logic.path-test-helpers :as pth]))

(t/deftest to-pixel-snap-quantises-to-half-pixels-past-the-zoom-threshold
  ;; Pixel snapping uses half steps above 300% zoom.
  (let [original @st/state
        snap     (fn [zoom p]
                   (reset! st/state {:workspace-layout #{:snap-pixel-grid}
                                     :workspace-local {:zoom zoom}})
                   (path.streams/to-pixel-snap p))]
    (try
      ;; at or below the threshold, snapping rounds to whole pixels
      (t/is (= (gpt/point 100 100) (snap 1 (gpt/point 100.4 100.4))))
      (t/is (= (gpt/point 100 100) (snap 3 (gpt/point 100.4 100.4))))
      ;; above 300% zoom it rounds to the nearest half pixel
      (t/is (= (gpt/point 100.5 100.5) (snap 6 (gpt/point 100.4 100.4))))
      ;; with pixel snapping off the position passes through unchanged
      (reset! st/state {:workspace-layout #{} :workspace-local {:zoom 6}})
      (t/is (= (gpt/point 100.4 100.4)
               (path.streams/to-pixel-snap (gpt/point 100.4 100.4))))
      (finally
        (reset! st/state original)))))

(t/deftest node-merge-snap-finds-the-closest-target-for-multiple-moving-points
  (let [start-point     (gpt/point 0 0)
        selected-points #{start-point (gpt/point 100 0)}
        points          (into selected-points
                              [(gpt/point 8 14)
                               (gpt/point 111 10.5)
                               (gpt/point 500 500)])
        snap-position   (path.streams/make-node-merge-snap
                         start-point selected-points points 10)]
    ;; The closest merge target moves the full selection.
    (t/is (= (gpt/point 11 10.5)
             (snap-position (gpt/point 10 10))))
    ;; Missing merge targets return no snap delta.
    (t/is (nil? (snap-position (gpt/point 300 300))))))

(t/deftest insertion-preview-reuses-precomputed-segment-midpoints
  (let [content    (path/content
                    [{:command :move-to :params {:x 0 :y 0}}
                     {:command :line-to :params {:x 10 :y 0}}
                     {:command :curve-to
                      :params {:c1x 10 :c1y 0
                               :c2x 20 :c2y 10
                               :x 20 :y 0}}
                     {:command :close-path :params {}}])
        midpoints  (path.helpers/insertion-mid-points content)
        line-mid   (first midpoints)
        curve-mid  (second midpoints)]
    (t/is (= 2 (count midpoints)))
    (t/is (= (gpt/point 5 0) line-mid))
    (t/is (= {:from-p (gpt/point 0 0)
              :to-p   (gpt/point 10 0)
              :t      0.5}
             (meta line-mid)))
    (t/is (= line-mid
             (path.helpers/insertion-point
              content (gpt/point 5.5 0) 1 false midpoints)))
    (t/is (nil? (path.helpers/insertion-point
                 content (gpt/point 200 200) 1 false midpoints)))
    ;; Alt/insert-anywhere remains dynamic and ignores the midpoint cache.
    (t/is (some? (path.helpers/insertion-point
                  content curve-mid 1 true [])))))

(t/deftest selected-node-indices-folds-segment-endpoints
  (let [content (pth/selectable-path-content)]
    ;; segment index 1 connects nodes 0 and 1
    (t/is (= #{0 1}
             (path.helpers/selected-node-indices content {:nodes #{} :segments #{1}})))
    ;; explicit nodes and segment endpoints are unioned
    (t/is (= #{0 1 2}
             (path.helpers/selected-node-indices content {:nodes #{2} :segments #{1}})))))

(t/deftest remap-selection-follows-content-structure
  (let [content     (pth/selectable-path-content)
        ;; Same command layout: index 1 turned into a line-to
        corner      (path/content
                     (assoc (vec content) 1 {:command :line-to
                                             :params {:x 10 :y 0}}))
        ;; Different layout: the middle node was removed
        shorter     (path/content
                     [{:command :move-to
                       :params {:x 0 :y 0}}
                      {:command :line-to
                       :params {:x 20 :y 0}}])
        selection   {:nodes #{1 2}
                     :segments #{2}
                     :handlers #{[1 :c1] [2 :c2]}}]
    (t/is (= {:nodes #{1 2}
              :segments #{2}
              :handlers #{[2 :c2]}}
             (path.helpers/remap-selection selection content corner)))
    (t/is (= {:nodes #{1}
              :segments #{}
              :handlers #{}}
             (path.helpers/remap-selection selection content shorter)))))

(t/deftest handlers-joined-detects-smooth-vs-corner-nodes
  ;; node (10,0): incoming [1 :c2]=(8,0), outgoing [2 :c1]
  (t/is (path.helpers/handlers-joined? (pth/selectable-path-content) 2 :c1))
  (t/is (not (path.helpers/handlers-joined? (pth/corner-path-content) 2 :c1))))

