;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.ingest
  "Penpot file -> Ladybug graph projection."
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.db :as db]
   [app.graph.ladybug :as ladybug]
   [app.graph.project.document :as project.document]
   [app.graph.project.transforms :as project.transforms]
   [app.graph.schema :as schema]
   [app.graph.stats :as stats]
   [app.srepl.helpers :as h]))

(defn ingest-file!
  [system file-id & {:keys [db-path reset-db?]
                     :or   {reset-db? true}}]
  (let [file-id (h/parse-uuid file-id)
        file    (db/run! system #(bfc/get-file % file-id :realize? true))
        db-path (or db-path (ladybug/db-path-for-file file-id))]
    (when-not file
      (ex/raise :type :not-found
                :code :file-not-found
                :file-id (str file-id)))
    (when reset-db?
      (ladybug/reset-db-path! db-path))
    (l/inf :hint "graph ingest"
           :file-id (str file-id)
           :revn (:revn file)
           :db-path db-path
           :schema schema/schema-version)
    (let [data              (:data file)
          ddl               (schema/ddl-statements)
          {:keys [statements stats]}
          (project.document/projection-statements data file)
          ingest-statements (conj (into ddl statements) "CHECKPOINT;")]
      (ladybug/exec! system db-path ingest-statements)
      {:file-id        file-id
       :revn           (:revn file)
       :name           (or (:name data) (:name file))
       :db-path        db-path
       :schema-version schema/schema-version
       :projection     {:stats stats}
       :transforms     (project.transforms/apply-transforms! system db-path data file)
       :stats          (stats/summarize system db-path)})))
