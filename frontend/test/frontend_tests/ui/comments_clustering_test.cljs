;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.comments-clustering-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.main.data.comments :as dcm]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.ui.comments :as cmt]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(defn- thread
  [seqn x y]
  {:id seqn :seqn seqn :position (gpt/point x y)})

(defn- magnitude
  [p]
  (gpt/distance p (gpt/point 0 0)))

(defn- find-group
  "Return the group (from a group-bubbles result) that contains the thread with
   the given seqn."
  [groups seqn]
  (some (fn [group]
          (when (some #(= (:seqn %) seqn) group) group))
        groups))

;; --- overlap-bubbles? ------------------------------------------------------

(t/deftest overlap-bubbles-close
  (t/testing "bubbles within the overlap distance overlap"
    (t/is (true? (dwcm/overlap-bubbles? 1 (thread 1 100 100) (thread 2 110 100))))))

(t/deftest overlap-bubbles-far
  (t/testing "distant bubbles do not overlap"
    (t/is (false? (dwcm/overlap-bubbles? 1 (thread 1 100 100) (thread 2 200 100))))))

(t/deftest overlap-bubbles-zoom
  (t/testing "zoom scales the screen-space distance, separating close bubbles"
    ;; 10px apart in canvas space is 40px on screen at zoom 4, above the 32px
    ;; overlap threshold.
    (t/is (false? (dwcm/overlap-bubbles? 4 (thread 1 100 100) (thread 2 110 100))))))

;; --- group-bubbles ---------------------------------------------------------

(t/deftest group-bubbles-separate-clusters
  (let [threads [(thread 1 100 100)
                 (thread 2 105 100)
                 (thread 3 500 500)]
        groups  (dwcm/group-bubbles 1 threads)]
    (t/testing "produces one group per proximity cluster"
      (t/is (= 2 (count groups))))
    (t/testing "overlapping bubbles share a group"
      (t/is (= 2 (count (find-group groups 1))))
      (t/is (some #(= (:seqn %) 2) (find-group groups 1))))
    (t/testing "a distant bubble stays alone"
      (t/is (= 1 (count (find-group groups 3)))))))

(t/deftest group-bubbles-transitive-chain
  ;; A-B and B-C overlap but A-C do not; the shared neighbour B keeps them in a
  ;; single group.
  (let [threads [(thread 1 100 100)
                 (thread 2 120 100)
                 (thread 3 140 100)]
        groups  (dwcm/group-bubbles 1 threads)]
    (t/testing "a chain of overlaps forms a single group"
      (t/is (= 1 (count groups)))
      (t/is (= 3 (count (first groups)))))))

;; --- expanded-group-center -------------------------------------------------

(t/deftest expanded-group-center-centroid
  (t/testing "the cluster center is the centroid of the bubble positions"
    (t/is (= (gpt/point 150 150)
             (cmt/expanded-group-center [(thread 1 100 100)
                                         (thread 2 200 200)])))))

;; --- expanded-group-offsets ------------------------------------------------

(t/deftest expanded-group-offsets-single-ring
  (let [threads (mapv #(thread % 100 100) (range 6))
        offsets (cmt/expanded-group-offsets 1 threads)]
    (t/testing "lays out every bubble of the cluster"
      (t/is (= 6 (count offsets))))
    (t/testing "the innermost ring keeps a constant radius"
      (t/is (every? #(mth/close? 44 (magnitude (nth % 2))) offsets)))
    (t/testing "the first bubble is placed at the top of the ring"
      (let [ring (nth (first offsets) 2)]
        (t/is (mth/close? 0 (:x ring)))
        (t/is (mth/close? -44 (:y ring)))))))

(t/deftest expanded-group-offsets-concentric-rings
  (let [threads (mapv #(thread % 100 100) (range 8))
        rings   (mapv #(magnitude (nth % 2))
                      (cmt/expanded-group-offsets 1 threads))]
    (t/testing "the first six bubbles fill the inner ring"
      (t/is (every? #(mth/close? 44 %) (take 6 rings))))
    (t/testing "the overflow spills onto a second, wider ring"
      (t/is (every? #(mth/close? 88 %) (drop 6 rings))))))

(t/deftest expanded-group-offsets-total-offset
  (t/testing "the total offset moves each bubble from its position to the ring"
    ;; With all bubbles sharing a position, the base displacement is zero, so the
    ;; total offset equals the pure ring vector.
    (let [threads (mapv #(thread % 100 100) (range 3))]
      (doseq [[_ offset ring] (cmt/expanded-group-offsets 1 threads)]
        (t/is (mth/close? (:x offset) (:x ring)))
        (t/is (mth/close? (:y offset) (:y ring)))))))

;; --- cluster state transitions ---------------------------------------------

(t/deftest expand-comment-group-replaces-open
  (let [state  {:comments-local {:open 5 :draft {} :options 9}}
        result (ptk/update (dcm/expand-comment-group #{1 2 3}) state)
        local  (:comments-local result)]
    (t/testing "expanding a cluster records its member ids"
      (t/is (= #{1 2 3} (:expanded local))))
    (t/testing "expanding a cluster clears any open thread or draft"
      (t/is (nil? (:open local)))
      (t/is (nil? (:draft local))))))

(t/deftest collapse-comment-group-preserves-open
  (let [state  {:comments-local {:expanded #{1 2} :open 5}}
        result (ptk/update (dcm/collapse-comment-group) state)
        local  (:comments-local result)]
    (t/testing "collapsing clears the expansion"
      (t/is (nil? (:expanded local))))
    (t/testing "collapsing leaves an open thread untouched"
      (t/is (= 5 (:open local))))))

(t/deftest close-thread-clears-everything
  (let [state  {:comments-local {:open 5 :draft {} :expanded #{1} :options 9}}
        result (ptk/update (dcm/close-thread) state)
        local  (:comments-local result)]
    (t/testing "closing a thread clears open, draft, expanded and options"
      (t/is (nil? (:open local)))
      (t/is (nil? (:draft local)))
      (t/is (nil? (:expanded local)))
      (t/is (nil? (:options local))))))
