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
   [token-tests.helpers.state :as tohs]))

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
  (t/async
   done
   (let [file (setup-file)
         store (ths/setup-store file)
         rect-1 (cths/get-shape file :rect-1)
         events [(wtc/apply-token {:shape-ids [(:id rect-1)]
                                   :attributes #{:rx :ry}
                                   :token (get-token file :token-1)
                                   :on-update-shape wtc/update-shape-radius})
                 ;; Will override
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

(comment
  (t/run-tests)
  (defn make-printable
    "Convert records that are not printable by cider inspect into regular maps."
    [coll]
    (letfn [(stringifyable? [x]
              (not (or (map? x)
                       (sequential? x)
                       (keyword? x)
                       (number? x)
                       (uuid? x))))]
      (clojure.walk/postwalk #(cond->> %
                                (record? %) (into {})
                                (stringifyable? %) str)
                             coll)))

  (-> (cthf/sample-file :file-1)
      (assoc :tokens {})
      (make-printable))

 (make-printable (setup-file))
 nil)
