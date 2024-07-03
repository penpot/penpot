(ns token-tests.logic.token-actions-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace.changes :as dch]
   [app.main.ui.workspace.tokens.core :as wtc]
   [beicon.v2.core :as rx]
   [token-tests.helpers.state :as tohs]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})


(def radius-token
  {:id #uuid "91bf7f1f-fce2-482f-a423-c6b502705ff1"
   :value "12"
   :name "sm"
   :type :border-radius})

(def radius-ref-token
  {:id #uuid "4c2bf84d-3a98-47a2-8e3c-e7fb037a615c"
   :value "{sm} * 2"
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
      (assoc-in [:data :tokens] test-tokens)))

(t/deftest test-apply-token
  (t/async
   done
   (let [file (setup-file)
         store (ths/setup-store file)
         rect-1 (cths/get-shape file :rect-1)
         events [(wtc/apply-token {:shape-ids [(:id rect-1)]
                                   :attributes #{:rx :ry}
                                   :token radius-token
                                   :on-update-shape wtc/update-shape-radius})
                 ;; Will override
                 (wtc/apply-token {:shape-ids [(:id rect-1)]
                                   :attributes #{:rx :ry}
                                   :token radius-ref-token
                                   :on-update-shape wtc/update-shape-radius})]]
     (tohs/run-store-async
      store done events
      (fn [new-state]
        (let [file' (ths/get-file-from-store new-state)
              rect-1' (cths/get-shape file' :rect-1)]
          (t/is (some? (:applied-tokens rect-1')))
          (t/is (= (:rx (:applied-tokens rect-1')) (:id radius-ref-token)))
          (t/is (= (:ry (:applied-tokens rect-1')) (:id radius-ref-token)))
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
