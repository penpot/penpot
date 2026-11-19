;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.logic.scale-selected-test
  "Tests for the scale-selected event used by the scale sidebar panel."
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.modifiers :as ctm]
   [app.common.types.text :as txt]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.ui.workspace.sidebar.scale :as scale]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]))

(def ^:private parse-factor
  @#'scale/parse-factor)

(def ^:private scale-input-operation
  @#'scale/scale-input-operation)

(def ^:private apply-preview-modifiers
  @#'scale/apply-preview-modifiers)

(t/use-fixtures :each
  {:before (fn []
             (cthi/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after thw/teardown-wasm-mocks!})

(defn- shape-rect
  [file label]
  (-> (cths/get-shape file label)
      :points
      grc/points->rect))

(defn- assert-rect
  [rect x y width height]
  (t/is (= x (:x rect)))
  (t/is (= y (:y rect)))
  (t/is (= width (:width rect)))
  (t/is (= height (:height rect))))

(t/deftest scale-sidebar-factor-starts-at-one-for-a-new-selection
  (let [selection #{:shape}
        state     (scale/scale-state-for-selection selection)]
    (t/is (= 1 (scale/selection-scale-factor state selection)))))

(t/deftest scale-sidebar-factor-resets-after-deselecting-and-reselecting
  (let [selection  #{:shape}
        scaled     {:selection selection :factor 1.5}
        deselected (scale/scale-state-for-selection #{})
        reselected (scale/scale-state-for-selection selection)]
    (t/is (= 1 (scale/selection-scale-factor deselected #{})))
    (t/is (= 1 (scale/selection-scale-factor reselected selection)))
    (t/is (= 1 (scale/selection-scale-factor scaled #{})))))

(t/deftest scale-sidebar-factor-is-independent-of-canvas-resize
  (let [selection #{:shape}
        state     {:selection selection :factor 1.5}]
    (t/is (= 1.5 (scale/selection-scale-factor state selection)))))

(t/deftest scale-sidebar-factor-updates-after-dimension-input
  (t/is (= 1.5 (scale/calculate-scale-after-dimension-change 1 100 150))))

(t/deftest scale-sidebar-size-uses-live-resize-modifiers
  (let [file       (-> (cthf/sample-file :file1)
                       (ctho/add-rect :rect1 :x 0 :y 0 :width 100 :height 50))
        shape      (cths/get-shape file :rect1)
        resize     (ctm/resize-modifiers (gpt/point 2 2) (gpt/point 0 0))
        modifiers  {(:id shape) {:modifiers resize}}
        wasm-mtx   (ctm/modifiers->transform resize)
        normal     (apply-preview-modifiers [shape] modifiers {} false)
        wasm       (apply-preview-modifiers [shape] {} {(:id shape) wasm-mtx} true)]
    (t/is (= 200 (:width (gsh/shapes->rect normal))))
    (t/is (= 100 (:height (gsh/shapes->rect normal))))
    (t/is (= 200 (:width (gsh/shapes->rect wasm))))
    (t/is (= 100 (:height (gsh/shapes->rect wasm))))))

(t/deftest scale-sidebar-defaults-to-center-anchor-for-a-selection
  (t/is (= [:center :center]
           (scale/selection-anchor nil #{:shape})))
  (t/is (nil? (scale/selection-anchor nil #{}))))

(t/deftest scale-sidebar-uses-the-user-selected-anchor
  (let [selection #{:shape}
        state     {:selection selection :anchor [:left :top]}]
    (t/is (= [:left :top]
             (scale/selection-anchor state selection)))))

(t/deftest scale-sidebar-does-not-keep-the-anchor-between-selections
  (let [previous-selection #{:shape-1}
        current-selection  #{:shape-2}
        state              {:selection previous-selection :anchor [:left :top]}]
    (t/is (= [:center :center]
             (scale/selection-anchor state current-selection)))))

(t/deftest scale-sidebar-parses-custom-scale-factors
  (doseq [[value expected] [["1.5x" 1.5]
                            ["0,5x" 0.5]
                            [" 2 X " 2]]]
    (t/is (= expected (parse-factor value))
          (str "parses " value))))

(t/deftest scale-sidebar-rejects-invalid-custom-scale-factors
  (doseq [value ["0" "-1" "not a number"]]
    (t/is (nil? (parse-factor value))
          (str "rejects " value))))

(t/deftest scale-sidebar-does-not-scale-invalid-or-unchanged-input
  (t/is (nil? (scale-input-operation 1 "0")))
  (t/is (nil? (scale-input-operation 1 "not a number")))
  (t/is (nil? (scale-input-operation 1 "1x"))))

(t/deftest scale-sidebar-builds-an-operation-for-a-new-scale-factor
  (t/is (= {:multiplier 1.5 :factor 1.5}
           (scale-input-operation 1 "1.5x"))))

(t/deftest scale-selected-keeps-the-center-anchor-in-place
  (t/async
    done
    (let [file   (-> (cthf/sample-file :file1)
                     (ctho/add-rect :rect1
                                    :x 100 :y 100 :width 100 :height 50))
          rect   (cths/get-shape file :rect1)
          store  (ths/setup-store file)]

      (ths/run-store
       store done
       [(dws/select-shape (:id rect))
        (dwt/scale-selected 2 [:center :center])]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (assert-rect (shape-rect file' :rect1) 50 75 200 100)))))))

(t/deftest scale-selected-keeps-the-top-left-anchor-in-place
  (t/async
    done
    (let [file   (-> (cthf/sample-file :file1)
                     (ctho/add-rect :rect1
                                    :x 100 :y 100 :width 100 :height 50))
          rect   (cths/get-shape file :rect1)
          store  (ths/setup-store file)]

      (ths/run-store
       store done
       [(dws/select-shape (:id rect))
        (dwt/scale-selected 3 [:left :top])]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (assert-rect (shape-rect file' :rect1) 100 100 300 150)))))))

(t/deftest scale-selected-scales-strokes-and-text
  (t/async
    done
    (let [file   (-> (cthf/sample-file :file1)
                     (ctho/add-rect :rect1
                                    :x 0 :y 0 :width 100 :height 100
                                    :strokes [{:stroke-color "#000000"
                                               :stroke-opacity 1
                                               :stroke-width 2
                                               :stroke-alignment :inner}])
                     (ctho/add-text :text1 "Hello" :text-params {:x 200 :y 0}))
          rect   (cths/get-shape file :rect1)
          text   (cths/get-shape file :text1)
          store  (ths/setup-store file)]

      (ths/run-store
       store done
       [(dws/select-shapes (d/ordered-set (:id rect) (:id text)))
        (dwt/scale-selected 2 [:left :top])]
       (fn [state]
         (let [file'     (ths/get-file-from-state state)
               rect'     (cths/get-shape file' :rect1)
               text'     (cths/get-shape file' :text1)
               text-node (first (txt/node-seq txt/is-text-node? (:content text')))]

           (assert-rect (shape-rect file' :rect1) 0 0 200 200)
           (t/is (= 4 (get-in rect' [:strokes 0 :stroke-width]))
                 "stroke width scales with the selection")
           (t/is (= "28" (:font-size text-node))
                 "font size scales with the selection")))))))

(t/deftest scale-selected-ignores-constraints-in-the-legacy-renderer
  (t/async
    done
    (let [file  (ctho/add-frame-with-child
                 (cthf/sample-file :file1) :frame1 :child1
                 :frame-params {:x 0 :y 0 :width 200 :height 200}
                 :child-params {:x 50 :y 50 :width 50 :height 50})
          frame (cths/get-shape file :frame1)
          store (ths/setup-store file {:renderer :svg})]

      (ths/run-store
       store done
       [(dws/select-shape (:id frame))
        (dwt/scale-selected 2 [:left :top])]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (assert-rect (shape-rect file' :frame1) 0 0 400 400)
           (assert-rect (shape-rect file' :child1) 100 100 100 100)))))))
