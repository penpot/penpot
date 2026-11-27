;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.logic.wasm-modifiers-bool-test
  "Booleans above the shapes that `apply-wasm-modifiers` changes."
  (:require
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.modifiers :as ctm]
   [app.common.types.path :as path]
   [app.main.data.workspace.modifiers :as dwm]
   [app.render-wasm.api :as wasm.api]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]))

(def ^:private calculations
  "Booleans calculated during a test."
  (atom 0))

(def ^:private calc-bool-content path/wasm:calc-bool-content)

(t/use-fixtures :each
  {:before (fn []
             (cthi/reset-idmap!)
             (reset! calculations 0)
             (thw/setup-wasm-mocks!)
             (set! wasm.api/propagate-modifiers
                   (fn [entries _snap-pixel? _snap-ignore-axis]
                     (into [] (map (fn [[id data]] [id (:transform data)])) entries)))
             nil)
   :after  (fn []
             (set! path/wasm:calc-bool-content calc-bool-content)
             (thw/teardown-wasm-mocks!))})

(defn- count-calculations
  "Count the booleans calculated, with the CLJS calculation. The store
  setup installs the renderer's one, so this goes after it."
  []
  (set! path/wasm:calc-bool-content
        (fn [shape objects]
          (swap! calculations inc)
          (path/calc-bool-content shape objects))))

(defn- setup-file
  []
  (-> (cthf/sample-file :file1)
      (cths/add-sample-shape :bool :type :bool :bool-type :union
                             :x 0 :y 0 :width 30 :height 10)
      (cths/add-sample-shape :rect1 :parent-label :bool :x 0 :y 0 :width 10 :height 10)
      (cths/add-sample-shape :rect2 :parent-label :bool :x 20 :y 0 :width 10 :height 10)))

(t/deftest a-boolean-moved-with-its-children-keeps-its-content
  (t/async
    done
    (let [file  (setup-file)
          store (ths/setup-store file)
          _     (count-calculations)
          bool  (cths/get-shape file :bool)]
      (ths/run-store
       store done [(dwm/apply-wasm-modifiers {(:id bool) {:modifiers (ctm/move-modifiers (gpt/point 10 0))}}
                                             {:ignore-snap-pixel true})]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (t/is (= 0 @calculations) "not calculated again")
           (t/is (mth/close? 30 (:x (cths/get-shape file' :rect2))))
           (t/is (mth/close? 10 (-> (cths/get-shape file' :bool) :selrect :x)))))))))

(t/deftest a-boolean-whose-child-changes-alone-is-calculated-once
  (t/async
    done
    (let [file  (setup-file)
          store (ths/setup-store file)
          _     (count-calculations)
          rect2 (cths/get-shape file :rect2)]
      (ths/run-store
       store done [(dwm/apply-wasm-modifiers {(:id rect2) {:modifiers (ctm/change-dimensions-modifiers rect2 :width 30)}}
                                             {:ignore-snap-pixel true})]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (t/is (= 1 @calculations))
           (t/is (mth/close? 50 (-> (cths/get-shape file' :bool) :selrect :x2)))))))))
