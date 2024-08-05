(ns token-tests.logic.token-actions-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.main.ui.workspace.tokens.changes :as wtch]
   [app.main.ui.workspace.tokens.core :as wtc]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [token-tests.helpers.state :as tohs]
   [token-tests.helpers.tokens :as toht]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(defn setup-file
  [& {:keys [rect-1 rect-2 rect-3]}]
  (-> (cthf/sample-file :file-1 :page-label :page-1)
      (ctho/add-rect :rect-1 rect-1)
      (ctho/add-rect :rect-2 rect-2)
      (ctho/add-rect :rect-3 rect-3)
      (toht/add-token :token-1 {:value "12"
                                :name "borderRadius.sm"
                                :type :border-radius})
      (toht/add-token :token-2 {:value "{borderRadius.sm} * 2"
                                :name "borderRadius.md"
                                :type :border-radius})))

(t/deftest test-apply-token
  (t/testing "applying a token twice with the same attributes will override the previous applied token"
    (t/async
      done
      (let [file (setup-file)
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
             (t/is (some? (:applied-tokens rect-1')))
             (t/is (= (:rx (:applied-tokens rect-1')) (:id token-2')))
             (t/is (= (:ry (:applied-tokens rect-1')) (:id token-2')))
             (t/is (= (:rx rect-1') 24))
             (t/is (= (:ry rect-1') 24)))))))))

(t/deftest test-apply-token-overwrite
  (t/testing "removes old token attributes and applies only single attribute"
    (t/async
     done
     (let [file (setup-file)
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
              (t/is (= (:r1 (:applied-tokens rect-1')) (:id token-2'))))
            (t/testing "while :r4 was kept"
              (t/is (= (:r4 (:applied-tokens rect-1')) (:id token-1')))))))))));)))))))))))

(t/deftest test-apply-border-radius
  (t/testing "applies radius token and updates the shapes radius"
    (t/async
      done
      (let [file (setup-file)
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
             (t/is (some? (:applied-tokens rect-1')))
             (t/is (= (:rx (:applied-tokens rect-1')) (:id token-2')))
             (t/is (= (:ry (:applied-tokens rect-1')) (:id token-2')))
             (t/is (= (:rx rect-1') 24))
             (t/is (= (:ry rect-1') 24)))))))))

(t/deftest test-apply-dimensions
  (t/testing "applies dimensions token and updates the shapes width and height"
    (t/async
      done
      (let [file (-> (setup-file)
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
             (t/is (some? (:applied-tokens rect-1')))
             (t/is (= (:width (:applied-tokens rect-1')) (:id token-target')))
             (t/is (= (:height (:applied-tokens rect-1')) (:id token-target')))
             (t/is (= (:width rect-1') 100))
             (t/is (= (:height rect-1') 100)))))))))

(t/deftest test-apply-sizing
  (t/testing "applies sizing token and updates the shapes width and height"
    (t/async
     done
     (let [file (-> (setup-file)
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
            (t/is (some? (:applied-tokens rect-1')))
            (t/is (= (:width (:applied-tokens rect-1')) (:id token-target')))
            (t/is (= (:height (:applied-tokens rect-1')) (:id token-target')))
            (t/is (= (:width rect-1') 100))
            (t/is (= (:height rect-1') 100)))))))))

(t/deftest test-apply-opacity
  (t/testing "applies opacity token and updates the shapes opacity"
    (t/async
      done
      (let [file (-> (setup-file)
                     (toht/add-token :token-target {:value "0.5"
                                                    :name "opacity.medium"
                                                    :type :opacity}))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            events [(wtch/apply-token {:shape-ids [(:id rect-1)]
                                       :attributes #{:opacity}
                                       :token (toht/get-token file :token-target)
                                       :on-update-shape wtch/update-opacity})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
                 token-target' (toht/get-token file' :token-target)
                 rect-1' (cths/get-shape file' :rect-1)]
             (t/is (some? (:applied-tokens rect-1')))
             (t/is (= (:opacity (:applied-tokens rect-1')) (:id token-target')))
             ;; TODO Fix opacity shape update not working?
             #_(t/is (= (:opacity rect-1') 0.5)))))))))

(t/deftest test-apply-rotation
  (t/testing "applies rotation token and updates the shapes rotation"
    (t/async
      done
      (let [file (-> (setup-file)
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
             (t/is (= (:rotation (:applied-tokens rect-1')) (:id token-target')))
             (t/is (= (:rotation rect-1') 120)))))))))

(t/deftest test-toggle-token-none
  (t/testing "should apply token to all selected items, where no item has the token applied"
    (t/async
      done
      (let [file (setup-file)
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
             (t/is (= (:rx (:applied-tokens rect-1')) (:id token-2')))
             (t/is (= (:rx (:applied-tokens rect-2')) (:id token-2')))
             (t/is (= (:ry (:applied-tokens rect-1')) (:id token-2')))
             (t/is (= (:ry (:applied-tokens rect-2')) (:id token-2')))
             (t/is (= (:rx rect-1') 24))
             (t/is (= (:rx rect-2') 24)))))))))

(t/deftest test-toggle-token-mixed
  (t/testing "should unapply given token if one of the selected items has the token applied while keeping other tokens with some attributes"
    (t/async
      done
      (let [file (-> (setup-file)
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
      (let [file (-> (setup-file)
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
               (t/is (= (:rx (:applied-tokens rect-with-other-token-1')) (:id target-token)))
               (t/is (= (:rx (:applied-tokens rect-without-token')) (:id target-token)))
               (t/is (= (:rx (:applied-tokens rect-with-other-token-2')) (:id target-token)))

               (t/is (= (:ry (:applied-tokens rect-with-other-token-1')) (:id target-token)))
               (t/is (= (:ry (:applied-tokens rect-without-token')) (:id target-token)))
               (t/is (= (:ry (:applied-tokens rect-with-other-token-2')) (:id target-token)))))))))))
