;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.object-update
  "A task used for perform simple object properties update
  in an asynchronous flow."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.db :as db]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(defn- update-object
  [{:keys [::db/conn] :as cfg} {:keys [id object key val] :as props}]
  (l/trc :hint "update object prop"
         :id (str id)
         :object (d/name object)
         :key (d/name key)
         :val val)
  (db/update! conn object {key val} {:id id} {::db/return-keys false}))

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req [::db/pool]))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as params}]
    (db/tx-run! cfg update-object props)))
