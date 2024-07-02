(ns token-tests.logic.token-actions-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.main.ui.workspace.tokens.core :as wtc]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(defn- setup-file
  []
  (-> (cthf/sample-file :file-1)
      (ctho/add-rect :file-1 :rect-1 {})
      (ctho/add-rect :file-1 :rect-2 {})
      (ctho/add-rect :file-1 :rect-3 {})))

(t/deftest test-update-shape
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file)
          store    (ths/setup-store file)

          ;; ==== Action
          events [(wtc/on-add-token {:token-type-props {:attributes {:rx :ry}
                                                        :on-update-shape #(fn [& _])}
                                     :token {:id (random-uuid)}})]]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               shape1' (get-in new-state [:workspace-data
                                          :pages-index
                                          (cthi/id :page1)
                                          :objects
                                          (cthi/id :shape1)])
               fills'      (:fills shape1')
               fill'       (first fills')]

          ;; ==== Check
           (t/is (some? shape1'))
           (t/is (= (count fills') 1))
           (t/is (= (:fill-color fill') "#fabada"))
           (t/is (= (:fill-opacity fill') 1))))))))



(comment
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
