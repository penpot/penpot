;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.component-geometry-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.component-geometry :as clcg]
   [app.common.math :as mth]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; ---- Helpers

(def ^:private parent-id (uuid/next))

(defn- make-root
  [x y width height]
  (cts/setup-shape {:id parent-id :type :frame :x x :y y :width width :height height}))

(defn- make-child
  [x y width height & {:as params}]
  (cts/setup-shape (merge {:type :rect :x x :y y :width width :height height
                           :parent-id parent-id :frame-id parent-id
                           :constraints-h :left :constraints-v :top}
                          params)))

(defn- make-path
  [x y width height]
  (cts/setup-shape {:type :path
                    :content (path/content [{:command :move-to :params {:x x :y y}}
                                            {:command :line-to :params {:x (+ x width) :y y}}
                                            {:command :line-to :params {:x (+ x width) :y (+ y height)}}
                                            {:command :close-path}])}))

(defn- rotate
  [shape angle]
  (gsh/transform-shape shape (ctm/rotation-modifiers shape (gsh/shape->center shape) angle)))

(defn- touch-geometry
  [shape]
  (assoc shape :touched #{:geometry-group}))

(defn- top-left
  [shape]
  (gpt/point (:selrect shape)))

;; ---- reposition-shape

(t/deftest reposition-shape-moves-by-roots-offset
  (let [shape (clcg/reposition-shape (make-child 10 20 30 30)
                                     (make-root 0 0 100 100)
                                     (make-root 200 100 100 100))]
    (t/is (gpt/close? (gpt/point 210 120) (top-left shape)))))

(t/deftest reposition-shape-ignores-rotation-of-untouched-root
  (let [origin-root (make-root 0 0 100 100)
        dest-root   (rotate (make-root 0 0 100 100) 90)
        shape       (make-child 0 0 20 20)]
    (t/is (= (gsh/translate-relative shape origin-root dest-root)
             (clcg/reposition-shape shape origin-root dest-root)))))

(t/deftest reposition-shape-follows-rotation-of-touched-root
  (let [shape (clcg/reposition-shape (make-child 0 0 20 20)
                                     (make-root 0 0 100 100)
                                     (touch-geometry (rotate (make-root 0 0 100 100) 90)))]
    (t/is (mth/close? 90 (:rotation shape)))
    (t/is (gpt/close? (gpt/point 90 10) (gsh/shape->center shape)))))

;; ---- synced-geometry

(t/deftest synced-geometry-takes-main-geometry-when-not-kept
  (let [main-geometry (make-child 0 0 50 50)]
    (t/is (= [main-geometry nil]
             (clcg/synced-geometry (make-child 10 10 20 20)
                                   main-geometry
                                   false
                                   [(make-root 0 0 100 100) (make-root 50 0 100 100)])))))

(t/deftest synced-geometry-keeps-shape-without-parent-change
  (let [shape-inst (make-child 10 10 20 20)]
    (t/is (= [shape-inst nil]
             (clcg/synced-geometry shape-inst (make-child 0 0 50 50) true nil)))))

(t/deftest synced-geometry-follows-parent-move
  (let [[shape modifiers] (clcg/synced-geometry (make-child 10 10 20 20)
                                                (make-child 0 0 50 50)
                                                true
                                                [(make-root 0 0 100 100) (make-root 30 5 100 100)])]
    (t/is (ctm/only-move? modifiers))
    (t/is (gpt/close? (gpt/point 40 15) (top-left shape)))))

(t/deftest synced-geometry-follows-parent-resize-through-constraints
  (let [[shape _] (clcg/synced-geometry (make-child 80 10 20 20 :constraints-h :right)
                                        (make-child 0 0 50 50)
                                        true
                                        [(make-root 0 0 100 100) (make-root 0 0 200 100)])]
    (t/is (mth/close? 180 (-> shape :selrect :x)))
    (t/is (mth/close? 20 (-> shape :selrect :width)))))

;; ---- sync-swap-position

(t/deftest sync-swap-position-is-main-place-plus-parent-gap
  ;; The slot occupant is at (10, 10) in the main, the copy root is 100 to the
  ;; right, and the sync leaves the parent 5 further right than the main puts it
  (let [position (clcg/sync-swap-position (make-child 10 10 20 20)
                                          (make-root 0 0 100 100)
                                          (make-root 100 0 100 100)
                                          (make-root 105 0 100 100)
                                          (make-root 100 0 100 100))]
    (t/is (gpt/close? (gpt/point 115 10) position))))

;; ---- switch-previous-geometry

(t/deftest switch-previous-geometry-resizes-with-parent-and-repositions
  (let [shape (clcg/switch-previous-geometry (make-child 50 10 20 20 :constraints-h :scale)
                                             (make-root 0 0 100 100)
                                             (make-root 0 0 200 100)
                                             (make-root 0 0 100 100)
                                             (make-root 300 0 100 100))]
    (t/is (mth/close? 40 (-> shape :selrect :width)))
    (t/is (gpt/close? (gpt/point 350 10) (top-left shape)))))

;; ---- equal-geometry?

(t/deftest equal-geometry-compares-selrect-size-only
  (let [shape (make-child 0 0 20 30)]
    (t/is (clcg/equal-geometry? shape (make-child 50 60 20 30) :selrect))
    (t/is (not (clcg/equal-geometry? shape (make-child 0 0 21 30) :selrect)))))

(t/deftest equal-geometry-compares-points-ignoring-position
  (let [shape (make-child 0 0 20 30)]
    (t/is (clcg/equal-geometry? shape (make-child 50 60 20 30) :points))
    (t/is (not (clcg/equal-geometry? shape (rotate (make-child 0 0 20 30) 45) :points)))))

(t/deftest equal-geometry-compares-path-content-size
  (let [shape (make-path 0 0 20 30)]
    (t/is (clcg/equal-geometry? shape (make-path 50 60 20 30) :content))
    (t/is (not (clcg/equal-geometry? shape (make-path 0 0 40 30) :content)))
    (t/is (not (clcg/equal-geometry? (make-child 0 0 20 30) (make-child 0 0 20 30) :content)))))

(t/deftest equal-geometry-is-false-for-other-attrs
  (let [shape (make-child 0 0 20 30)]
    (t/is (not (clcg/equal-geometry? shape shape :fills)))))

;; ---- switch geometry values

(t/deftest switch-geom-change-value-carries-size-at-current-position
  (let [prev    (make-child 0 0 50 60)
        current (make-child 10 20 30 40)
        selrect (clcg/switch-geom-change-value prev current :selrect)
        points  (clcg/switch-geom-change-value prev current :points)]
    (t/is (= [10 20 50 60] ((juxt :x :y :width :height) selrect)))
    (t/is (gpt/close? (gpt/point 10 20) (first points)))
    (t/is (gpt/close? (gpt/point 60 80) (nth points 2)))))

(t/deftest switch-fixed-layout-geom-change-value-keeps-fixed-axis
  (let [prev    (make-child 0 0 50 60 :layout-item-h-sizing :fix :layout-item-v-sizing :auto)
        current (make-child 10 20 30 40)
        origin  (make-child 0 0 30 40)]
    ;; Fixed width takes the new variant's; the height override is kept
    ;; because the origin and the new variant have the same height
    (t/is (= 30 (clcg/switch-fixed-layout-geom-change-value prev current origin :width)))
    (t/is (= 60 (clcg/switch-fixed-layout-geom-change-value prev current origin :height)))
    (t/is (= [10 20 30 60]
             ((juxt :x :y :width :height)
              (clcg/switch-fixed-layout-geom-change-value prev current origin :selrect))))))

(t/deftest switch-fixed-layout-geom-change-value-drops-override-on-size-change
  (let [prev    (make-child 0 0 50 60 :layout-item-h-sizing :fix :layout-item-v-sizing :auto)
        current (make-child 10 20 30 40)
        origin  (make-child 0 0 30 45)]
    (t/is (= 40 (clcg/switch-fixed-layout-geom-change-value prev current origin :height)))))

(t/deftest switch-path-change-value-scales-like-the-override
  ;; The user doubled the path in the copy, so the new variant's path doubles
  ;; too and keeps the center of the previous one
  (let [prev    (make-path 100 100 40 60)
        current (make-path 0 0 20 30)
        ref     (make-path 0 0 20 30)
        selrect (clcg/switch-path-change-value prev current ref :selrect)]
    (t/is (mth/close? 40 (:width selrect)))
    (t/is (mth/close? 60 (:height selrect)))
    (t/is (gpt/close? (gpt/point 100 100) (gpt/point selrect)))))

;; ---- Changes

(defn- setup-follow-file
  []
  (-> (thf/sample-file :file1)
      (ths/add-sample-shape :parent :type :frame :x 0 :y 0 :width 100 :height 100)
      (ths/add-sample-shape :child :type :frame :parent-label :parent
                            :x 10 :y 10 :width 40 :height 40)
      (ths/add-sample-shape :grandchild :parent-label :child
                            :x 20 :y 20 :width 10 :height 10)))

(t/deftest generate-follow-parent-moves-subtree-to-target
  (let [file    (setup-follow-file)
        page    (thf/current-page file)
        parent  (ths/get-shape file :parent)
        changes (clcg/generate-follow-parent (pcb/empty-changes nil (:id page))
                                             (ctn/make-container page :page)
                                             (ths/get-shape file :child)
                                             parent
                                             (gsh/move parent (gpt/point 50 0))
                                             (gpt/point 0 0))
        file'   (thf/apply-changes file changes)]
    (t/is (gpt/close? (gpt/point 0 0) (top-left (ths/get-shape file' :child))))
    (t/is (gpt/close? (gpt/point 10 10) (top-left (ths/get-shape file' :grandchild))))
    (t/is (nil? (:touched (ths/get-shape file' :child))))))

(t/deftest generate-follow-parent-without-change-adds-nothing
  (let [file    (setup-follow-file)
        page    (thf/current-page file)
        parent  (ths/get-shape file :parent)
        changes (clcg/generate-follow-parent (pcb/empty-changes nil (:id page))
                                             (ctn/make-container page :page)
                                             (ths/get-shape file :child)
                                             parent
                                             parent
                                             nil)]
    (t/is (empty? (:redo-changes changes)))))

(t/deftest generate-transform-untouched-applies-modifiers
  (let [file    (setup-follow-file)
        page    (thf/current-page file)
        changes (clcg/generate-transform-untouched (pcb/empty-changes nil (:id page))
                                                   (ctn/make-container page :page)
                                                   (ths/get-shape file :child)
                                                   (ctm/move-modifiers (gpt/point 5 5)))
        file'   (thf/apply-changes file changes)]
    (t/is (gpt/close? (gpt/point 15 15) (top-left (ths/get-shape file' :child))))))

(t/deftest generate-transform-untouched-with-empty-modifiers-adds-nothing
  (let [file    (setup-follow-file)
        page    (thf/current-page file)
        changes (clcg/generate-transform-untouched (pcb/empty-changes nil (:id page))
                                                   (ctn/make-container page :page)
                                                   (ths/get-shape file :child)
                                                   (ctm/empty))]
    (t/is (empty? (:redo-changes changes)))))
