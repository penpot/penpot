(ns frontend-tests.tokens.logic.token-actions-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.tokens-lib :as ctob]
   [app.main.ui.workspace.tokens.changes :as wtch]
   [cljs.test :as t :include-macros true]
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
      (assoc-in [:data :tokens-lib]
                (-> (ctob/make-tokens-lib)
                    (ctob/add-theme (ctob/make-token-theme :name "Theme A" :sets #{"Set A"}))
                    (ctob/set-active-themes #{"/Theme A"})
                    (ctob/add-set (ctob/make-token-set :name "Set A"))
                    (ctob/add-token-in-set "Set A" (ctob/make-token border-radius-token))
                    (ctob/add-token-in-set "Set A" (ctob/make-token reference-border-radius-token))))))

(t/deftest test-apply-token
  (t/testing "applies token to shape and updates shape attributes to resolved value"
    (t/async
      done
      (let [file (setup-file-with-tokens)
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(wtch/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:r1 :r2 :r3 :r4}
                                       :token (toht/get-token file "borderRadius.md")
                                       :on-update-shape wtch/update-shape-radius-all})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
                 token (toht/get-token file' "borderRadius.md")
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
            events [(wtch/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:r1 :r2 :r3 :r4}
                                       :token (toht/get-token file "borderRadius.sm")
                                       :on-update-shape wtch/update-shape-radius-all})
                    (wtch/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:r1 :r2 :r3 :r4}
                                       :token (toht/get-token file "borderRadius.md")
                                       :on-update-shape wtch/update-shape-radius-all})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
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
                    (wtch/apply-token {:attributes #{:r1 :r2 :r3 :r4}
                                       :token (toht/get-token file "borderRadius.sm")
                                       :shape-ids [(:id rect-1)]
                                       :on-update-shape wtch/update-shape-radius-all})
                   ;; Apply single `:r1` attribute to same shape
                   ;; while removing other attributes from the border-radius set
                   ;; but keep `:r4` for testing purposes
                    (wtch/apply-token {:attributes #{:r1 :r2 :r3}
                                       :token (toht/get-token file "borderRadius.md")
                                       :shape-ids [(:id rect-1)]
                                       :on-update-shape wtch/update-shape-radius-all})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
                 token-sm (toht/get-token file' "borderRadius.sm")
                 token-md (toht/get-token file' "borderRadius.md")
                 rect-1' (cths/get-shape file' :rect-1)]
             (t/testing "r1 got applied with borderRadius.md"
               (t/is (= (:r1 (:applied-tokens rect-1')) (:name token-md))))
             (t/testing "while :r4 was kept with borderRadius.sm"
               (t/is (= (:r4 (:applied-tokens rect-1')) (:name token-sm)))))))))))

(t/deftest test-apply-dimensions
  (t/testing "applies dimensions token and updates the shapes width and height"
    (t/async
      done
      (let [dimensions-token {:name "dimensions.sm"
                              :value "100"
                              :type :dimensions}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token-in-set % "Set A" (ctob/make-token dimensions-token))))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(wtch/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:width :height}
                                       :token (toht/get-token file "dimensions.sm")
                                       :on-update-shape wtch/update-shape-dimensions})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
                 token-target' (toht/get-token file' "dimensions.sm")
                 rect-1' (cths/get-shape file' :rect-1)]
             (t/testing "shape `:applied-tokens` got updated"
               (t/is (some? (:applied-tokens rect-1')))
               (t/is (= (:width (:applied-tokens rect-1')) (:name token-target')))
               (t/is (= (:height (:applied-tokens rect-1')) (:name token-target'))))
             (t/testing "shapes width and height got updated"
               (t/is (= (:width rect-1') 100))
               (t/is (= (:height rect-1') 100))))))))))

(t/deftest test-apply-sizing
  (t/testing "applies sizing token and updates the shapes width and height"
    (t/async
      done
      (let [sizing-token {:name "sizing.sm"
                          :value "100"
                          :type :sizing}
            file (-> (setup-file-with-tokens)
                     (update-in [:data :tokens-lib]
                                #(ctob/add-token-in-set % "Set A" (ctob/make-token sizing-token))))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(wtch/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:width :height}
                                       :token (toht/get-token file "sizing.sm")
                                       :on-update-shape wtch/update-shape-dimensions})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
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
                                     (ctob/add-token-in-set "Set A" (ctob/make-token opacity-float))
                                     (ctob/add-token-in-set "Set A" (ctob/make-token opacity-percent))
                                     (ctob/add-token-in-set "Set A" (ctob/make-token opacity-invalid)))))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            rect-2 (cths/get-shape file :rect-2)
            rect-3 (cths/get-shape file :rect-3)
            events [(wtch/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:opacity}
                                       :token (toht/get-token file "opacity.float")
                                       :on-update-shape wtch/update-opacity})
                    (wtch/apply-token {:shape-ids [(:id rect-2)]
                                       :attributes #{:opacity}
                                       :token (toht/get-token file "opacity.percent")
                                       :on-update-shape wtch/update-opacity})
                    (wtch/apply-token {:shape-ids [(:id rect-3)]
                                       :attributes #{:opacity}
                                       :token (toht/get-token file "opacity.invalid")
                                       :on-update-shape wtch/update-opacity})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
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
                                #(ctob/add-token-in-set % "Set A" (ctob/make-token rotation-token))))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(wtch/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:rotation}
                                       :token (toht/get-token file "rotation.medium")
                                       :on-update-shape wtch/update-rotation})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
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
                                #(ctob/add-token-in-set % "Set A" (ctob/make-token stroke-width-token))))
            store (ths/setup-store file)
            rect-with-stroke (cths/get-shape file :rect-1)
            rect-without-stroke (cths/get-shape file :rect-2)
            events [(wtch/apply-token {:shape-ids [(:id rect-with-stroke) (:id rect-without-stroke)]
                                       :attributes #{:stroke-width}
                                       :token (toht/get-token file "stroke-width.sm")
                                       :on-update-shape wtch/update-stroke-width})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
                 token-target' (toht/get-token file' "stroke-width.sm")
                 rect-with-stroke' (cths/get-shape file' :rect-1)
                 rect-without-stroke' (cths/get-shape file' :rect-2)]
             (t/testing "token got applied to rect with stroke and shape stroke got updated"
               (t/is (= (:stroke-width (:applied-tokens rect-with-stroke')) (:name token-target')))
               (t/is (= (get-in rect-with-stroke' [:strokes 0 :stroke-width]) 10)))
             (t/testing "token got applied to rect without stroke but shape didnt get updated"
               (t/is (= (:stroke-width (:applied-tokens rect-without-stroke')) (:name token-target')))
               (t/is (empty? (:strokes rect-without-stroke')))))))))))

(t/deftest test-toggle-token-none
  (t/testing "should apply token to all selected items, where no item has the token applied"
    (t/async
      done
      (let [file (setup-file-with-tokens)
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            rect-2 (cths/get-shape file :rect-2)
            events [(wtch/toggle-token {:shapes [rect-1 rect-2]
                                        :token-type-props {:attributes #{:r1 :r2 :r3 :r4}
                                                           :on-update-shape wtch/update-shape-radius-all}
                                        :token (toht/get-token file "borderRadius.md")})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
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

            events [(wtch/toggle-token {:shapes [rect-with-token rect-without-token rect-with-other-token]
                                        :token (toht/get-token file "borderRadius.sm")
                                        :token-type-props {:attributes #{:r1 :r2 :r3 :r4}}})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
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

            events [(wtch/toggle-token {:shapes [rect-with-other-token-1 rect-without-token rect-with-other-token-2]
                                        :token (toht/get-token file "borderRadius.sm")
                                        :token-type-props {:attributes #{:r1 :r2 :r3 :r4}}})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
                 target-token (toht/get-token file' "borderRadius.sm")
                 rect-with-other-token-1' (cths/get-shape file' :rect-1)
                 rect-without-token' (cths/get-shape file' :rect-2)
                 rect-with-other-token-2' (cths/get-shape file' :rect-3)]

             (t/testing "token got applied to all shapes"
               (t/is (= (:r1 (:applied-tokens rect-with-other-token-1')) (:name target-token)))
               (t/is (= (:r1 (:applied-tokens rect-without-token')) (:name target-token)))
               (t/is (= (:r1 (:applied-tokens rect-with-other-token-2')) (:name target-token)))))))))))
