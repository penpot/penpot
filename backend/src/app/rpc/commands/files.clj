;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.doc :as-alias doc]
   [app.rpc.queries.files :as files]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUERY COMMANDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Query: File Libraries used by a File

(declare retrieve-has-file-libraries)

(s/def ::file-id ::us/uuid)
(s/def ::profile-id ::us/uuid)

(s/def ::has-file-libraries
  (s/keys :req-un [::profile-id ::file-id]))

(sv/defmethod ::has-file-libraries
  "Checks if the file has libraries. Returns a boolean"
  {::doc/added "1.15.1"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (with-open [conn (db/open pool)]
    (files/check-read-permissions! pool profile-id file-id)
    (retrieve-has-file-libraries conn params)))

(def ^:private sql:has-file-libraries
  "SELECT COUNT(*) > 0 AS has_libraries
     FROM file_library_rel AS flr
     JOIN file AS fl ON (flr.library_file_id = fl.id)
    WHERE flr.file_id = ?::uuid
      AND (fl.deleted_at IS NULL OR
           fl.deleted_at > now())")

(defn- retrieve-has-file-libraries
  [conn {:keys [file-id]}]
  (let [row (db/exec-one! conn [sql:has-file-libraries file-id])]
    (:has-libraries row)))

