;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.helpers.state
  (:require
   [app.common.pprint :refer [pprint]]
   [app.common.schema :as sm]
   [app.common.test-helpers.files :as cthf]
   [app.main.data.workspace.layout :as layout]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def ^private initial-state
  {:workspace-layout layout/default-layout
   :workspace-global layout/default-global
   :current-file-id nil
   :current-page-id nil
   :features-team #{"components/v2"}})

(defn- on-error
  [cause]
  (js/console.log "STORE ERROR" (.-stack cause))
  (when-let [data (some-> cause ex-data ::sm/explain)]
    (pprint (sm/humanize-explain data))))

(defn setup-store
  [file]
  (let [state (-> initial-state
                  (assoc :current-file-id (:id file)
                         :current-page-id (cthf/current-page-id file)
                         :permissions {:can-edit true}
                         :files {(:id file) file}))
        store (ptk/store {:state state :on-error on-error})]
    store))

(defn run-store
  ([store done events completed-cb]
   (run-store store done events completed-cb nil))
  ([store done events completed-cb stopper]
   (let [stream    (ptk/input-stream store)
         stopper-s (if (fn? stopper)
                     (stopper stream)
                     (rx/filter #(= :the/end %) stream))]

     (->> stream
          (rx/take-until stopper-s)
          (rx/last)
          (rx/tap (fn [_]
                    (completed-cb @store)))
          (rx/subs! (fn [_] (done))
                    (fn [cause]
                      (js/console.log "[error]:" cause))
                    (fn [_]
                      #_(js/console.debug "[complete]"))))

     (doseq [event events]
       (ptk/emit! store event))

     (ptk/emit! store :the/end))))

(defn get-file-from-state
  [state]
  (let [file-id (:current-file-id state)]
    (get-in state [:files file-id])))
