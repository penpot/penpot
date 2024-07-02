(ns token-tests.logic.token-actions-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.selection :as dws]
   [app.main.ui.workspace.tokens.core :as wtc]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})


(defn- setup-file
  []
  (let [token-id (random-uuid)]
    (-> (cthf/sample-file :file-1 :page-label :page-1)
        (ctho/add-rect :rect-1 {})
        (ctho/add-rect :rect-2 {})
        (ctho/add-rect :rect-3 {})
        (assoc-in [:data :tokens] {token-id {:id token-id
                                             :value "12"
                                             :name "sm"
                                             :type :border-radius}}))))

(t/deftest test-update-shape
  (t/async
    done
    (let [;; ==== Setup
          file (setup-file)
          store (ths/setup-store file)
          rect-1 (cths/get-shape file :rect-1)

          ;; ==== Action
          events [(dws/select-shape (:id rect-1))
                  (dch/update-shapes [(:id rect-1)] (fn [shape] (assoc shape :applied-tokens {:rx (random-uuid)})))
                  #_(wtc/on-add-token {:token-type-props {:attributes {:rx :ry}
                                                          :on-update-shape #(fn [& _])}
                                       :shape-ids [(:id rect-1)]
                                       :token {:id (random-uuid)}})]]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [file' (ths/get-file-from-store new-state)
               page' (cthf/current-page file')
               rect-1' (cths/get-shape file' :rect-1)
               ;; ==== Get
               #_#_rect-1' (get-in new-state [:workspace-data
                                              :pages-index
                                              (cthi/id :page-1)
                                              :objects
                                              (cthi/id :rect-1)])]

           ;; ==== Check
           (t/is (some? (:applied-tokens rect-1')))))))))




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
