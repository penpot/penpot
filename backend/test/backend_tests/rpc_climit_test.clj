;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-climit-test
  (:require
   [app.rpc.climit :as-alias climit]
   [app.util.services :as-alias sv]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)

(defn- method-limit-declarations
  "The `::climit/id` declarations of every RPC command, as the running system
  wraps them."
  []
  (->> (:app.rpc/methods th/*system*)
       (vals)
       (map first)
       (keep (fn [mdata]
               (when-let [limits (::climit/id mdata)]
                 [mdata limits])))))

(t/deftest commands-declare-the-by-profile-limit-before-the-global-one
  ;; `climit/get-limits` reverses a declared vector of pairs, so the first one
  ;; declared is acquired first, and the nested `climit/invoke!` chains read the
  ;; same declaration. A pair declared global first puts a command back in the
  ;; inverted order that parked two paths of one profile for good.
  (let [declarations (method-limit-declarations)]

    ;; the system wraps every command, so a short list means the scan went stale
    (t/is (<= 10 (count declarations)))

    (doseq [[mdata limits] declarations
            :when (and (vector? limits) (vector? (first limits)))]
      (let [ids   (mapv first limits)
            index (into {} (map-indexed (fn [idx id] [id idx]) ids))
            where (str (::sv/name mdata))]

        (t/is (= (count ids) (count (distinct ids)))
              (str where " declares the same limit twice"))

        (doseq [[id idx] index
                :when (= "by-profile" (name id))]
          (when-let [gidx (get index (keyword (namespace id) "global"))]
            (t/is (< idx gidx)
                  (str where " must declare " id " before "
                       (keyword (namespace id) "global")))))))))
