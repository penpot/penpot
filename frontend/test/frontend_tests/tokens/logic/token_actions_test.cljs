;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.logic.token-actions-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.text :as txt]
   [app.common.types.token :as ctt]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.util.storage :as storage]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [frontend-tests.tokens.helpers.state :as tohs]
   [frontend-tests.tokens.helpers.tokens :as toht]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(defn setup-file []
  (cthf/sample-file :file-1 :page-label :page-1))

(def border-radius-token
  {:name "borderRadius.sm"
   :value "12"
   :type :border-radius})

(def reference-border-radius-token
  {:name "borderRadius.md"
   :value "{borderRadius.sm} * 2"
   :type :border-radius})

(defn setup-file-with-tokens
  [& {:keys [rect-1 rect-2 rect-3]}]
  (-> (ctht/sample-file-with-tokens
       :lib-fn #(-> %
                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set-a)
                                                       :name "Set A"))
                    (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :theme-a)
                                                           :name "Theme A"
                                                           :sets #{"Set A"}))
                    (ctob/add-token (cthi/id :set-a)
                                    (ctob/make-token border-radius-token))
                    (ctob/add-token (cthi/id :set-a)
                                    (ctob/make-token reference-border-radius-token)))
       :status-fn #(ctos/set-tokens-status % #{(cthi/id :theme-a)} #{(cthi/id :set-a)}))
      (ctho/add-rect :rect-1 rect-1)
      (ctho/add-rect :rect-2 rect-2)
      (ctho/add-rect :rect-3 rect-3)
      (ctho/add-text :text-1 "Hello World!")))

(def debounce-text-stop
  (tohs/stop-on ::dwwt/resize-wasm-text-debounce-commit))

;; Regression coverage for issue #10070 (set-creation activation).
;;
;; Newly created token sets are inactive by default — only active sets
;; affect shapes and reference resolution. The Plugin API's
;; `addSet({ name, active })` creates an already-active set by emitting
;; `create-token-set` followed by `set-enabled-token-set`. These tests
;; pin that the create-then-enable sequence the proxy relies on actually
;; ends with the set active (enabling only adds the set name to the hidden
;; theme, so it does not depend on the create event having propagated).

(defn setup-file-with-empty-lib []
  (-> (setup-file)
      (assoc-in [:data :tokens-lib] (ctob/make-tokens-lib))))

(t/deftest test-create-token-set-inactive-by-default
  (t/testing "a newly created set is not active unless explicitly enabled"
    (t/async
      done
      (let [file   (setup-file-with-empty-lib)
            store  (ths/setup-store file)
            set    (ctob/make-token-set :id (cthi/new-id! :set1) :name "primitives")
            events [(dwtl/create-token-set set)]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file'  (ths/get-file-from-state new-state)
                 lib    (ctht/get-tokens-lib file')
                 status (ctht/get-tokens-status file')]
             (t/is (some? (ctob/get-set lib (ctob/get-id set))))
             (t/is (false? (ctos/set-active? status (cthi/id :set1)))))))))))

(t/deftest test-create-then-enable-token-set
  (t/testing "create followed by set-enabled (as the plugin addSet does) yields an active set"
    (t/async
      done
      (let [file   (setup-file-with-empty-lib)
            store  (ths/setup-store file)
            set    (ctob/make-token-set :id (cthi/new-id! :set1) :name "primitives")
            events [(dwtl/create-token-set set)
                    (dwtl/set-enabled-token-set (cthi/id :set1) true)]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file'  (ths/get-file-from-state new-state)
                 lib    (ctht/get-tokens-lib file')
                 status (ctht/get-tokens-status file')]
             (t/is (some? (ctob/get-set lib (ctob/get-id set))))
             (t/is (true? (ctos/set-active? status (cthi/id :set1)))))))))))

(t/deftest test-apply-token
  (t/testing "applies token to shape and updates shape   attributes to resolved value"
    (t/async
      done
      (let [file   (setup-file-with-tokens)
            store  (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(dwta/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:r1 :r2 :r3 :r4}
                                       :token (toht/get-token file "borderRadius.md")
                                       :on-update-shape dwta/update-shape-radius})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file'   (ths/get-file-from-state new-state)
                 token   (toht/get-token file' "borderRadius.md")
                 rect-1' (cths/get-shape file' :rect-1)]

             (t/testing "shape `:applied-tokens` got updated"
               (t/is (some? (:applied-tokens rect-1')))
               (t/is (= (:r1 (:applied-tokens rect-1')) (:name token))))

             (t/testing "shape radius got update to the resolved token value."
               (t/is (= (:r1 rect-1') 24))))))))))

(t/deftest test-apply-multiple-tokens
  (t/testing "applying a token twice with the same attributes will override the previously applied tokens values"
    (t/async
      done
      (let [file (setup-file-with-tokens)
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(dwta/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:r1 :r2 :r3 :r4}
                                       :token (toht/get-token file "borderRadius.sm")
                                       :on-update-shape dwta/update-shape-radius})
                    (dwta/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:r1 :r2 :r3 :r4}
                                       :token (toht/get-token file "borderRadius.md")
                                       :on-update-shape dwta/update-shape-radius})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token (toht/get-token file' "borderRadius.md")
                 rect-1' (cths/get-shape file' :rect-1)]
             (t/testing "shape `:applied-tokens` got updated"
               (t/is (some? (:applied-tokens rect-1')))
               (t/is (= (:r1 (:applied-tokens rect-1')) (:name token))))
             (t/testing "shape radius got update to the resolved token value."
               (t/is (= (:r1 rect-1') 24))))))))))

(t/deftest test-apply-token-overwrite
  (t/testing "removes old token attributes and applies only single attribute"
    (t/async
      done
      (let [file (setup-file-with-tokens)
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [;; Apply "borderRadius.sm" to all border radius attributes
                    (dwta/apply-token {:attributes #{:r1 :r2 :r3 :r4}
                                       :token (toht/get-token file "borderRadius.sm")
                                       :shape-ids [(:id rect-1)]
                                       :on-update-shape dwta/update-shape-radius})
                    ;; Apply single `:r1` attribute to same shape
                    ;; while removing other attributes from the border-radius set
                    ;; but keep `:r4` for testing purposes
                    (dwta/apply-token {:attributes #{:r1 :r2 :r3}
                                       :token (toht/get-token file "borderRadius.md")
                                       :shape-ids [(:id rect-1)]
                                       :on-update-shape dwta/update-shape-radius})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-sm (toht/get-token file' "borderRadius.sm")
                 token-md (toht/get-token file' "borderRadius.md")
                 rect-1' (cths/get-shape file' :rect-1)]
             (t/testing "r1 got applied with borderRadius.md"
               (t/is (= (:r1 (:applied-tokens rect-1')) (:name token-md))))
             (t/testing "while :r4 was kept with borderRadius.sm"
               (t/is (= (:r4 (:applied-tokens rect-1')) (:name token-sm)))))))))))

(t/deftest test-apply-border-radius
  (t/testing "applies border-radius to all and individual corners"
    (t/async
      done
      (let [file (setup-file-with-tokens {:rect-1 {:r1 100 :r2 100}
                                          :rect-2 {:r3 100 :r4 100}})
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            rect-2 (cths/get-shape file :rect-2)
            events [(dwta/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:r3 :r4}
                                       :token (toht/get-token file "borderRadius.sm")
                                       :on-update-shape dwta/update-shape-radius-for-corners})
                    (dwta/apply-token {:shape-ids [(:id rect-2)]
                                       :attributes #{:r1 :r2 :r3 :r4}
                                       :token (toht/get-token file "borderRadius.sm")
                                       :on-update-shape dwta/update-shape-radius})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 rect-1' (cths/get-shape file' :rect-1)
                 rect-2' (cths/get-shape file' :rect-2)]
             (t/testing "individual corners"
               (t/is (nil? (:r1 (:applied-tokens rect-1'))))
               (t/is (nil? (:r2 (:applied-tokens rect-1'))))
               (t/is (= "borderRadius.sm" (:r3 (:applied-tokens rect-1'))))
               (t/is (= "borderRadius.sm" (:r4 (:applied-tokens rect-1'))))
               (t/is (= 100 (:r1 rect-1')))
               (t/is (= 100 (:r2 rect-1')))
               (t/is (= 12 (:r3 rect-1')))
               (t/is (= 12 (:r4 rect-1'))))

             (t/testing "all corners"
               (t/is (= "borderRadius.sm" (:r1 (:applied-tokens rect-2'))))
               (t/is (= "borderRadius.sm" (:r2 (:applied-tokens rect-2'))))
               (t/is (= "borderRadius.sm" (:r3 (:applied-tokens rect-2'))))
               (t/is (= "borderRadius.sm" (:r4 (:applied-tokens rect-2'))))
               (t/is (= 12 (:r1 rect-2')))
               (t/is (= 12 (:r2 rect-2')))
               (t/is (= 12 (:r3 rect-2')))
               (t/is (= 12 (:r4 rect-2')))))))))))

(t/deftest test-apply-color
  (t/testing "applies color token and updates the shape fill and stroke-color"
    (t/async
      done
      (let [color-token {:name "color.primary"
                         :value "red"
                         :type :color}
            color-alpha-token {:name "color.secondary"
                               :value "rgba(255,0,0,0.5)"
                               :type :color}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(-> %
                                     (ctob/add-token (cthi/id :set-a)
                                                     (ctob/make-token color-token))
                                     (ctob/add-token (cthi/id :set-a)
                                                     (ctob/make-token color-alpha-token)))))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            rect-2 (cths/get-shape file :rect-2)
            events [(dwta/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:fill}
                                       :token (toht/get-token file "color.primary")
                                       :on-update-shape dwta/update-fill})
                    (dwta/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:stroke-color}
                                       :token (toht/get-token file "color.primary")
                                       :on-update-shape dwta/update-stroke-color})
                    (dwta/apply-token {:shape-ids [(:id rect-2)]
                                       :attributes #{:fill}
                                       :token (toht/get-token file "color.secondary")
                                       :on-update-shape dwta/update-fill})
                    (dwta/apply-token {:shape-ids [(:id rect-2)]
                                       :attributes #{:stroke-color}
                                       :token (toht/get-token file "color.secondary")
                                       :on-update-shape dwta/update-stroke-color})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 primary-target (toht/get-token file' "color.primary")
                 secondary-target (toht/get-token file' "color.secondary")
                 rect-1' (cths/get-shape file' :rect-1)
                 rect-2' (cths/get-shape file' :rect-2)]

             (t/testing "regular color"
               (t/is (some? (:applied-tokens rect-1')))
               (t/is (= (:fill (:applied-tokens rect-1')) (:name primary-target)))
               (t/is (= (-> rect-1' :fills (nth 0) :fill-color) "#ff0000"))
               (t/is (= (:stroke-color (:applied-tokens rect-1')) (:name primary-target)))
               (t/is (= (get-in rect-1' [:strokes 0 :stroke-color]) "#ff0000")))

             (t/testing "color with alpha channel"
               (t/is (some? (:applied-tokens rect-2')))

               (t/is (= (:fill (:applied-tokens rect-2')) (:name secondary-target)))
               (let [fills (get rect-2' :fills)]
                 (t/is (= (-> fills (nth 0) :fill-color) "#ff0000"))
                 (t/is (= (-> fills (nth 0) :fill-opacity) 0.5)))

               (t/is (= (:stroke-color (:applied-tokens rect-2')) (:name secondary-target)))
               (t/is (= (get-in rect-2' [:strokes 0 :stroke-color]) "#ff0000"))
               (t/is (= (get-in rect-2' [:strokes 0 :stroke-opacity]) 0.5))))))))))

(t/deftest test-apply-dimensions
  (t/testing "applies dimensions token and updates the shapes width and height"
    (t/async
      done
      (let [dimensions-token {:name "dimensions.sm"
                              :value "100"
                              :type :dimensions}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token dimensions-token))))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(dwta/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:width :height}
                                       :token (toht/get-token file "dimensions.sm")
                                       :on-update-shape dwta/apply-dimensions-token})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "dimensions.sm")
                 rect-1' (cths/get-shape file' :rect-1)]
             (t/testing "shape `:applied-tokens` got updated"
               (t/is (some? (:applied-tokens rect-1')))
               (t/is (= (:width (:applied-tokens rect-1')) (:name token-target')))
               (t/is (= (:height (:applied-tokens rect-1')) (:name token-target'))))
             (t/testing "shapes width and height got updated"
               (t/is (= (:width rect-1') 100))
               (t/is (= (:height rect-1') 100))))))))))

(t/deftest test-apply-padding
  (t/testing "applies padding token to shapes with layout"
    (t/async
      done
      (let [spacing-token {:name "padding.sm"
                           :value "100"
                           :type :spacing}
            file (-> (setup-file-with-tokens)
                     (ctho/add-frame :frame-1)
                     (ctho/add-frame :frame-2 {:layout :grid})
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token spacing-token))))
            store (ths/setup-store file)
            frame-1 (cths/get-shape file :frame-1)
            frame-2 (cths/get-shape file :frame-2)
            events [(dwta/apply-token {:shape-ids [(:id frame-1) (:id frame-2)]
                                       :attributes #{:p1 :p2 :p3 :p4}
                                       :token (toht/get-token file "padding.sm")
                                       :on-update-shape dwta/update-layout-padding})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "padding.sm")
                 frame-1' (cths/get-shape file' :frame-1)
                 frame-2' (cths/get-shape file' :frame-2)]
             (t/testing "shape `:applied-tokens` got updated"
               (t/is (= (:p1 (:applied-tokens frame-1')) nil))
               (t/is (= (:p2 (:applied-tokens frame-1')) nil))
               (t/is (= (:p3 (:applied-tokens frame-1')) nil))
               (t/is (= (:p4 (:applied-tokens frame-1')) nil))

               (t/is (= (:p1 (:applied-tokens frame-2')) (:name token-target')))
               (t/is (= (:p2 (:applied-tokens frame-2')) (:name token-target')))
               (t/is (= (:p3 (:applied-tokens frame-2')) (:name token-target')))
               (t/is (= (:p4 (:applied-tokens frame-2')) (:name token-target'))))
             (t/testing "shapes padding got updated"
               (t/is (= (:layout-padding frame-2') {:p1 100 :p2 100 :p3 100 :p4 100})))
             (t/testing "shapes without layout get ignored"
               (t/is (nil? (:layout-padding frame-1')))))))))))

(t/deftest test-apply-negative-spacing-clamps-padding-and-gap
  (t/testing "negative spacing tokens write zero padding and gap"
    (t/async
      done
      (let [spacing-token {:name "spacing.negative"
                           :value "-8"
                           :type :spacing}
            file (-> (setup-file-with-tokens)
                     (ctho/add-frame :frame-1 {:layout :flex})
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token spacing-token))))
            store (ths/setup-store file)
            frame-1 (cths/get-shape file :frame-1)
            token (toht/get-token file "spacing.negative")
            events [(dwta/apply-token {:shape-ids [(:id frame-1)]
                                       :attributes #{:p1 :p2 :p3 :p4}
                                       :token token
                                       :on-update-shape dwta/update-layout-padding})
                    (dwta/apply-token {:shape-ids [(:id frame-1)]
                                       :attributes #{:row-gap :column-gap}
                                       :token token
                                       :on-update-shape dwta/update-layout-gap})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file'    (ths/get-file-from-state new-state)
                 frame-1' (cths/get-shape file' :frame-1)]
             (t/is (= (:layout-padding frame-1') {:p1 0 :p2 0 :p3 0 :p4 0}))
             (t/is (= (:layout-gap frame-1') {:row-gap 0 :column-gap 0})))))))))

(t/deftest test-apply-sizing
  (t/testing "applies sizing token and updates the shapes width and height"
    (t/async
      done
      (let [sizing-token {:name "sizing.sm"
                          :value "100"
                          :type :sizing}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token sizing-token))))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(dwta/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:width :height}
                                       :token (toht/get-token file "sizing.sm")
                                       :on-update-shape dwta/apply-dimensions-token})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "sizing.sm")
                 rect-1' (cths/get-shape file' :rect-1)]
             (t/testing "shape `:applied-tokens` got updated"
               (t/is (some? (:applied-tokens rect-1')))
               (t/is (= (:width (:applied-tokens rect-1')) (:name token-target')))
               (t/is (= (:height (:applied-tokens rect-1')) (:name token-target'))))
             (t/testing "shapes width and height got updated"
               (t/is (= (:width rect-1') 100))
               (t/is (= (:height rect-1') 100))))))))))

(t/deftest test-apply-opacity
  (t/testing "applies opacity token and updates the shapes opacity"
    (t/async
      done
      (let [opacity-float {:name "opacity.float"
                           :value "0.3"
                           :type :opacity}
            opacity-percent {:name "opacity.percent"
                             :value "40%"
                             :type :opacity}
            opacity-invalid {:name "opacity.invalid"
                             :value "100"
                             :type :opacity}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(-> %
                                     (ctob/add-token (cthi/id :set-a)
                                                     (ctob/make-token opacity-float))
                                     (ctob/add-token (cthi/id :set-a)
                                                     (ctob/make-token opacity-percent))
                                     (ctob/add-token (cthi/id :set-a)
                                                     (ctob/make-token opacity-invalid)))))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            rect-2 (cths/get-shape file :rect-2)
            rect-3 (cths/get-shape file :rect-3)
            events [(dwta/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:opacity}
                                       :token (toht/get-token file "opacity.float")
                                       :on-update-shape dwta/update-opacity})
                    (dwta/apply-token {:shape-ids [(:id rect-2)]
                                       :attributes #{:opacity}
                                       :token (toht/get-token file "opacity.percent")
                                       :on-update-shape dwta/update-opacity})
                    (dwta/apply-token {:shape-ids [(:id rect-3)]
                                       :attributes #{:opacity}
                                       :token (toht/get-token file "opacity.invalid")
                                       :on-update-shape dwta/update-opacity})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 rect-1' (cths/get-shape file' :rect-1)
                 rect-2' (cths/get-shape file' :rect-2)
                 rect-3' (cths/get-shape file' :rect-3)
                 token-opacity-float (toht/get-token file' "opacity.float")
                 token-opacity-percent (toht/get-token file' "opacity.percent")
                 token-opacity-invalid (toht/get-token file' "opacity.invalid")]
             (t/testing "float value got translated to float and applied to opacity"
               (t/is (= (:opacity (:applied-tokens rect-1')) (:name token-opacity-float)))
               (t/is (= (:opacity rect-1') 0.3)))
             (t/testing "percentage value got translated to float and applied to opacity"
               (t/is (= (:opacity (:applied-tokens rect-2')) (:name token-opacity-percent)))
               (t/is (= (:opacity rect-2') 0.4)))
             (t/testing "invalid opacity value got applied but did not change shape"
               (t/is (= (:opacity (:applied-tokens rect-3')) (:name token-opacity-invalid)))
               (t/is (nil? (:opacity rect-3')))))))))))

(t/deftest test-apply-rotation
  (t/testing "applies rotation token and updates the shapes rotation"
    (t/async
      done
      (let [rotation-token {:name "rotation.medium"
                            :value "120"
                            :type :rotation}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token rotation-token))))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(dwta/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:rotation}
                                       :token (toht/get-token file "rotation.medium")
                                       :on-update-shape dwta/update-rotation})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "rotation.medium")
                 rect-1' (cths/get-shape file' :rect-1)]
             (t/is (some? (:applied-tokens rect-1')))
             (t/is (= (:rotation (:applied-tokens rect-1')) (:name token-target')))
             (t/is (= (:rotation rect-1') 120)))))))))

(t/deftest test-apply-stroke-width
  (t/testing "applies stroke-width token and updates the shapes with stroke"
    (t/async
      done
      (let [stroke-width-token {:name "stroke-width.sm"
                                :value "10"
                                :type :stroke-width}
            file (-> (setup-file-with-tokens {:rect-1 {:strokes [{:stroke-alignment :inner,
                                                                  :stroke-style :solid,
                                                                  :stroke-color "#000000",
                                                                  :stroke-opacity 1,
                                                                  :stroke-width 5}]}})
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token stroke-width-token))))
            store (ths/setup-store file)
            rect-with-stroke (cths/get-shape file :rect-1)
            rect-without-stroke (cths/get-shape file :rect-2)
            events [(dwta/apply-token {:shape-ids [(:id rect-with-stroke) (:id rect-without-stroke)]
                                       :attributes ctt/per-side-stroke-width-keys
                                       :token (toht/get-token file "stroke-width.sm")
                                       :on-update-shape dwta/update-stroke-width-side})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "stroke-width.sm")
                 rect-with-stroke' (cths/get-shape file' :rect-1)
                 rect-without-stroke' (cths/get-shape file' :rect-2)]
             (t/testing "token got applied to rect with stroke and shape stroke got updated"
               (t/is (= (:stroke-width-top (:applied-tokens rect-with-stroke')) (:name token-target')))
               (t/is (= (:stroke-width-right (:applied-tokens rect-with-stroke')) (:name token-target')))
               (t/is (= (:stroke-width-bottom (:applied-tokens rect-with-stroke')) (:name token-target')))
               (t/is (= (:stroke-width-left (:applied-tokens rect-with-stroke')) (:name token-target')))
               (t/is (= (get-in rect-with-stroke' [:strokes 0 :stroke-width]) 10)))
             (t/testing "token got applied to rect without stroke and shape stroke got updated"
               (t/is (= (:stroke-width-top (:applied-tokens rect-without-stroke')) (:name token-target')))
               (t/is (= (:stroke-width-right (:applied-tokens rect-without-stroke')) (:name token-target')))
               (t/is (= (:stroke-width-bottom (:applied-tokens rect-without-stroke')) (:name token-target')))
               (t/is (= (:stroke-width-left (:applied-tokens rect-without-stroke')) (:name token-target')))
               (t/is (= (get-in rect-without-stroke' [:strokes 0 :stroke-width]) 10))))))))))

(t/deftest test-apply-stroke-width-per-side-new-shape
  (t/testing "applying a stroke-width token to one side of a shape without strokes zeroes the other sides"
    (t/async
      done
      (let [stroke-width-token {:name "stroke-width.sm"
                                :value "8"
                                :type :stroke-width}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token stroke-width-token))))
            store (ths/setup-store file)
            rect (cths/get-shape file :rect-1)
            events [(dwta/apply-token {:shape-ids [(:id rect)]
                                       :attributes #{:stroke-width-top}
                                       :token (toht/get-token file "stroke-width.sm")
                                       :on-update-shape dwta/update-stroke-width-side})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 rect' (cths/get-shape file' :rect-1)
                 stroke' (get-in rect' [:strokes 0])]
             (t/testing "the applied side gets the token value"
               (t/is (= (:stroke-width-top stroke') 8)))
             (t/testing "the remaining sides go to zero"
               (t/is (= (:stroke-width-right stroke') 0))
               (t/is (= (:stroke-width-bottom stroke') 0))
               (t/is (= (:stroke-width-left stroke') 0)))
             (t/testing "the global width mirrors the top side"
               (t/is (= (:stroke-width stroke') 8)))
             (t/testing "only the applied side records the token"
               (t/is (= (:stroke-width-top (:applied-tokens rect')) "stroke-width.sm"))
               (t/is (nil? (:stroke-width-right (:applied-tokens rect'))))
               (t/is (nil? (:stroke-width-bottom (:applied-tokens rect'))))
               (t/is (nil? (:stroke-width-left (:applied-tokens rect'))))))))))))

(t/deftest test-apply-stroke-width-per-side-keeps-other-sides
  (t/testing "applying a stroke-width token to one side preserves the other sides"
    (t/async
      done
      (let [stroke-width-token {:name "stroke-width.sm"
                                :value "10"
                                :type :stroke-width}
            file (-> (setup-file-with-tokens {:rect-1 {:strokes [{:stroke-alignment :inner
                                                                  :stroke-style :solid
                                                                  :stroke-color "#000000"
                                                                  :stroke-opacity 1
                                                                  :stroke-width 5}]}})
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token stroke-width-token))))
            store (ths/setup-store file)
            rect (cths/get-shape file :rect-1)
            events [(dwta/apply-token {:shape-ids [(:id rect)]
                                       :attributes #{:stroke-width-top}
                                       :token (toht/get-token file "stroke-width.sm")
                                       :on-update-shape dwta/update-stroke-width-side})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 rect' (cths/get-shape file' :rect-1)
                 stroke' (get-in rect' [:strokes 0])]
             (t/testing "the applied side gets the token value"
               (t/is (= (:stroke-width-top stroke') 10)))
             (t/testing "the remaining sides keep their width"
               (t/is (= (:stroke-width-right stroke') 5))
               (t/is (= (:stroke-width-bottom stroke') 5))
               (t/is (= (:stroke-width-left stroke') 5)))
             (t/testing "the global width mirrors the top side"
               (t/is (= (:stroke-width stroke') 10))))))))))

(t/deftest test-change-stroke-width-side-no-propagation
  (t/testing "editing the top side of a stroke with only a global width does not propagate to the other sides"
    (t/async
      done
      (let [file (setup-file-with-tokens
                  {:rect-1 {:strokes [{:stroke-alignment :inner
                                       :stroke-style :solid
                                       :stroke-color "#000000"
                                       :stroke-opacity 1
                                       :stroke-width 5}]}})
            store (ths/setup-store file)
            rect (cths/get-shape file :rect-1)
            events [(dc/change-stroke-side-width [(:id rect)] :stroke-width-top 10 0)]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 rect' (cths/get-shape file' :rect-1)
                 stroke' (get-in rect' [:strokes 0])]
             (t/testing "the edited side gets the new value"
               (t/is (= (:stroke-width-top stroke') 10)))
             (t/testing "the remaining sides keep their width"
               (t/is (= (:stroke-width-right stroke') 5))
               (t/is (= (:stroke-width-bottom stroke') 5))
               (t/is (= (:stroke-width-left stroke') 5)))
             (t/testing "the global width mirrors the top side"
               (t/is (= (:stroke-width stroke') 10))))))))))

(t/deftest test-change-stroke-width-side-keeps-other-token
  (t/testing "editing one side does not unapply a token applied to another side"
    (t/async
      done
      (let [file (setup-file-with-tokens
                  {:rect-1 {:strokes [{:stroke-alignment :inner
                                       :stroke-style :solid
                                       :stroke-color "#000000"
                                       :stroke-opacity 1
                                       :stroke-width 5
                                       :stroke-width-top 2
                                       :stroke-width-right 9}]
                            :applied-tokens {:stroke-width-right "stroke-width.sm"}}})
            store (ths/setup-store file)
            rect (cths/get-shape file :rect-1)
            events [(dc/change-stroke-side-width [(:id rect)] :stroke-width-top 10 0)]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 rect' (cths/get-shape file' :rect-1)
                 stroke' (get-in rect' [:strokes 0])]
             (t/testing "the edited side changes"
               (t/is (= (:stroke-width-top stroke') 10)))
             (t/testing "a side with its own value keeps it"
               (t/is (= (:stroke-width-right stroke') 9)))
             (t/testing "the token on the untouched side is not unapplied"
               (t/is (= (:stroke-width-right (:applied-tokens rect')) "stroke-width.sm"))))))))))

(t/deftest test-change-stroke-width-side-later-stroke-keeps-first-token
  (t/testing "editing a side of a later stroke does not unapply a token on the first stroke"
    (t/async
      done
      (let [file (setup-file-with-tokens
                  {:rect-1 {:strokes [{:stroke-alignment :inner
                                       :stroke-style :solid
                                       :stroke-color "#000000"
                                       :stroke-opacity 1
                                       :stroke-width 5
                                       :stroke-width-top 2}
                                      {:stroke-alignment :inner
                                       :stroke-style :solid
                                       :stroke-color "#000000"
                                       :stroke-opacity 1
                                       :stroke-width 3}]
                            :applied-tokens {:stroke-width-top "stroke-width.sm"}}})
            store (ths/setup-store file)
            rect (cths/get-shape file :rect-1)
            events [(dc/change-stroke-side-width [(:id rect)] :stroke-width-top 10 1)]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 rect' (cths/get-shape file' :rect-1)
                 stroke-0 (get-in rect' [:strokes 0])
                 stroke-1 (get-in rect' [:strokes 1])]
             (t/testing "the edited second stroke gets the new value"
               (t/is (= (:stroke-width-top stroke-1) 10)))
             (t/testing "the first stroke keeps its width"
               (t/is (= (:stroke-width-top stroke-0) 2)))
             (t/testing "the token on the first stroke is not unapplied"
               (t/is (= (:stroke-width-top (:applied-tokens rect')) "stroke-width.sm"))))))))))

(t/deftest test-remove-later-stroke-keeps-first-token
  (t/testing "removing a later stroke does not unapply tokens on the first stroke"
    (t/async
      done
      (let [file (setup-file-with-tokens
                  {:rect-1 {:strokes [{:stroke-alignment :inner
                                       :stroke-style :solid
                                       :stroke-color "#000000"
                                       :stroke-opacity 1
                                       :stroke-width 5}
                                      {:stroke-alignment :inner
                                       :stroke-style :solid
                                       :stroke-color "#000000"
                                       :stroke-opacity 1
                                       :stroke-width 3}]
                            :applied-tokens {:stroke-width-top "stroke-width.sm"
                                             :stroke-color "color.sm"}}})
            store (ths/setup-store file)
            rect (cths/get-shape file :rect-1)
            events [(dc/remove-stroke [(:id rect)] 1)]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 rect' (cths/get-shape file' :rect-1)]
             (t/testing "only the first stroke remains"
               (t/is (= 1 (count (:strokes rect')))))
             (t/testing "the tokens on the first stroke are kept"
               (t/is (= (:stroke-width-top (:applied-tokens rect')) "stroke-width.sm"))
               (t/is (= (:stroke-color (:applied-tokens rect')) "color.sm"))))))))))

(t/deftest test-change-stroke-width-side-new-shape
  (t/testing "editing a side of a shape without strokes creates a stroke and zeroes the other sides"
    (t/async
      done
      (let [file (setup-file-with-tokens)
            store (ths/setup-store file)
            rect (cths/get-shape file :rect-1)
            events [(dc/change-stroke-side-width [(:id rect)] :stroke-width-top 8 0)]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 rect' (cths/get-shape file' :rect-1)
                 stroke' (get-in rect' [:strokes 0])]
             (t/testing "the edited side gets the value"
               (t/is (= (:stroke-width-top stroke') 8)))
             (t/testing "the remaining sides go to zero"
               (t/is (= (:stroke-width-right stroke') 0))
               (t/is (= (:stroke-width-bottom stroke') 0))
               (t/is (= (:stroke-width-left stroke') 0)))
             (t/testing "the global width mirrors the top side"
               (t/is (= (:stroke-width stroke') 8))))))))))

(t/deftest test-change-stroke-width-side-right-does-not-touch-global
  (t/testing "editing a non-top side leaves the global width intact"
    (t/async
      done
      (let [file (setup-file-with-tokens
                  {:rect-1 {:strokes [{:stroke-alignment :inner
                                       :stroke-style :solid
                                       :stroke-color "#000000"
                                       :stroke-opacity 1
                                       :stroke-width 5
                                       :stroke-width-top 2}]}})
            store (ths/setup-store file)
            rect (cths/get-shape file :rect-1)
            events [(dc/change-stroke-side-width [(:id rect)] :stroke-width-right 7 0)]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 rect' (cths/get-shape file' :rect-1)
                 stroke' (get-in rect' [:strokes 0])]
             (t/testing "the right side gets the new value"
               (t/is (= (:stroke-width-right stroke') 7)))
             (t/testing "the global width keeps mirroring the top side"
               (t/is (= (:stroke-width stroke') 2)))
             (t/testing "the other sides keep their width"
               (t/is (= (:stroke-width-bottom stroke') 5))
               (t/is (= (:stroke-width-left stroke') 5))))))))))

(t/deftest test-apply-dimensions-token-to-stroke-width-per-side
  (t/testing "applying a dimension token to stroke width updates every side"
    (t/async
      done
      (let [dimensions-token {:name "dimensions.md"
                              :value "8"
                              :type :dimensions}
            file (-> (setup-file-with-tokens {:rect-1 {:strokes [{:stroke-alignment :inner
                                                                  :stroke-style :solid
                                                                  :stroke-color "#000000"
                                                                  :stroke-opacity 1
                                                                  :stroke-width 5
                                                                  :stroke-width-top 2
                                                                  :stroke-width-right 3
                                                                  :stroke-width-bottom 4
                                                                  :stroke-width-left 6}]}})
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token dimensions-token))))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(dwta/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes ctt/per-side-stroke-width-keys
                                       :token (toht/get-token file "dimensions.md")
                                       :on-update-shape dwta/update-stroke-width-side})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "dimensions.md")
                 rect-1' (cths/get-shape file' :rect-1)
                 stroke' (get-in rect-1' [:strokes 0])]
             (t/testing "every side gets the resolved value"
               (t/is (= (:stroke-width-top stroke') 8))
               (t/is (= (:stroke-width-right stroke') 8))
               (t/is (= (:stroke-width-bottom stroke') 8))
               (t/is (= (:stroke-width-left stroke') 8))
               (t/is (= (:stroke-width stroke') 8)))
             (t/testing "the token is recorded on every side"
               (t/is (= (:stroke-width-top (:applied-tokens rect-1')) (:name token-target')))
               (t/is (= (:stroke-width-right (:applied-tokens rect-1')) (:name token-target')))
               (t/is (= (:stroke-width-bottom (:applied-tokens rect-1')) (:name token-target')))
               (t/is (= (:stroke-width-left (:applied-tokens rect-1')) (:name token-target')))))))))))

(t/deftest test-apply-stroke-width-overwrites-per-side-token
  (t/testing "applying token to all sides overwrites per-side token"
    (t/async
      done
      (let [token-sm {:name "stroke-width.sm"
                      :value "6"
                      :type :stroke-width}
            token-lg {:name "stroke-width.lg"
                      :value "12"
                      :type :stroke-width}
            file (-> (setup-file-with-tokens {:rect-1 {:strokes [{:stroke-alignment :inner
                                                                  :stroke-style :solid
                                                                  :stroke-color "#000000"
                                                                  :stroke-opacity 1
                                                                  :stroke-width 5}]}})
                     (update-in [:data :tokens-lib]
                                (fn [lib]
                                  (-> lib
                                      (ctob/add-token (cthi/id :set-a) (ctob/make-token token-sm))
                                      (ctob/add-token (cthi/id :set-a) (ctob/make-token token-lg))))))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            step2 (fn [state1]
                    (let [file1 (ths/get-file-from-state state1)
                          rect1 (cths/get-shape file1 :rect-1)]
                      ;; Verify token-sm applied to top only
                      (t/is (= (:stroke-width-top (:applied-tokens rect1)) "stroke-width.sm"))
                      (t/is (nil? (:stroke-width-right (:applied-tokens rect1))))
                      ;; Second: apply token-lg to all 4 sides
                      (let [events2 [(dwta/apply-token {:shape-ids [(:id rect1)]
                                                        :attributes ctt/per-side-stroke-width-keys
                                                        :token (toht/get-token file1 "stroke-width.lg")
                                                        :on-update-shape dwta/update-stroke-width})]]
                        (tohs/run-store-async
                         (ths/setup-store file1) done events2
                         (fn [state2]
                           (let [file2 (ths/get-file-from-state state2)
                                 rect2 (cths/get-shape file2 :rect-1)]
                             (t/testing "token-lg overwrites all sides"
                               (t/is (= (:stroke-width-top (:applied-tokens rect2)) "stroke-width.lg"))
                               (t/is (= (:stroke-width-right (:applied-tokens rect2)) "stroke-width.lg"))
                               (t/is (= (:stroke-width-bottom (:applied-tokens rect2)) "stroke-width.lg"))
                               (t/is (= (:stroke-width-left (:applied-tokens rect2)) "stroke-width.lg"))
                               (t/is (= (get-in rect2 [:strokes 0 :stroke-width]) 12)))))))))]
        ;; First: apply token-sm to top side only
        (tohs/run-store-async
         store (constantly nil)
         [(dwta/apply-token {:shape-ids [(:id rect-1)]
                             :attributes #{:stroke-width-top}
                             :token (toht/get-token file "stroke-width.sm")
                             :on-update-shape dwta/update-stroke-width-side})]
         step2)))))

(t/deftest test-detach-token-from-single-side
  (t/testing "detaching token from one side leaves other sides intact"
    (t/async
      done
      (let [token {:name "stroke-width.sm"
                   :value "10"
                   :type :stroke-width}
            file (-> (setup-file-with-tokens {:rect-1 {:strokes [{:stroke-alignment :inner
                                                                  :stroke-style :solid
                                                                  :stroke-color "#000000"
                                                                  :stroke-opacity 1
                                                                  :stroke-width 5}]}})
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token token))))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            step2 (fn [state1]
                    (let [file1 (ths/get-file-from-state state1)
                          rect1 (cths/get-shape file1 :rect-1)]
                      ;; Verify all sides have the token
                      (t/is (= (:stroke-width-top (:applied-tokens rect1)) "stroke-width.sm"))
                      (t/is (= (:stroke-width-right (:applied-tokens rect1)) "stroke-width.sm"))
                      (t/is (= (:stroke-width-bottom (:applied-tokens rect1)) "stroke-width.sm"))
                      (t/is (= (:stroke-width-left (:applied-tokens rect1)) "stroke-width.sm"))
                      ;; Second: unapply token from top side only
                      (let [events2 [(dwta/unapply-token {:token-name "stroke-width.sm"
                                                          :attributes #{:stroke-width-top}
                                                          :shape-ids [(:id rect1)]})]]
                        (tohs/run-store-async
                         (ths/setup-store file1) done events2
                         (fn [state2]
                           (let [file2 (ths/get-file-from-state state2)
                                 rect2 (cths/get-shape file2 :rect-1)]
                             (t/testing "top side token detached"
                               (t/is (nil? (:stroke-width-top (:applied-tokens rect2)))))
                             (t/testing "other sides retain the token"
                               (t/is (= (:stroke-width-right (:applied-tokens rect2)) "stroke-width.sm"))
                               (t/is (= (:stroke-width-bottom (:applied-tokens rect2)) "stroke-width.sm"))
                               (t/is (= (:stroke-width-left (:applied-tokens rect2)) "stroke-width.sm")))))))))]
        ;; First: apply token to all 4 sides
        (tohs/run-store-async
         store (constantly nil)
         [(dwta/apply-token {:shape-ids [(:id rect-1)]
                             :attributes ctt/per-side-stroke-width-keys
                             :token (toht/get-token file "stroke-width.sm")
                             :on-update-shape dwta/update-stroke-width})]
         step2)))))

(t/deftest test-apply-shadow
  (t/testing "applies shadow token and updates the shapes with shadow"
    (t/async
      done
      (let [shadow-token {:name "shadow.sm"
                          :value [{:offset-x 10
                                   :offset-y 10
                                   :blur 10
                                   :spread 10
                                   :color "rgba(0,0,0,0.5)"
                                   :inset false}]
                          :type :shadow}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token shadow-token))))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(dwta/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:shadow}
                                       :token (toht/get-token file "shadow.sm")
                                       :on-update-shape dwta/update-shadow})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "shadow.sm")
                 rect-1' (cths/get-shape file' :rect-1)
                 shadow (first (:shadow rect-1'))]
             (t/testing "token got applied to rect with shadow and shape shadow got updated"
               (t/is (= (:shadow (:applied-tokens rect-1')) (:name token-target')))
               (t/is (= (:offset-x shadow) 10))
               (t/is (= (:offset-y shadow) 10))
               (t/is (= (:blur shadow) 10))
               (t/is (= (:spread shadow) 10))
               (t/is (= (get-in shadow [:color :color]) "#000000"))
               (t/is (= (get-in shadow [:color :opacity]) 0.5))))))))))

(t/deftest test-apply-font-size
  (t/testing "applies font-size token and updates the text font-size"
    (t/async
      done
      (let [font-size-token {:name "heading-size"
                             :value "24"
                             :type :font-size}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token font-size-token))))
            store (ths/setup-store file)
            text-1 (cths/get-shape file :text-1)
            events [(dwta/apply-token {:shape-ids [(:id text-1)]
                                       :attributes #{:font-size}
                                       :token (toht/get-token file "heading-size")
                                       :on-update-shape dwta/update-font-size})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "heading-size")
                 text-1' (cths/get-shape file' :text-1)
                 style-text-blocks (->> (:content text-1')
                                        (txt/content->text+styles)
                                        (remove (fn [[_ text]] (str/empty? (str/trim text))))
                                        (mapv (fn [[style text]]
                                                {:styles (merge txt/default-text-attrs style)
                                                 :text-content text}))
                                        (first)
                                        (:styles))]
             (t/is (some? (:applied-tokens text-1')))
             (t/is (= (:font-size (:applied-tokens text-1')) (:name token-target')))
             (t/is (= (:font-size style-text-blocks) "24"))
             (t/testing "WASM text mocks were exercised"
               (t/is (pos? (thw/call-count :set-shape-text-content)))
               (t/is (pos? (thw/call-count :get-text-dimensions))))))
         debounce-text-stop)))))

(t/deftest test-apply-line-height
  (t/testing "applies line-height token and updates the text line-height"
    (t/async
      done
      (let [line-height-token {:name "big-height"
                               :value "1.5"
                               :type :number}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token line-height-token))))
            store (ths/setup-store file)
            text-1 (cths/get-shape file :text-1)
            events [(dwta/apply-token {:shape-ids [(:id text-1)]
                                       :attributes #{:line-height}
                                       :token (toht/get-token file "big-height")
                                       :on-update-shape dwta/update-line-height})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "big-height")
                 text-1' (cths/get-shape file' :text-1)
                 style-text-blocks (->> (:content text-1')
                                        (txt/content->text+styles)
                                        (remove (fn [[_ text]] (str/empty? (str/trim text))))
                                        (mapv (fn [[style text]]
                                                {:styles (merge txt/default-text-attrs style)
                                                 :text-content text}))
                                        (first)
                                        (:styles))]
             (t/is (some? (:applied-tokens text-1')))
             (t/is (= (:line-height (:applied-tokens text-1')) (:name token-target')))
             (t/is (= (:line-height style-text-blocks) 1.5))
             (t/testing "WASM text mocks were exercised"
               (t/is (pos? (thw/call-count :set-shape-text-content)))
               (t/is (pos? (thw/call-count :get-text-dimensions))))))
         debounce-text-stop)))))

(t/deftest test-apply-letter-spacing
  (t/testing "applies letter-spacing token and updates the text letter-spacing"
    (t/async
      done
      (let [letter-spacing-token {:name "wide-spacing"
                                  :value "2"
                                  :type :letter-spacing}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token letter-spacing-token))))
            store (ths/setup-store file)
            text-1 (cths/get-shape file :text-1)
            events [(dwta/apply-token {:shape-ids [(:id text-1)]
                                       :attributes #{:letter-spacing}
                                       :token (toht/get-token file "wide-spacing")
                                       :on-update-shape dwta/update-letter-spacing})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "wide-spacing")
                 text-1' (cths/get-shape file' :text-1)
                 style-text-blocks (->> (:content text-1')
                                        (txt/content->text+styles)
                                        (remove (fn [[_ text]] (str/empty? (str/trim text))))
                                        (mapv (fn [[style text]]
                                                {:styles (merge txt/default-text-attrs style)
                                                 :text-content text}))
                                        (first)
                                        (:styles))]
             (t/is (some? (:applied-tokens text-1')))
             (t/is (= (:letter-spacing (:applied-tokens text-1')) (:name token-target')))
             (t/is (= (:letter-spacing style-text-blocks) "2"))
             (t/testing "WASM text mocks were exercised"
               (t/is (pos? (thw/call-count :set-shape-text-content)))
               (t/is (pos? (thw/call-count :get-text-dimensions))))))
         debounce-text-stop)))))

(t/deftest test-apply-font-family
  (t/testing "applies font-family token and updates the text font-family"
    (t/async
      done
      (let [font-family-token {:name "primary-font"
                               :value "Arial"
                               :type :font-family}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token font-family-token))))
            store (ths/setup-store file)
            text-1 (cths/get-shape file :text-1)
            events [(dwta/apply-token {:shape-ids [(:id text-1)]
                                       :attributes #{:font-family}
                                       :token (toht/get-token file "primary-font")
                                       :on-update-shape dwta/update-font-family})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "primary-font")
                 text-1' (cths/get-shape file' :text-1)
                 style-text-blocks (->> (:content text-1')
                                        (txt/content->text+styles)
                                        (remove (fn [[_ text]] (str/empty? (str/trim text))))
                                        (mapv (fn [[style text]]
                                                {:styles (merge txt/default-text-attrs style)
                                                 :text-content text}))
                                        (first)
                                        (:styles))]
             (t/is (some? (:applied-tokens text-1')))
             (t/is (= (:font-family (:applied-tokens text-1')) (:name token-target')))
             (t/is (= (:font-family style-text-blocks) (:font-id txt/default-text-attrs)))
             (t/testing "WASM text mocks were exercised"
               (t/is (pos? (thw/call-count :set-shape-text-content)))
               (t/is (pos? (thw/call-count :get-text-dimensions))))))
         debounce-text-stop)))))

(t/deftest test-apply-text-case
  (t/testing "applies text-case token and updates the text transform"
    (t/async
      done
      (let [text-case-token {:name "uppercase-case"
                             :value "uppercase"
                             :type :text-case}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token text-case-token))))
            store (ths/setup-store file)
            text-1 (cths/get-shape file :text-1)
            events [(dwta/apply-token {:shape-ids [(:id text-1)]
                                       :attributes #{:text-case}
                                       :token (toht/get-token file "uppercase-case")
                                       :on-update-shape dwta/update-text-case})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "uppercase-case")
                 text-1' (cths/get-shape file' :text-1)
                 style-text-blocks (->> (:content text-1')
                                        (txt/content->text+styles)
                                        (remove (fn [[_ text]] (str/empty? (str/trim text))))
                                        (mapv (fn [[style text]]
                                                {:styles (merge txt/default-text-attrs style)
                                                 :text-content text}))
                                        (first)
                                        (:styles))]
             (t/is (some? (:applied-tokens text-1')))
             (t/is (= (:text-case (:applied-tokens text-1')) (:name token-target')))
             (t/is (= (:text-transform style-text-blocks) "uppercase")))))))))

(t/deftest test-apply-text-decoration
  (t/testing "applies text-decoration token and updates the text decoration"
    (t/async
      done
      (let [text-decoration-token {:name "underline-decoration"
                                   :value "underline"
                                   :type :text-decoration}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token text-decoration-token))))
            store (ths/setup-store file)
            text-1 (cths/get-shape file :text-1)
            events [(dwta/apply-token {:shape-ids [(:id text-1)]
                                       :attributes #{:text-decoration}
                                       :token (toht/get-token file "underline-decoration")
                                       :on-update-shape dwta/update-text-decoration})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "underline-decoration")
                 text-1' (cths/get-shape file' :text-1)
                 style-text-blocks (->> (:content text-1')
                                        (txt/content->text+styles)
                                        (remove (fn [[_ text]] (str/empty? (str/trim text))))
                                        (mapv (fn [[style text]]
                                                {:styles (merge txt/default-text-attrs style)
                                                 :text-content text}))
                                        (first)
                                        (:styles))]
             (t/is (some? (:applied-tokens text-1')))
             (t/is (= (:text-decoration (:applied-tokens text-1')) (:name token-target')))
             (t/is (= (:text-decoration style-text-blocks) "underline")))))))))

(t/deftest test-apply-font-weight
  (t/testing "applies font-weight token and updates the font weight"
    (t/async
      done
      (let [font-weight-token {:name "font-weight"
                               :value "regular"
                               :type :font-weight}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token font-weight-token))))
            store (ths/setup-store file)
            text-1 (cths/get-shape file :text-1)
            events [(dwta/apply-token {:shape-ids [(:id text-1)]
                                       :attributes #{:font-weight}
                                       :token (toht/get-token file "font-weight")
                                       :on-update-shape dwta/update-font-weight})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "font-weight")
                 text-1' (cths/get-shape file' :text-1)
                 style-text-blocks (->> (:content text-1')
                                        (txt/content->text+styles)
                                        (remove (fn [[_ text]] (str/empty? (str/trim text))))
                                        (mapv (fn [[style text]]
                                                {:styles (merge txt/default-text-attrs style)
                                                 :text-content text}))
                                        (first)
                                        (:styles))]
             (t/is (some? (:applied-tokens text-1')))
             (t/is (= (:font-weight (:applied-tokens text-1')) (:name token-target')))
             (t/is (= (:font-weight style-text-blocks) "400"))
             (t/testing "WASM text mocks were exercised"
               (t/is (pos? (thw/call-count :set-shape-text-content)))
               (t/is (pos? (thw/call-count :get-text-dimensions))))))
         debounce-text-stop)))))

(t/deftest test-toggle-token-none
  (t/testing "should apply token to all selected items, where no item has the token applied"
    (t/async
      done
      (let [file (setup-file-with-tokens)
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            rect-2 (cths/get-shape file :rect-2)
            events [(dwta/toggle-token {:shape-ids [(:id rect-1) (:id rect-2)]
                                        :token-type-props {:attributes #{:r1 :r2 :r3 :r4}
                                                           :on-update-shape dwta/update-shape-radius}
                                        :token (toht/get-token file "borderRadius.md")})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-2' (toht/get-token file' "borderRadius.md")
                 rect-1' (cths/get-shape file' :rect-1)
                 rect-2' (cths/get-shape file' :rect-2)]
             (t/is (some? (:applied-tokens rect-1')))
             (t/is (some? (:applied-tokens rect-2')))
             (t/is (= (:r1 (:applied-tokens rect-1')) (:name token-2')))
             (t/is (= (:r1 (:applied-tokens rect-2')) (:name token-2')))
             (t/is (= (:r1 rect-1') 24))
             (t/is (= (:r1 rect-2') 24)))))))))

(t/deftest test-toggle-token-mixed
  (t/testing "should unapply given token if one of the selected items has the token applied while keeping other tokens with some attributes"
    (t/async
      done
      (let [file (-> (setup-file-with-tokens)
                     (toht/apply-token-to-shape :rect-1 "borderRadius.sm" #{:r1 :r2 :r3 :r4})
                     (toht/apply-token-to-shape :rect-3 "borderRadius.md" #{:r1 :r2 :r3 :r4}))
            store (ths/setup-store file)

            rect-with-token (cths/get-shape file :rect-1)
            rect-without-token (cths/get-shape file :rect-2)
            rect-with-other-token (cths/get-shape file :rect-3)

            events [(dwta/toggle-token {:shape-ids [(:id rect-with-token) (:id rect-without-token) (:id rect-with-other-token)]
                                        :token (toht/get-token file "borderRadius.sm")
                                        :token-type-props {:attributes #{:r1 :r2 :r3 :r4}}})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 rect-with-token' (cths/get-shape file' :rect-1)
                 rect-without-token' (cths/get-shape file' :rect-2)
                 rect-with-other-token' (cths/get-shape file' :rect-3)]

             (t/testing "rect-with-token got the token removed"
               (t/is (nil? (:r1 (:applied-tokens rect-with-token')))))

             (t/testing "rect-without-token didn't get updated"
               (t/is (= (:applied-tokens rect-without-token') (:applied-tokens rect-without-token))))

             (t/testing "rect-with-other-token didn't get updated"
               (t/is (= (:applied-tokens rect-with-other-token') (:applied-tokens rect-with-other-token)))))))))))

(t/deftest test-toggle-token-apply-to-all
  (t/testing "should apply token to all if none of the shapes has it applied"
    (t/async
      done
      (let [file (-> (setup-file-with-tokens)
                     (toht/apply-token-to-shape :rect-1 "borderRadius.md" #{:r1 :r2 :r3 :r4})
                     (toht/apply-token-to-shape :rect-3 "borderRadius.md" #{:r1 :r2 :r3 :r4}))
            store (ths/setup-store file)

            rect-with-other-token-1 (cths/get-shape file :rect-1)
            rect-without-token (cths/get-shape file :rect-2)
            rect-with-other-token-2 (cths/get-shape file :rect-3)

            events [(dwta/toggle-token {:shape-ids [(:id rect-with-other-token-1) (:id rect-without-token) (:id rect-with-other-token-2)]
                                        :token (toht/get-token file "borderRadius.sm")
                                        :token-type-props {:attributes #{:r1 :r2 :r3 :r4}}})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 target-token (toht/get-token file' "borderRadius.sm")
                 rect-with-other-token-1' (cths/get-shape file' :rect-1)
                 rect-without-token' (cths/get-shape file' :rect-2)
                 rect-with-other-token-2' (cths/get-shape file' :rect-3)]

             (t/testing "token got applied to all shapes"
               (t/is (= (:r1 (:applied-tokens rect-with-other-token-1')) (:name target-token)))
               (t/is (= (:r1 (:applied-tokens rect-without-token')) (:name target-token)))
               (t/is (= (:r1 (:applied-tokens rect-with-other-token-2')) (:name target-token)))))))))))

(t/deftest test-toggle-token-completes-partial-per-side-application
  (t/testing "toggling a token already applied to some sides applies it to all"
    (t/async
      done
      (let [stroke-width-token {:name "strokeWidth.md" :value "10" :type :stroke-width}
            file (-> (setup-file-with-tokens {:rect-1 {:strokes [{:stroke-alignment :inner
                                                                  :stroke-style :solid
                                                                  :stroke-color "#000000"
                                                                  :stroke-opacity 1
                                                                  :stroke-width 5}]}})
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token stroke-width-token)))
                     (toht/apply-token-to-shape :rect-1 "strokeWidth.md"
                                                #{:stroke-width-right :stroke-width-bottom}))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(dwta/toggle-token {:shape-ids [(:id rect-1)]
                                        :token (toht/get-token file "strokeWidth.md")
                                        :attrs ctt/per-side-stroke-width-keys})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 rect-1' (cths/get-shape file' :rect-1)
                 stroke' (get-in rect-1' [:strokes 0])]
             (t/testing "the token is applied to every side"
               (t/is (= (:stroke-width-top (:applied-tokens rect-1')) "strokeWidth.md"))
               (t/is (= (:stroke-width-right (:applied-tokens rect-1')) "strokeWidth.md"))
               (t/is (= (:stroke-width-bottom (:applied-tokens rect-1')) "strokeWidth.md"))
               (t/is (= (:stroke-width-left (:applied-tokens rect-1')) "strokeWidth.md")))

             (t/testing "every side gets the resolved value"
               (t/is (= (:stroke-width-top stroke') 10))
               (t/is (= (:stroke-width-right stroke') 10))
               (t/is (= (:stroke-width-bottom stroke') 10))
               (t/is (= (:stroke-width-left stroke') 10))))))))))

(t/deftest test-toggle-token-explicit-attrs-unapplies-when-fully-applied
  (t/testing "toggling a token applied to every side removes it"
    (t/async
      done
      (let [stroke-width-token {:name "strokeWidth.md" :value "10" :type :stroke-width}
            file (-> (setup-file-with-tokens {:rect-1 {:strokes [{:stroke-alignment :inner
                                                                  :stroke-style :solid
                                                                  :stroke-color "#000000"
                                                                  :stroke-opacity 1
                                                                  :stroke-width 5}]}})
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token stroke-width-token)))
                     (toht/apply-token-to-shape :rect-1 "strokeWidth.md"
                                                ctt/per-side-stroke-width-keys))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(dwta/toggle-token {:shape-ids [(:id rect-1)]
                                        :token (toht/get-token file "strokeWidth.md")
                                        :attrs ctt/per-side-stroke-width-keys})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 rect-1' (cths/get-shape file' :rect-1)
                 applied' (:applied-tokens rect-1')]
             (t/is (nil? (:stroke-width-top applied')))
             (t/is (nil? (:stroke-width-right applied')))
             (t/is (nil? (:stroke-width-bottom applied')))
             (t/is (nil? (:stroke-width-left applied'))))))))))

(t/deftest test-toggle-spacing-token
  (t/testing "applies spacing token only to layouts and layout children"
    (t/async
      done
      (let [spacing-token {:name "spacing.md"
                           :value "16"
                           :type :spacing}
            file (-> (setup-file-with-tokens)
                     (ctho/add-frame-with-child :frame-layout :rect-in-layout
                                                {:frame-params {:layout :grid}})
                     (ctho/add-rect :rect-regular)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token spacing-token))))
            store (ths/setup-store file)
            frame-layout (cths/get-shape file :frame-layout)
            rect-in-layout (cths/get-shape file :rect-in-layout)
            rect-regular (cths/get-shape file :rect-regular)
            events [(dwta/toggle-token {:token (toht/get-token file "spacing.md")
                                        :shape-ids [(:id frame-layout) (:id rect-in-layout) (:id rect-regular)]})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 frame-layout' (cths/get-shape file' :frame-layout)
                 rect-in-layout' (cths/get-shape file' :rect-in-layout)
                 rect-regular' (cths/get-shape file' :rect-regular)]

             (t/testing "frame with layout gets all spacing attributes"
               (t/is (= "spacing.md" (:column-gap (:applied-tokens frame-layout'))))
               (t/is (= "spacing.md" (:row-gap (:applied-tokens frame-layout'))))
               (t/is (= 16 (get-in frame-layout' [:layout-gap :column-gap])))
               (t/is (= 16 (get-in frame-layout' [:layout-gap :row-gap]))))

             (t/testing "shape inside layout frame gets only margin attributes"
               (t/is (= "spacing.md" (:m1 (:applied-tokens rect-in-layout'))))
               (t/is (= "spacing.md" (:m2 (:applied-tokens rect-in-layout'))))
               (t/is (= "spacing.md" (:m3 (:applied-tokens rect-in-layout'))))
               (t/is (= "spacing.md" (:m4 (:applied-tokens rect-in-layout'))))
               (t/is (nil? (:column-gap (:applied-tokens rect-in-layout'))))
               (t/is (nil? (:row-gap (:applied-tokens rect-in-layout'))))
               (t/is (= {:m1 16, :m2 16, :m3 16, :m4 16} (get rect-in-layout' :layout-item-margin))))

             (t/testing "regular shape doesn't get spacing attributes"
               (t/is (nil? (:applied-tokens rect-regular')))))))))))

(t/deftest test-detach-styles-color
  (t/testing "applying a color token to a shape with color styles should detach the styles"
    (t/async
      done
      (let [color-token {:name "color.primary"
                         :value "red"
                         :type :color}
            file (setup-file-with-tokens)
            file (-> file
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token % (cthi/id :set-a)
                                                 (ctob/make-token color-token)))
                     (cths/add-sample-library-color :color1 {:name "Test color"
                                                             :color "#abcdef"})
                     (cths/update-shape :rect-1 :fills
                                        (cths/sample-fills-color :fill-color "#fabada"
                                                                 :fill-color-ref-id (cthi/id :color1)
                                                                 :fill-color-ref-file (:id file))))
            store (ths/setup-store file)
            events [(dwta/apply-token {:shape-ids [(cthi/id :rect-1)]
                                       :attributes #{:fill}
                                       :token (toht/get-token file "color.primary")
                                       :on-update-shape dwta/update-fill})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file'   (ths/get-file-from-state new-state)
                 rect-1' (cths/get-shape file' :rect-1)
                 fills   (:fills rect-1')
                 fill    (first fills)]
             (t/is (nil? (:fill-color-ref-id fill)))
             (t/is (nil? (:fill-color-ref-file fill))))))))))

(t/deftest test-apply-typography-token
  (t/testing "applies typography (composite) tokens"
    (t/async
      done
      (let [font-size-token {:name "font-size-reference"
                             :value "100px"
                             :type :font-size}
            font-family-token {:name "font-family-reference"
                               :value ["Arial" "sans-serif"]
                               :type :font-family}
            typography-token {:name "typography.heading"
                              :value {:font-size "24px"
                                      :font-weight "bold"
                                      :font-family [(:font-id txt/default-text-attrs) "Arial" "sans-serif"]
                                      :line-height "24px"
                                      :letter-spacing "2"
                                      :text-case "uppercase"
                                      :text-decoration "underline"}
                              :type :typography}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(-> %
                                     (ctob/add-token (cthi/id :set-a) (ctob/make-token font-size-token))
                                     (ctob/add-token (cthi/id :set-a) (ctob/make-token font-family-token))
                                     (ctob/add-token (cthi/id :set-a) (ctob/make-token typography-token)))))
            store (ths/setup-store file)
            text-1 (cths/get-shape file :text-1)
            events [(dwta/apply-token {:shape-ids [(:id text-1)]
                                       :attributes #{:typography}
                                       :token (toht/get-token file "typography.heading")
                                       :on-update-shape dwta/update-typography})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 text-1' (cths/get-shape file' :text-1)
                 style-text-blocks (->> (:content text-1')
                                        (txt/content->text+styles)
                                        (remove (fn [[_ text]] (str/empty? (str/trim text))))
                                        (mapv (fn [[style text]]
                                                {:styles (merge txt/default-text-attrs style)
                                                 :text-content text}))
                                        (first)
                                        (:styles))]
             (t/is (some? (:applied-tokens text-1')))
             (t/is (= (:typography (:applied-tokens text-1')) "typography.heading"))

             (t/is (= (:font-size style-text-blocks) "24"))
             (t/is (= (:font-weight style-text-blocks) "700"))
             (t/is (= (:line-height style-text-blocks) 1))
             (t/is (= (:font-family style-text-blocks) "sourcesanspro"))
             (t/is (= (:letter-spacing style-text-blocks) "2"))
             (t/is (= (:text-transform style-text-blocks) "uppercase"))
             (t/is (= (:text-decoration style-text-blocks) "underline"))
             (t/testing "WASM text mocks were exercised"
               (t/is (pos? (thw/call-count :set-shape-text-content)))
               (t/is (pos? (thw/call-count :get-text-dimensions))))))
         debounce-text-stop)))))

(t/deftest test-apply-reference-typography-token
  (t/testing "applies typography (composite) tokens with references"
    (t/async
      done
      (let [font-size-token {:name "fontSize"
                             :value "100px"
                             :type :font-size}
            font-family-token {:name "fontFamily"
                               :value ["Arial" "sans-serif"]
                               :type :font-family}
            typography-token {:name "typography"
                              :value {:font-size "{fontSize}"
                                      :font-family ["{fontFamily}"]}
                              :type :typography}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(-> %
                                     (ctob/add-token (cthi/id :set-a) (ctob/make-token font-size-token))
                                     (ctob/add-token (cthi/id :set-a) (ctob/make-token font-family-token))
                                     (ctob/add-token (cthi/id :set-a) (ctob/make-token typography-token)))))
            store (ths/setup-store file)
            text-1 (cths/get-shape file :text-1)
            events [(dwta/apply-token {:shape-ids [(:id text-1)]
                                       :attributes #{:typography}
                                       :token (toht/get-token file "typography")
                                       :on-update-shape dwta/update-typography})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 text-1' (cths/get-shape file' :text-1)
                 style-text-blocks (->> (:content text-1')
                                        (txt/content->text+styles)
                                        (remove (fn [[_ text]] (str/empty? (str/trim text))))
                                        (mapv (fn [[style text]]
                                                {:styles (merge txt/default-text-attrs style)
                                                 :text-content text}))
                                        (first)
                                        (:styles))]
             (t/is (some? (:applied-tokens text-1')))
             (t/is (= (:typography (:applied-tokens text-1')) "typography"))

             (t/is (= (:font-size style-text-blocks) "100"))
             (t/is (= (:font-family style-text-blocks) "Arial"))
             (t/testing "WASM text mocks were exercised"
               (t/is (pos? (thw/call-count :set-shape-text-content)))
               (t/is (pos? (thw/call-count :get-text-dimensions))))))
         debounce-text-stop)))))

(t/deftest test-unapply-atomic-tokens-on-composite-apply
  (t/testing "unapplies atomic typography tokens when applying composite token"
    (t/async
      done
      (let [font-size-token {:name "fontSize"
                             :value "100px"
                             :type :font-size}
            typography-token {:name "typography"
                              :value {}
                              :type :typography}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(-> %
                                     (ctob/add-token (cthi/id :set-a) (ctob/make-token font-size-token))
                                     (ctob/add-token (cthi/id :set-a) (ctob/make-token typography-token)))))
            store (ths/setup-store file)
            text-1 (cths/get-shape file :text-1)
            events [(dwta/apply-token {:shape-ids [(:id text-1)]
                                       :attributes #{:typography}
                                       :token (toht/get-token file "fontSize")})
                    (dwta/apply-token {:shape-ids [(:id text-1)]
                                       :attributes #{:typography}
                                       :token (toht/get-token file "typography")
                                       :on-update-shape dwta/update-typography})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 text-1' (cths/get-shape file' :text-1)]
             (t/is (some? (:applied-tokens text-1')))
             (t/is (= (:typography (:applied-tokens text-1')) "typography"))
             (t/is (nil? (:font-size (:applied-tokens text-1')))))))))))


(t/deftest test-unapply-composite-tokens-on-atomic-apply
  (t/testing "unapplies composite typography tokens when applying atomic token"
    (t/async
      done
      (let [font-size-token {:name "fontSize"
                             :value "100px"
                             :type :font-size}
            typography-token {:name "typography"
                              :value {}
                              :type :typography}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(-> %
                                     (ctob/add-token (cthi/id :set-a) (ctob/make-token font-size-token))
                                     (ctob/add-token (cthi/id :set-a) (ctob/make-token typography-token)))))
            store (ths/setup-store file)
            text-1 (cths/get-shape file :text-1)
            events [(dwta/apply-token {:shape-ids [(:id text-1)]
                                       :attributes #{:typography}
                                       :token (toht/get-token file "typography")
                                       :on-update-shape dwta/update-typography})
                    (dwta/apply-token {:shape-ids [(:id text-1)]
                                       :attributes #{:font-size}
                                       :token (toht/get-token file "fontSize")
                                       :on-update-shape dwta/update-font-size})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 text-1' (cths/get-shape file' :text-1)]
             (t/is (some? (:applied-tokens text-1')))
             (t/is (= (:font-size (:applied-tokens text-1')) "fontSize"))
             (t/is (nil? (:typography (:applied-tokens text-1')))))))))))

(t/deftest test-detach-styles-typography
  (t/testing "applying any typography token to a shape with a typography style should detach the style"
    (t/async
      done
      (let [font-size-token {:name "heading-size"
                             :value "24"
                             :type :font-size}
            line-height-token {:name "big-height"
                               :value "1.5"
                               :type :number}
            letter-spacing-token {:name "wide-spacing"
                                  :value "2"
                                  :type :letter-spacing}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(-> %
                                     (ctob/add-token (cthi/id :set-a)
                                                     (ctob/make-token font-size-token))
                                     (ctob/add-token (cthi/id :set-a)
                                                     (ctob/make-token line-height-token))
                                     (ctob/add-token (cthi/id :set-a)
                                                     (ctob/make-token letter-spacing-token))))
                     (cths/add-sample-typography :typography1 {:name "Test typography"}))
            content {:type "root"
                     :children [{:type "paragraph-set"
                                 :children [{:type "paragraph"
                                             :key "67uep"
                                             :children [{:text "Example text"
                                                         :typography-ref-id (cthi/id :typography1)
                                                         :typography-ref-file (:id file)
                                                         :line-height "1.2"
                                                         :font-style "normal"
                                                         :text-transform "none"
                                                         :text-align "left"
                                                         :font-id "sourcesanspro"
                                                         :font-family "sourcesanspro"
                                                         :font-size "14"
                                                         :font-weight "400"
                                                         :font-variant-id "regular"
                                                         :text-decoration "none"
                                                         :letter-spacing "0"
                                                         :fills [{:fill-color "#000000"
                                                                  :fill-opacity 1}]}]}]}]}
            file (-> file
                     (ctho/add-text :text-1 "Helo World!" :text-params {:content content})
                     (ctho/add-text :text-2 "Helo World!" :text-params {:content content})
                     (ctho/add-text :text-3 "Helo World!" :text-params {:content content}))
            store (ths/setup-store file)
            events [(dwta/apply-token {:shape-ids [(cthi/id :text-1)]
                                       :attributes #{:font-size}
                                       :token (toht/get-token file "heading-size")
                                       :on-update-shape dwta/update-font-size})
                    (dwta/apply-token {:shape-ids [(cthi/id :text-2)]
                                       :attributes #{:line-height}
                                       :token (toht/get-token file "big-height")
                                       :on-update-shape dwta/update-line-height})
                    (dwta/apply-token {:shape-ids [(cthi/id :text-3)]
                                       :attributes #{:letter-spacing}
                                       :token (toht/get-token file "wide-spacing")
                                       :on-update-shape dwta/update-letter-spacing})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file'   (ths/get-file-from-state new-state)
                 text-1' (cths/get-shape file' :text-1)
                 text-2' (cths/get-shape file' :text-2)
                 text-3' (cths/get-shape file' :text-3)
                 paragraph-1 (get-in text-1' [:content :children 0 :children 0])
                 text-node-1 (get-in paragraph-1 [:children 0])
                 paragraph-2 (get-in text-2' [:content :children 0 :children 0])
                 text-node-2 (get-in paragraph-2 [:children 0])
                 paragraph-3 (get-in text-3' [:content :children 0 :children 0])
                 text-node-3 (get-in paragraph-3 [:children 0])]
             (t/is (nil? (:typography-ref-id paragraph-1)))
             (t/is (nil? (:typography-ref-file paragraph-1)))
             (t/is (nil? (:typography-ref-id text-node-1)))
             (t/is (nil? (:typography-ref-file text-node-1)))
             (t/is (nil? (:typography-ref-id paragraph-2)))
             (t/is (nil? (:typography-ref-file paragraph-2)))
             (t/is (nil? (:typography-ref-id text-node-2)))
             (t/is (nil? (:typography-ref-file text-node-2)))
             (t/is (nil? (:typography-ref-id paragraph-3)))
             (t/is (nil? (:typography-ref-file paragraph-3)))
             (t/is (nil? (:typography-ref-id text-node-3)))
             (t/is (nil? (:typography-ref-file text-node-3)))
             (t/testing "WASM text mocks were exercised"
               (t/is (pos? (thw/call-count :set-shape-text-content)))
               (t/is (pos? (thw/call-count :get-text-dimensions))))))
         debounce-text-stop)))))

;; Coverage for issue #9819 (token tree collapsed after import).
;;
;; The fold state of the token type sections lives in
;; `[:workspace-tokens :unfolded-token-types]` and is persisted in the user
;; storage. The sections restore that state from storage on mount and when the
;; selected set changes, so importing a library has to unfold the imported
;; token types on both places, otherwise the tree shows up collapsed.

(defn- unfolded-token-types
  [state]
  (get-in state [:workspace-tokens :unfolded-token-types :types]))

(defn- reset-unfolded-token-types-storage! []
  (swap! storage/user dissoc :app.main.ui.workspace.tokens/unfolded-token-types))

(defn- setup-imported-lib []
  (-> (ctob/make-tokens-lib)
      (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :imported-set-a)
                                         :name "Imported A"))
      (ctob/add-token (cthi/id :imported-set-a)
                      (ctob/make-token {:name "borderRadius.sm"
                                        :value "12"
                                        :type :border-radius}))
      (ctob/add-token (cthi/id :imported-set-a)
                      (ctob/make-token {:name "color.primary"
                                        :value "#ff0000"
                                        :type :color}))
      (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :imported-set-b)
                                         :name "Imported B"))
      (ctob/add-token (cthi/id :imported-set-b)
                      (ctob/make-token {:name "spacing.md"
                                        :value "16"
                                        :type :spacing}))))

(t/deftest test-import-tokens-lib-unfolds-token-types
  (t/testing "importing a tokens library unfolds the token types of the shown set"
    (t/async
      done
      (reset-unfolded-token-types-storage!)
      (let [file   (setup-file-with-empty-lib)
            store  (ths/setup-store file)
            lib    (setup-imported-lib)
            events [(dwtl/import-tokens-lib lib)]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (t/is (= #{:border-radius :color} (unfolded-token-types new-state)))
           (t/is (= (cthi/id :imported-set-a)
                    (get-in new-state [:workspace-tokens :unfolded-token-types :set-id])))
           (t/is (= (:id file)
                    (get-in new-state [:workspace-tokens :unfolded-token-types :file-id])))))))))

(t/deftest test-import-tokens-lib-persists-unfolded-token-types
  (t/testing "the unfolded token types survive the storage restore done on section mount"
    (t/async
      done
      (reset-unfolded-token-types-storage!)
      (let [file   (setup-file-with-empty-lib)
            store  (ths/setup-store file)
            lib    (setup-imported-lib)
            ;; Mirrors what the UI does after an import: the tokens panel selects
            ;; the first set and every token section restores its fold state from
            ;; storage on mount.
            events [(dwtl/import-tokens-lib lib)
                    (dwtl/set-selected-token-set-id (cthi/id :imported-set-a))
                    (dwtl/restore-unfolded-token-types)]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (t/is (= #{:border-radius :color} (unfolded-token-types new-state)))))))))

(t/deftest test-import-tokens-lib-unfolds-every-imported-set
  (t/testing "switching to another imported set also shows it unfolded"
    (t/async
      done
      (reset-unfolded-token-types-storage!)
      (let [file   (setup-file-with-empty-lib)
            store  (ths/setup-store file)
            lib    (setup-imported-lib)
            events [(dwtl/import-tokens-lib lib)
                    (dwtl/set-selected-token-set-id (cthi/id :imported-set-b))]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (t/is (= #{:spacing} (unfolded-token-types new-state)))))))))

(t/deftest test-import-tokens-lib-keeps-previously-unfolded-types
  (t/testing "importing does not fold token types the user had already unfolded"
    (t/async
      done
      (reset-unfolded-token-types-storage!)
      (let [file   (setup-file-with-tokens)
            store  (ths/setup-store file)
            lib    (-> (get-in file [:data :tokens-lib])
                       (ctob/add-token (cthi/id :set-a)
                                       (ctob/make-token {:name "color.primary"
                                                         :value "#ff0000"
                                                         :type :color})))
            events [(dwtl/set-selected-token-set-id (cthi/id :set-a))
                    (dwtl/open-token-type :opacity)
                    (dwtl/import-tokens-lib lib)]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (t/is (= #{:border-radius :color :opacity} (unfolded-token-types new-state)))))))))

(t/deftest test-import-empty-tokens-lib
  (t/testing "importing a library without sets does not fail nor unfold anything"
    (t/async
      done
      (reset-unfolded-token-types-storage!)
      (let [file   (setup-file-with-empty-lib)
            store  (ths/setup-store file)
            events [(dwtl/import-tokens-lib (ctob/make-tokens-lib))]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (t/is (empty? (unfolded-token-types new-state)))))))))
