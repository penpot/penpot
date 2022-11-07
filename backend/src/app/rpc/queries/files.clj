;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.queries.files
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as cmd.files]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rpch]
   [app.rpc.queries.projects :as projects]
   [app.rpc.queries.teams :as teams]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- Query: Project Files

(s/def ::project-files ::cmd.files/get-project-files)

(sv/defmethod ::project-files
  {::doc/added "1.1"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id project-id] :as params}]
  (with-open [conn (db/open pool)]
    (projects/check-read-permissions! conn profile-id project-id)
    (cmd.files/get-project-files conn project-id)))

;; --- Query: File (By ID)

(s/def ::components-v2 ::us/boolean)
(s/def ::file
  (s/and ::cmd.files/get-file
         (s/keys :opt-un [::components-v2])))

(defn get-file
  [conn id features]
  (let [file   (cmd.files/get-file conn id features)
        thumbs (cmd.files/get-object-thumbnails conn id)]
    (assoc file :thumbnails thumbs)))

(sv/defmethod ::file
  "Retrieve a file by its ID. Only authenticated users."
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id id features components-v2] :as params}]
  (with-open [conn (db/open pool)]
    (let [perms    (cmd.files/get-permissions pool profile-id id)
          ;; BACKWARD COMPATIBILTY with the components-v2 parameter
          features (cond-> (or features #{})
                     components-v2 (conj "components/v2"))]

      (cmd.files/check-read-permissions! perms)
      (-> (get-file conn id features)
          (assoc :permissions perms)))))

;; --- QUERY: page

(s/def ::page
  (s/and ::cmd.files/get-page
         (s/keys :opt-un [::components-v2])))

(sv/defmethod ::page
  "Retrieves the page data from file and returns it. If no page-id is
  specified, the first page will be returned. If object-id is
  specified, only that object and its children will be returned in the
  page objects data structure.

  If you specify the object-id, the page-id parameter becomes
  mandatory.

  Mainly used for rendering purposes."
  {::doc/added "1.5"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id features components-v2] :as params}]
  (with-open [conn (db/open pool)]
    (cmd.files/check-read-permissions! conn profile-id file-id)
    (let [;; BACKWARD COMPATIBILTY with the components-v2 parameter
          features (cond-> (or features #{})
                     components-v2 (conj "components/v2"))
          params   (assoc params :features features)]

      (cmd.files/get-page conn params))))

;; --- QUERY: file-data-for-thumbnail

(s/def ::file-data-for-thumbnail
  (s/and ::cmd.files/get-file-data-for-thumbnail
         (s/keys :opt-un [::components-v2])))

(sv/defmethod ::file-data-for-thumbnail
  "Retrieves the data for generate the thumbnail of the file. Used
  mainly for render thumbnails on dashboard."
  {::doc/added "1.11"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id features components-v2] :as props}]
  (with-open [conn (db/open pool)]
    (cmd.files/check-read-permissions! conn profile-id file-id)
    (let [;; BACKWARD COMPATIBILTY with the components-v2 parameter
          features (cond-> (or features #{})
                     components-v2 (conj "components/v2"))
          file     (cmd.files/get-file conn file-id features)]
      {:file-id file-id
       :revn (:revn file)
       :page (cmd.files/get-file-data-for-thumbnail conn file)})))

;; --- Query: Shared Library Files

(s/def ::team-shared-files ::cmd.files/get-team-shared-files)

(sv/defmethod ::team-shared-files
  {::doc/added "1.3"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id team-id] :as params}]
  (with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (cmd.files/get-team-shared-files conn params)))


;; --- Query: File Libraries used by a File

(s/def ::file-libraries ::cmd.files/get-file-libraries)

(sv/defmethod ::file-libraries
  {::doc/added "1.3"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (with-open [conn (db/open pool)]
    (cmd.files/check-read-permissions! conn profile-id file-id)
    (cmd.files/get-file-libraries conn false file-id)))


;; --- Query: Files that use this File library

(s/def ::library-using-files ::cmd.files/get-library-file-references)

(sv/defmethod ::library-using-files
  {::doc/added "1.13"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (with-open [conn (db/open pool)]
    (cmd.files/check-read-permissions! conn profile-id file-id)
    (cmd.files/get-library-file-references conn file-id)))

;; --- QUERY: team-recent-files

(s/def ::team-recent-files ::cmd.files/get-team-recent-files)

(sv/defmethod ::team-recent-files
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id team-id]}]
  (with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (cmd.files/get-team-recent-files conn team-id)))


;; --- QUERY: get file thumbnail

(s/def ::file-thumbnail ::cmd.files/get-file-thumbnail)

(sv/defmethod ::file-thumbnail
  {::doc/added "1.13"
   ::doc/deprecated "1.17"}
  [{:keys [pool]} {:keys [profile-id file-id revn]}]
  (with-open [conn (db/open pool)]
    (cmd.files/check-read-permissions! conn profile-id file-id)
    (-> (cmd.files/get-file-thumbnail conn file-id revn)
        (with-meta {::rpc/transform-response (rpch/http-cache {:max-age (* 1000 60 60)})}))))
