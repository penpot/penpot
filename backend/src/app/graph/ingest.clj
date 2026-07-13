;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.ingest
  "Penpot file -> Ladybug graph projection.

  Skeleton stage: loads the canonical file from the backend and exercises
  Ladybug. Document projection will replace the smoke test step."
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.db :as db]
   [app.graph.ladybug :as ladybug]
   [app.srepl.helpers :as h]))

(defn ingest-file!
  [system file-id & {:keys [db-path smoke-test?]
                     :or   {smoke-test? true}}]
  (let [file-id (h/parse-uuid file-id)
        file    (db/run! system #(bfc/get-file % file-id :realize? true))
        db-path (or db-path (ladybug/db-path-for-file file-id))]
    (when-not file
      (ex/raise :type :not-found
                :code :file-not-found
                :file-id (str file-id)))
    (l/inf :hint "graph ingest skeleton"
           :file-id (str file-id)
           :revn (:revn file)
           :db-path db-path)
    ;; TODO: project (:data file) into Ladybug node/rel tables.
    (let [ladybug-result (when smoke-test?
                           (ladybug/smoke-test! system :db-path db-path))]
      {:file-id file-id
       :revn    (:revn file)
       :name    (get-in file [:data :name])
       :db-path db-path
       :ladybug ladybug-result})))
