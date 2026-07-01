;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.files-actions
  (:require
   [app.common.schema :as sm]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.util.blob :as blob]
   [app.util.services :as sv]))

;; --- SCHEMA

(def ^:private schema:get-file-actions
  [:map {:title "get-file-actions"}
   [:file-id ::sm/uuid]
   [:limit {:optional true} ::sm/int]])

;; --- COMMAND QUERY: get-file-actions

(sv/defmethod ::get-file-actions
  "Retrieve recent file changes from all users for display in the
   History panel's ACTIONS tab. Returns a limited set of change
   records with profile attribution and decoded changes."
  {::doc/added "2.17"
   ::sm/params schema:get-file-actions}
  [cfg {:keys [::rpc/profile-id file-id] :as params}]
  (let [limit (or (:limit params) 50)]
    (db/run! cfg (fn [{:keys [::db/conn]}]
                   (files/check-read-permissions! conn profile-id file-id)

                   (let [sql (str "SELECT id, profile_id, created_at, revn, changes "
                                   "FROM file_change "
                                   "WHERE file_id = ?::uuid "
                                   "  AND profile_id IS NOT NULL "
                                   "  AND changes IS NOT NULL "
                                   "ORDER BY created_at DESC "
                                   "LIMIT ?")]
                     (->> (db/exec! conn [sql file-id (int limit)])
                          (mapv (fn [row]
                                  {:id         (:id row)
                                   :profile-id (:profile-id row)
                                   :created-at (:created-at row)
                                   :revn       (:revn row)
                                   :changes    (blob/decode (:changes row))}))))))))
