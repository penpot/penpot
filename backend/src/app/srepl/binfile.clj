;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.binfile
  (:require
   [app.binfile.v2 :as binfile.v2]
   [app.db :as db]
   [app.main :as main]
   [app.srepl.helpers :as h]
   [cuerdas.core :as str]))

(defn export-team!
  [team-id]
  (let [team-id (h/parse-uuid team-id)]
    (binfile.v2/export-team! main/system team-id)))

(defn import-team!
  [path & {:keys [owner rollback?] :or {rollback? true}}]
  (db/tx-run! (assoc main/system ::db/rollback rollback?)
              (fn [cfg]
                (let [team  (binfile.v2/import-team! cfg path)
                      owner (cond
                              (string? owner)
                              (db/get* cfg :profile {:email (str/lower owner)})
                              (uuid? owner)
                              (db/get* cfg :profile {:id owner}))]

                  (when owner
                    (db/insert! cfg :team-profile-rel
                                {:team-id (:id team)
                                 :profile-id (:id owner)
                                 :is-admin true
                                 :is-owner true
                                 :can-edit true}))

                  team))))
