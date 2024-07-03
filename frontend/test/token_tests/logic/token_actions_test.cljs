(ns token-tests.logic.token-actions-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace.changes :as dch]
   [app.main.ui.workspace.tokens.core :as wtc]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})


(defn- setup-file
  []
  (let [token-id (random-uuid)]
    (-> (cthf/sample-file :file-1 :page-label :page-1)
        (ctho/add-rect :rect-1 {})
        (ctho/add-rect :rect-2 {})
        (ctho/add-rect :rect-3 {})
        (assoc-in [:data :tokens] {#uuid "91bf7f1f-fce2-482f-a423-c6b502705ff1"
                                   {:id #uuid "91bf7f1f-fce2-482f-a423-c6b502705ff1"
                                    :value "12"
                                    :name "sm"
                                    :type :border-radius}}))))

(t/deftest test-apply-token
  (t/async
   done
   (let [file (setup-file)
         store (ths/setup-store file)
         rect-1 (cths/get-shape file :rect-1)
         events [(wtc/apply-token {:shape-ids [(:id rect-1)]
                                   :attributes #{:rx :ry}
                                   :token {:id #uuid "91bf7f1f-fce2-482f-a423-c6b502705ff1"}
                                   :on-update-shape wtc/update-shape-radius})]]

     (ths/run-store
      store done events
      (fn [new-state]
        (let [file' (ths/get-file-from-store new-state)
              rect-1' (cths/get-shape file' :rect-1)]
          (t/is (some? (:applied-tokens rect-1')))
          (t/is (= (:rx (:applied-tokens rect-1')) #uuid "91bf7f1f-fce2-482f-a423-c6b502705ff1"))
          (t/is (= (:rx rect-1') 12))))

      (fn [stream]
        (->> stream
             ;; (rx/tap #(prn (ptk/type %)))
             (rx/filter #(ptk/type? :app.main.data.workspace.changes/send-update-indices %))))))))


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
