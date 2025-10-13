;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.tokens.logic.token-actions-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.text :as txt]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.application :as dwta]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.tokens.helpers.state :as tohs]
   [frontend-tests.tokens.helpers.tokens :as toht]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

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
  (-> (setup-file)
      (ctho/add-rect :rect-1 rect-1)
      (ctho/add-rect :rect-2 rect-2)
      (ctho/add-rect :rect-3 rect-3)
      (ctho/add-text :text-1 "Hello World!")
      (assoc-in [:data :tokens-lib]
                (-> (ctob/make-tokens-lib)
                    (ctob/add-theme (ctob/make-token-theme :name "Theme A" :sets #{"Set A"}))
                    (ctob/set-active-themes #{"/Theme A"})
                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set-a)
                                                       :name "Set A"))
                    (ctob/add-token (cthi/id :set-a)
                                    (ctob/make-token border-radius-token))
                    (ctob/add-token (cthi/id :set-a)
                                    (ctob/make-token reference-border-radius-token))))))

(t/deftest test-apply-token
  (t/testing "applies token to shape and updates shape attributes to resolved value"
    (t/async
      done
      (let [file   (setup-file-with-tokens)
            store  (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(dwta/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:r1 :r2 :r3 :r4}
                                       :token (toht/get-token file "borderRadius.md")
                                       :on-update-shape dwta/update-shape-radius-all})]]
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
                                       :on-update-shape dwta/update-shape-radius-all})
                    (dwta/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:r1 :r2 :r3 :r4}
                                       :token (toht/get-token file "borderRadius.md")
                                       :on-update-shape dwta/update-shape-radius-all})]]
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
                                       :on-update-shape dwta/update-shape-radius-all})
                   ;; Apply single `:r1` attribute to same shape
                   ;; while removing other attributes from the border-radius set
                   ;; but keep `:r4` for testing purposes
                    (dwta/apply-token {:attributes #{:r1 :r2 :r3}
                                       :token (toht/get-token file "borderRadius.md")
                                       :shape-ids [(:id rect-1)]
                                       :on-update-shape dwta/update-shape-radius-all})]]
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
                                       :on-update-shape dwta/update-shape-radius-all})]]
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
                                       :on-update-shape dwta/update-shape-dimensions})]]
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
                                       :on-update-shape dwta/update-shape-dimensions})]]
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
                                       :attributes #{:stroke-width}
                                       :token (toht/get-token file "stroke-width.sm")
                                       :on-update-shape dwta/update-stroke-width})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 token-target' (toht/get-token file' "stroke-width.sm")
                 rect-with-stroke' (cths/get-shape file' :rect-1)
                 rect-without-stroke' (cths/get-shape file' :rect-2)]
             (t/testing "token got applied to rect with stroke and shape stroke got updated"
               (t/is (= (:stroke-width (:applied-tokens rect-with-stroke')) (:name token-target')))
               (t/is (= (get-in rect-with-stroke' [:strokes 0 :stroke-width]) 10)))
             (t/testing "token got applied to rect without stroke and shape stroke got updated"
               (t/is (= (:stroke-width (:applied-tokens rect-without-stroke')) (:name token-target')))
               (t/is (= (get-in rect-without-stroke' [:strokes 0 :stroke-width]) 10))))))))))

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
             (t/is (= (:font-size style-text-blocks) "24")))))))))

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
             (t/is (= (:line-height style-text-blocks) 1.5)))))))))

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
             (t/is (= (:letter-spacing style-text-blocks) "2")))))))))

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
             (t/is (= (:font-family style-text-blocks) (:font-id txt/default-text-attrs))))))))))

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
             (t/is (= (:font-weight style-text-blocks) "400")))))))))

(t/deftest test-toggle-token-none
  (t/testing "should apply token to all selected items, where no item has the token applied"
    (t/async
      done
      (let [file (setup-file-with-tokens)
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            rect-2 (cths/get-shape file :rect-2)
            events [(dwta/toggle-token {:shapes [rect-1 rect-2]
                                        :token-type-props {:attributes #{:r1 :r2 :r3 :r4}
                                                           :on-update-shape dwta/update-shape-radius-all}
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

            events [(dwta/toggle-token {:shapes [rect-with-token rect-without-token rect-with-other-token]
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

            events [(dwta/toggle-token {:shapes [rect-with-other-token-1 rect-without-token rect-with-other-token-2]
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
                                        :shapes [frame-layout rect-in-layout rect-regular]})]]
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
             (t/is (= (:text-decoration style-text-blocks) "underline")))))))))

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
             (t/is (= (:font-family style-text-blocks) "Arial")))))))))

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
             (t/is (nil? (:typography-ref-file text-node-3))))))))))
