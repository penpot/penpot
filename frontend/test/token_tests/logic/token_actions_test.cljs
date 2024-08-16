(ns token-tests.logic.token-actions-test
  (:require
   [app.common.pprint :refer [pprint]]
   [app.common.logging :as log]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.tokens :as wdt]
   [app.main.ui.workspace.tokens.changes :as wtch]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.main.ui.workspace.tokens.token-set :as wtts]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [token-tests.helpers.state :as tohs]
   [token-tests.helpers.tokens :as toht]))

(t/use-fixtures :each
  {:before (fn []
             ;; Ignore rxjs async errors
             (log/set-level! "app.main.data.changes" :error)
             (thp/reset-idmap!))})

(defn setup-file []
  (cthf/sample-file :file-1 :page-label :page-1))

(def border-radius-token
  {:value "12"
   :name "borderRadius.sm"
   :type :border-radius})

(def ^:private reference-border-radius-token
  {:value "{borderRadius.sm} * 2"
   :name "borderRadius.md"
   :type :border-radius})

(defn setup-file-with-tokens
  [& {:keys [rect-1 rect-2 rect-3]}]
  (-> (setup-file)
      (ctho/add-rect :rect-1 rect-1)
      (ctho/add-rect :rect-2 rect-2)
      (ctho/add-rect :rect-3 rect-3)
      (toht/add-token :token-1 border-radius-token)
      (toht/add-token :token-2 reference-border-radius-token)))

(t/deftest test-create-token
  (t/testing "creates token in new token set"
    (t/async
     done
     (let [file (setup-file)
           store (ths/setup-store file)
           events [(wdt/update-create-token border-radius-token)]]
       (tohs/run-store-async
        store done events
        (fn [new-state]
          (let [set-id (wtts/get-selected-token-set-id new-state)
                token-set (wtts/get-token-set set-id new-state)
                set-tokens (wtts/get-active-theme-sets-tokens-names-map new-state)]
            (t/testing "selects created workspace set and adds token to it"
              (t/is (some? token-set))
              (t/is (= 1 (count set-tokens)))
              (t/is (= (list border-radius-token) (->> (vals set-tokens)
                                                       (map #(dissoc % :id :modified-at)))))))))))))

(t/deftest test-create-multiple-tokens
  (t/testing "uses selected tokens set when creating multiple tokens"
    (t/async
     done
     (let [file (setup-file)
           store (ths/setup-store file)
           events [(wdt/update-create-token border-radius-token)
                   (wdt/update-create-token reference-border-radius-token)]]
       (tohs/run-store-async
        store done events
        (fn [new-state]
          (let [set-tokens (wtts/get-active-theme-sets-tokens-names-map new-state)]
            (t/testing "selects created workspace set and adds token to it"
              (t/is (= 2 (count set-tokens)))
              (t/is (= (list border-radius-token reference-border-radius-token)
                       (->> (vals set-tokens)
                            (map #(dissoc % :id :modified-at)))))))))))))

(t/deftest test-apply-token
  (t/testing "applies token to shape and updates shape attributes to resolved value"
    (t/async
     done
     (let [file (setup-file-with-tokens)
           store (ths/setup-store file)
           rect-1 (cths/get-shape file :rect-1)
           events [(wtch/apply-token {:shape-ids [(:id rect-1)]
                                      :attributes #{:rx :ry}
                                      :token (toht/get-token file :token-2)
                                      :on-update-shape wtch/update-shape-radius-all})]]
       (tohs/run-store-async
        store done events
        (fn [new-state]
          (let [file' (ths/get-file-from-store new-state)
                token-2' (toht/get-token file' :token-2)
                rect-1' (cths/get-shape file' :rect-1)]
            (t/testing "shape `:applied-tokens` got updated"
              (t/is (some? (:applied-tokens rect-1')))
              (t/is (= (:rx (:applied-tokens rect-1')) (wtt/token-identifier token-2')))
              (t/is (= (:ry (:applied-tokens rect-1')) (wtt/token-identifier token-2'))))
            (t/testing "shape radius got update to the resolved token value."
              (t/is (= (:rx rect-1') 24))
              (t/is (= (:ry rect-1') 24))))))))))

(t/deftest test-apply-multiple-tokens
  (t/testing "applying a token twice with the same attributes will override the previously applied tokens values"
    (t/async
     done
     (let [file (setup-file-with-tokens)
           store (ths/setup-store file)
           rect-1 (cths/get-shape file :rect-1)
           events [(wtch/apply-token {:shape-ids [(:id rect-1)]
                                      :attributes #{:rx :ry}
                                      :token (toht/get-token file :token-1)
                                      :on-update-shape wtch/update-shape-radius-all})
                   (wtch/apply-token {:shape-ids [(:id rect-1)]
                                      :attributes #{:rx :ry}
                                      :token (toht/get-token file :token-2)
                                      :on-update-shape wtch/update-shape-radius-all})]]
       (tohs/run-store-async
        store done events
        (fn [new-state]
          (let [file' (ths/get-file-from-store new-state)
                token-2' (toht/get-token file' :token-2)
                rect-1' (cths/get-shape file' :rect-1)]
            (t/testing "shape `:applied-tokens` got updated"
              (t/is (some? (:applied-tokens rect-1')))
              (t/is (= (:rx (:applied-tokens rect-1')) (wtt/token-identifier token-2')))
              (t/is (= (:ry (:applied-tokens rect-1')) (wtt/token-identifier token-2'))))
            (t/testing "shape radius got update to the resolved token value."
              (t/is (= (:rx rect-1') 24))
              (t/is (= (:ry rect-1') 24))))))))))

(t/deftest test-apply-token-overwrite
  (t/testing "removes old token attributes and applies only single attribute"
    (t/async
     done
     (let [file (setup-file-with-tokens)
           store (ths/setup-store file)
           rect-1 (cths/get-shape file :rect-1)
           events [;; Apply `:token-1` to all border radius attributes
                   (wtch/apply-token {:attributes #{:rx :ry :r1 :r2 :r3 :r4}
                                      :token (toht/get-token file :token-1)
                                      :shape-ids [(:id rect-1)]
                                      :on-update-shape wtch/update-shape-radius-all})
                   ;; Apply single `:r1` attribute to same shape
                   ;; while removing other attributes from the border-radius set
                   ;; but keep `:r4` for testing purposes
                   (wtch/apply-token {:attributes #{:r1}
                                      :attributes-to-remove #{:rx :ry :r1 :r2 :r3}
                                      :token (toht/get-token file :token-2)
                                      :shape-ids [(:id rect-1)]
                                      :on-update-shape wtch/update-shape-radius-all})]]
       (tohs/run-store-async
        store done events
        (fn [new-state]
          (let [file' (ths/get-file-from-store new-state)
                token-1' (toht/get-token file' :token-1)
                token-2' (toht/get-token file' :token-2)
                rect-1' (cths/get-shape file' :rect-1)]
            (t/testing "other border-radius attributes got removed"
              (t/is (nil? (:rx (:applied-tokens rect-1')))))
            (t/testing "r1 got applied with :token-2"
              (t/is (= (:r1 (:applied-tokens rect-1')) (wtt/token-identifier token-2'))))
            (t/testing "while :r4 was kept"
              (t/is (= (:r4 (:applied-tokens rect-1')) (wtt/token-identifier token-1')))))))))));)))))))))))

(t/deftest test-apply-dimensions
  (t/testing "applies dimensions token and updates the shapes width and height"
    (t/async
     done
     (let [file (-> (setup-file-with-tokens)
                    (toht/add-token :token-target {:value "100"
                                                   :name "dimensions.sm"
                                                   :type :dimensions}))
           store (ths/setup-store file)
           rect-1 (cths/get-shape file :rect-1)
           events [(wtch/apply-token {:shape-ids [(:id rect-1)]
                                      :attributes #{:width :height}
                                      :token (toht/get-token file :token-target)
                                      :on-update-shape wtch/update-shape-dimensions})]]
       (tohs/run-store-async
        store done events
        (fn [new-state]
          (let [file' (ths/get-file-from-store new-state)
                token-target' (toht/get-token file' :token-target)
                rect-1' (cths/get-shape file' :rect-1)]
            (t/testing "shape `:applied-tokens` got updated"
              (t/is (some? (:applied-tokens rect-1')))
              (t/is (= (:width (:applied-tokens rect-1')) (wtt/token-identifier token-target')))
              (t/is (= (:height (:applied-tokens rect-1')) (wtt/token-identifier token-target'))))
            (t/testing "shapes width and height got updated"
              (t/is (= (:width rect-1') 100))
              (t/is (= (:height rect-1') 100))))))))))

(t/deftest test-apply-sizing
  (t/testing "applies sizing token and updates the shapes width and height"
    (t/async
     done
     (let [file (-> (setup-file-with-tokens)
                    (toht/add-token :token-target {:value "100"
                                                   :name "sizing.sm"
                                                   :type :sizing}))
           store (ths/setup-store file)
           rect-1 (cths/get-shape file :rect-1)
           events [(wtch/apply-token {:shape-ids [(:id rect-1)]
                                      :attributes #{:width :height}
                                      :token (toht/get-token file :token-target)
                                      :on-update-shape wtch/update-shape-dimensions})]]
       (tohs/run-store-async
        store done events
        (fn [new-state]
          (let [file' (ths/get-file-from-store new-state)
                token-target' (toht/get-token file' :token-target)
                rect-1' (cths/get-shape file' :rect-1)]
            (t/testing "shape `:applied-tokens` got updated"
              (t/is (some? (:applied-tokens rect-1')))
              (t/is (= (:width (:applied-tokens rect-1')) (wtt/token-identifier token-target')))
              (t/is (= (:height (:applied-tokens rect-1')) (wtt/token-identifier token-target'))))
            (t/testing "shapes width and height got updated"
              (t/is (= (:width rect-1') 100))
              (t/is (= (:height rect-1') 100))))))))))

(t/deftest test-apply-opacity
  (t/testing "applies opacity token and updates the shapes opacity"
    (t/async
     done
     (let [file (-> (setup-file-with-tokens)
                    (toht/add-token :opacity-float {:value "0.3"
                                                    :name "opacity.float"
                                                    :type :opacity})
                    (toht/add-token :opacity-percent {:value "40%"
                                                      :name "opacity.percent"
                                                      :type :opacity})
                    (toht/add-token :opacity-invalid {:value "100"
                                                      :name "opacity.invalid"
                                                      :type :opacity}))
           store (ths/setup-store file)
           rect-1 (cths/get-shape file :rect-1)
           rect-2 (cths/get-shape file :rect-2)
           rect-3 (cths/get-shape file :rect-3)
           events [(wtch/apply-token {:shape-ids [(:id rect-1)]
                                      :attributes #{:opacity}
                                      :token (toht/get-token file :opacity-float)
                                      :on-update-shape wtch/update-opacity})
                   (wtch/apply-token {:shape-ids [(:id rect-2)]
                                      :attributes #{:opacity}
                                      :token (toht/get-token file :opacity-percent)
                                      :on-update-shape wtch/update-opacity})
                   (wtch/apply-token {:shape-ids [(:id rect-3)]
                                      :attributes #{:opacity}
                                      :token (toht/get-token file :opacity-invalid)
                                      :on-update-shape wtch/update-opacity})]]
       (tohs/run-store-async
        store done events
        (fn [new-state]
          (let [file' (ths/get-file-from-store new-state)
                rect-1' (cths/get-shape file' :rect-1)
                rect-2' (cths/get-shape file' :rect-2)
                rect-3' (cths/get-shape file' :rect-3)
                token-opacity-float (toht/get-token file' :opacity-float)
                token-opacity-percent (toht/get-token file' :opacity-percent)
                token-opacity-invalid (toht/get-token file' :opacity-invalid)]
            (t/testing "float value got translated to float and applied to opacity"
              (t/is (= (:opacity (:applied-tokens rect-1')) (wtt/token-identifier token-opacity-float)))
              (t/is (= (:opacity rect-1') 0.3)))
            (t/testing "percentage value got translated to float and applied to opacity"
              (t/is (= (:opacity (:applied-tokens rect-2')) (wtt/token-identifier token-opacity-percent)))
              (t/is (= (:opacity rect-2') 0.4)))
            (t/testing "invalid opacity value got applied but did not change shape"
              (t/is (= (:opacity (:applied-tokens rect-3')) (wtt/token-identifier token-opacity-invalid)))
              (t/is (nil? (:opacity rect-3')))))))))))

(t/deftest test-apply-rotation
  (t/testing "applies rotation token and updates the shapes rotation"
    (t/async
      done
      (let [file (-> (setup-file-with-tokens)
                     (toht/add-token :token-target {:value "120"
                                                    :name "rotation.medium"
                                                    :type :rotation}))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(wtch/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:rotation}
                                       :token (toht/get-token file :token-target)
                                       :on-update-shape wtch/update-rotation})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
                 token-target' (toht/get-token file' :token-target)
                 rect-1' (cths/get-shape file' :rect-1)]
             (t/is (some? (:applied-tokens rect-1')))
             (t/is (= (:rotation (:applied-tokens rect-1')) (wtt/token-identifier token-target')))
             (t/is (= (:rotation rect-1') 120)))))))))

(t/deftest test-apply-stroke-width
  (t/testing "applies stroke-width token and updates the shapes with stroke"
    (t/async
     done
     (let [file (-> (setup-file-with-tokens {:rect-1 {:strokes [{:stroke-alignment :inner,
                                                                 :stroke-style :solid,
                                                                 :stroke-color "#000000",
                                                                 :stroke-opacity 1,
                                                                 :stroke-width 5}]}})
                    (toht/add-token :token-target {:value "10"
                                                   :name "stroke-width.sm"
                                                   :type :stroke-width}))
           store (ths/setup-store file)
           rect-with-stroke (cths/get-shape file :rect-1)
           rect-without-stroke (cths/get-shape file :rect-2)
           events [(wtch/apply-token {:shape-ids [(:id rect-with-stroke) (:id rect-without-stroke)]
                                      :attributes #{:stroke-width}
                                      :token (toht/get-token file :token-target)
                                      :on-update-shape wtch/update-stroke-width})]]
       (tohs/run-store-async
        store done events
        (fn [new-state]
          (let [file' (ths/get-file-from-store new-state)
                token-target' (toht/get-token file' :token-target)
                rect-with-stroke' (cths/get-shape file' :rect-1)
                rect-without-stroke' (cths/get-shape file' :rect-2)]
            (t/testing "token got applied to rect with stroke and shape stroke got updated"
              (t/is (= (:stroke-width (:applied-tokens rect-with-stroke')) (wtt/token-identifier token-target')))
              (t/is (= (get-in rect-with-stroke' [:strokes 0 :stroke-width]) 10)))
            (t/testing "token got applied to rect without stroke but shape didnt get updated"
              (t/is (= (:stroke-width (:applied-tokens rect-without-stroke')) (wtt/token-identifier token-target')))
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
                                        :token-type-props {:attributes #{:rx :ry}
                                                           :on-update-shape wtch/update-shape-radius-all}
                                        :token (toht/get-token file :token-2)})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
                      token-2' (toht/get-token file' :token-2)
                      rect-1' (cths/get-shape file' :rect-1)
                      rect-2' (cths/get-shape file' :rect-2)]
             (t/is (some? (:applied-tokens rect-1')))
             (t/is (some? (:applied-tokens rect-2')))
             (t/is (= (:rx (:applied-tokens rect-1')) (wtt/token-identifier token-2')))
             (t/is (= (:rx (:applied-tokens rect-2')) (wtt/token-identifier token-2')))
             (t/is (= (:ry (:applied-tokens rect-1')) (wtt/token-identifier token-2')))
             (t/is (= (:ry (:applied-tokens rect-2')) (wtt/token-identifier token-2')))
             (t/is (= (:rx rect-1') 24))
             (t/is (= (:rx rect-2') 24)))))))))

(t/deftest test-toggle-token-mixed
  (t/testing "should unapply given token if one of the selected items has the token applied while keeping other tokens with some attributes"
    (t/async
      done
      (let [file (-> (setup-file-with-tokens)
                     (toht/apply-token-to-shape :rect-1 :token-1 #{:rx :ry})
                     (toht/apply-token-to-shape :rect-3 :token-2 #{:rx :ry}))
            store (ths/setup-store file)

            rect-with-token (cths/get-shape file :rect-1)
            rect-without-token (cths/get-shape file :rect-2)
            rect-with-other-token (cths/get-shape file :rect-3)

            events [(wtch/toggle-token {:shapes [rect-with-token rect-without-token rect-with-other-token]
                                        :token (toht/get-token file :token-1)
                                        :token-type-props {:attributes #{:rx :ry}}})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
                 rect-with-token' (cths/get-shape file' :rect-1)
                 rect-without-token' (cths/get-shape file' :rect-2)
                 rect-with-other-token' (cths/get-shape file' :rect-3)]

             (t/testing "rect-with-token got the token remove"
               (t/is (nil? (:rx (:applied-tokens rect-with-token'))))
               (t/is (nil? (:ry (:applied-tokens rect-with-token')))))

             (t/testing "rect-without-token didn't get updated"
               (t/is (= (:applied-tokens rect-without-token') (:applied-tokens rect-without-token))))

             (t/testing "rect-with-other-token didn't get updated"
               (t/is (= (:applied-tokens rect-with-other-token') (:applied-tokens rect-with-other-token)))))))))))

(t/deftest test-toggle-token-apply-to-all
  (t/testing "should apply token to all if none of the shapes has it applied"
    (t/async
      done
      (let [file (-> (setup-file-with-tokens)
                     (toht/apply-token-to-shape :rect-1 :token-2 #{:rx :ry})
                     (toht/apply-token-to-shape :rect-3 :token-2 #{:rx :ry}))
            store (ths/setup-store file)

            rect-with-other-token-1 (cths/get-shape file :rect-1)
            rect-without-token (cths/get-shape file :rect-2)
            rect-with-other-token-2 (cths/get-shape file :rect-3)

            events [(wtch/toggle-token {:shapes [rect-with-other-token-1 rect-without-token rect-with-other-token-2]
                                        :token (toht/get-token file :token-1)
                                        :token-type-props {:attributes #{:rx :ry}}})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
                 target-token (toht/get-token file' :token-1)
                 rect-with-other-token-1' (cths/get-shape file' :rect-1)
                 rect-without-token' (cths/get-shape file' :rect-2)
                 rect-with-other-token-2' (cths/get-shape file' :rect-3)]

             (t/testing "token got applied to all shapes"
               (t/is (= (:rx (:applied-tokens rect-with-other-token-1')) (wtt/token-identifier target-token)))
               (t/is (= (:rx (:applied-tokens rect-without-token')) (wtt/token-identifier target-token)))
               (t/is (= (:rx (:applied-tokens rect-with-other-token-2')) (wtt/token-identifier target-token)))

               (t/is (= (:ry (:applied-tokens rect-with-other-token-1')) (wtt/token-identifier target-token)))
               (t/is (= (:ry (:applied-tokens rect-without-token')) (wtt/token-identifier target-token)))
               (t/is (= (:ry (:applied-tokens rect-with-other-token-2')) (wtt/token-identifier target-token)))))))))))
