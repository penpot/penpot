(ns token-tests.logic.token-actions-test
  (:require
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.main.ui.workspace.tokens.core :as wtc]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [token-tests.helpers.state :as tohs]
   [app.main.ui.workspace.tokens.token :as wtt]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(defn add-token [state label params]
  (let [id (thi/new-id! label)
        token (assoc params :id id)]
    (update-in state [:data :tokens] assoc id token)))

(defn get-token [file label]
  (let [id (thi/id label)]
    (get-in file [:data :tokens id])))

(def radius-token
  {:value "12"
   :name "sm"
   :type :border-radius})

(def radius-ref-token
  {:value "{sm} * 2"
   :name "md"
   :type :border-radius})

(def test-tokens
  {(:id radius-token) radius-token
   (:id radius-ref-token) radius-ref-token})

(defn apply-token-to-shape [file shape-label token-label attributes]
  (let [first-page-id (get-in file [:data :pages 0])
        shape-id (thi/id shape-label)
        token-id (thi/id token-label)
        applied-attributes (wtt/attributes-map attributes token-id)]
    (update-in file [:data
                     :pages-index first-page-id
                     :objects shape-id
                     :applied-tokens]
               merge applied-attributes)))

(defn- setup-file
  []
  (-> (cthf/sample-file :file-1 :page-label :page-1)
      (ctho/add-rect :rect-1 {})
      (ctho/add-rect :rect-2 {})
      (ctho/add-rect :rect-3 {})
      (add-token :token-1 {:value "12"
                           :name "borderRadius.sm"
                           :type :border-radius})
      (add-token :token-2 {:value "{borderRadius.sm} * 2"
                           :name "borderRadius.md"
                           :type :border-radius})))

(t/deftest test-apply-token
  (t/testing "applying a token twice with the same attributes will override")
  (t/async
    done
    (let [file (setup-file)
          store (ths/setup-store file)
          rect-1 (cths/get-shape file :rect-1)
          events [(wtc/apply-token {:shape-ids [(:id rect-1)]
                                    :attributes #{:rx :ry}
                                    :token (get-token file :token-1)
                                    :on-update-shape wtc/update-shape-radius})
                  (wtc/apply-token {:shape-ids [(:id rect-1)]
                                    :attributes #{:rx :ry}
                                    :token (get-token file :token-2)
                                    :on-update-shape wtc/update-shape-radius})]]
      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file' (ths/get-file-from-store new-state)
               token-2' (get-token file' :token-2)
               rect-1' (cths/get-shape file' :rect-1)]
           (t/is (some? (:applied-tokens rect-1')))
           (t/is (= (:rx (:applied-tokens rect-1')) (:id token-2')))
           (t/is (= (:ry (:applied-tokens rect-1')) (:id token-2')))
           (t/is (= (:rx rect-1') 24))
           (t/is (= (:ry rect-1') 24))))))))

(t/deftest test-apply-border-radius
  (t/testing "applies radius token and updates the shapes radius")
  (t/async
    done
    (let [file (setup-file)
          store (ths/setup-store file)
          rect-1 (cths/get-shape file :rect-1)
          events [(wtc/apply-token {:shape-ids [(:id rect-1)]
                                    :attributes #{:rx :ry}
                                    :token (get-token file :token-2)
                                    :on-update-shape wtc/update-shape-radius})]]
      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file' (ths/get-file-from-store new-state)
               token-2' (get-token file' :token-2)
               rect-1' (cths/get-shape file' :rect-1)]
           (t/is (some? (:applied-tokens rect-1')))
           (t/is (= (:rx (:applied-tokens rect-1')) (:id token-2')))
           (t/is (= (:ry (:applied-tokens rect-1')) (:id token-2')))
           (t/is (= (:rx rect-1') 24))
           (t/is (= (:ry rect-1') 24))))))))

(t/deftest test-apply-dimensions
  (t/testing "applies radius token and updates the shapes radius")
  (t/async
    done
    (let [file (-> (setup-file)
                   (add-token :token-target {:value "100"
                                             :name "dimensions.sm"
                                             :type :dimensions}))
          store (ths/setup-store file)
          rect-1 (cths/get-shape file :rect-1)
          events [(wtc/apply-token {:shape-ids [(:id rect-1)]
                                    :attributes #{:width :height}
                                    :token (get-token file :token-target)
                                    :on-update-shape wtc/update-shape-dimensions})]]
      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file' (ths/get-file-from-store new-state)
               token-target' (get-token file' :token-target)
               rect-1' (cths/get-shape file' :rect-1)]
           (t/is (some? (:applied-tokens rect-1')))
           (t/is (= (:width (:applied-tokens rect-1')) (:id token-target')))
           (t/is (= (:height (:applied-tokens rect-1')) (:id token-target')))
           (t/is (= (:width rect-1') 100))
           (t/is (= (:height rect-1') 100))))))))

(t/deftest test-toggle-token-none
  (t/testing "should apply token to all selected items, where no item has the token applied"
    (t/async
      done
      (let [file (setup-file)
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            rect-2 (cths/get-shape file :rect-2)
            events [(wtc/toggle-token {:shapes [rect-1 rect-2]
                                       :token-type-props {:attributes #{:rx :ry}
                                                          :on-update-shape wtc/update-shape-radius}
                                       :token (get-token file :token-2)})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
                      token-2' (get-token file' :token-2)
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
  (t/testing "should unapply token if one of the selected items has the token applied"
    (t/async
      done
      (let [file (-> (setup-file)
                     (apply-token-to-shape :rect-1 :token-1 #{:rx :ry}))
            store (ths/setup-store file)
            rect-1 (cths/get-shape file :rect-1)
            rect-2 (cths/get-shape file :rect-2)
            events [(wtc/toggle-token {:shapes [rect-1 rect-2]
                                       :token-type-props {:attributes #{:rx :ry}}})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-store new-state)
                      token-2' (get-token file' :token-2)
                      rect-1' (cths/get-shape file' :rect-1)
                      rect-2' (cths/get-shape file' :rect-2)]
             (t/is (nil? (:rx (:applied-tokens rect-1'))))
             (t/is (nil? (:ry (:applied-tokens rect-1'))))
             (t/is (nil? (:rx (:applied-tokens rect-2'))))
             (t/is (nil? (:ry (:applied-tokens rect-2'))))
             ;; Verify that shape attributes didn't get changed
             (t/is (zero? (:rx rect-1')))
             (t/is (zero? (:rx rect-2'))))))))))
