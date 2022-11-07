;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.mutations.files
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.files :as cmd.files]
   [app.rpc.commands.files.create :as cmd.files.create]
   [app.rpc.commands.files.temp :as cmd.files.temp]
   [app.rpc.commands.files.update :as cmd.files.update]
   [app.rpc.doc :as-alias doc]
   [app.rpc.queries.projects :as proj]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]))

;; --- Mutation: Create File

(s/def ::create-file ::cmd.files.create/create-file)

(sv/defmethod ::create-file
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id project-id features components-v2] :as params}]
  (db/with-atomic [conn pool]
    (proj/check-edition-permissions! conn profile-id project-id)
    (let [team-id  (cmd.files/get-team-id conn project-id)
          features (cond-> (or features #{})
                     ;; BACKWARD COMPATIBILITY with the components-v2 param
                     components-v2 (conj "components/v2"))
          params   (assoc params :features features)]
      (-> (cmd.files.create/create-file conn params)
          (vary-meta assoc ::audit/props {:team-id team-id})))))


;; --- Mutation: Rename File

(s/def ::rename-file ::cmd.files/rename-file)

(sv/defmethod ::rename-file
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (cmd.files/check-edition-permissions! conn profile-id id)
    (cmd.files/rename-file conn params)))


;; --- Mutation: Set File shared

(s/def ::set-file-shared ::cmd.files/set-file-shared)

(sv/defmethod ::set-file-shared
  {::doc/added "1.2"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [id profile-id is-shared] :as params}]
  (db/with-atomic [conn pool]
    (cmd.files/check-edition-permissions! conn profile-id id)
    (when-not is-shared
      (cmd.files/absorb-library conn params)
      (cmd.files/unlink-files conn params))
    (cmd.files/set-file-shared conn params)))

;; --- Mutation: Delete File

(s/def ::delete-file ::cmd.files/delete-file)

(sv/defmethod ::delete-file
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (cmd.files/check-edition-permissions! conn profile-id id)
    (cmd.files/absorb-library conn params)
    (cmd.files/mark-file-deleted conn params)))

;; --- Mutation: Link file to library

(s/def ::link-file-to-library ::cmd.files/link-file-to-library)

(sv/defmethod ::link-file-to-library
  {::doc/added "1.3"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id library-id] :as params}]
  (when (= file-id library-id)
    (ex/raise :type :validation
              :code :invalid-library
              :hint "A file cannot be linked to itself"))
  (db/with-atomic [conn pool]
    (cmd.files/check-edition-permissions! conn profile-id file-id)
    (cmd.files/check-edition-permissions! conn profile-id library-id)
    (cmd.files/link-file-to-library conn params)))

;; --- Mutation: Unlink file from library

(s/def ::unlink-file-from-library ::cmd.files/unlink-file-from-library)

(sv/defmethod ::unlink-file-from-library
  {::doc/added "1.3"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (cmd.files/check-edition-permissions! conn profile-id file-id)
    (cmd.files/unlink-file-from-library conn params)))


;; --- Mutation: Update synchronization status of a link

(s/def ::update-sync ::cmd.files/update-file-library-sync-status)

(sv/defmethod ::update-sync
  {::doc/added "1.10"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (cmd.files/check-edition-permissions! conn profile-id file-id)
    (cmd.files/update-sync conn params)))


;; --- Mutation: Ignore updates in linked files

(declare ignore-sync)

(s/def ::ignore-sync ::cmd.files/ignore-file-library-sync-status)

(sv/defmethod ::ignore-sync
  {::doc/added "1.10"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (cmd.files/check-edition-permissions! conn profile-id file-id)
    (cmd.files/ignore-sync conn params)))


;; --- MUTATION: update-file

(s/def ::components-v2 ::us/boolean)
(s/def ::update-file
  (s/and ::cmd.files.update/update-file
         (s/keys :opt-un [::components-v2])))

(sv/defmethod ::update-file
  {::climit/queue :update-file
   ::climit/key-fn :id
   ::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [id profile-id features components-v2] :as params}]
  (db/with-atomic [conn pool]
    (db/xact-lock! conn id)
    (cmd.files/check-edition-permissions! conn profile-id id)

    (let [;; BACKWARD COMPATIBILITY with the components-v2 parameter
          features  (cond-> (or features #{})
                      components-v2 (conj "components/v2"))
          tpoint    (dt/tpoint)
          params    (assoc params :features features)
          cfg       (assoc cfg :conn conn)]

      (-> (cmd.files.update/update-file cfg params)
          (vary-meta assoc ::rpc/before-complete
                     (fn []
                       (let [elapsed (tpoint)]
                         (l/trace :hint "update-file" :time (dt/format-duration elapsed)))))))))

;; --- Mutation: upsert object thumbnail

(s/def ::upsert-file-object-thumbnail ::cmd.files/upsert-file-object-thumbnail)

(sv/defmethod ::upsert-file-object-thumbnail
  {::doc/added "1.13"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (cmd.files/check-edition-permissions! conn profile-id file-id)
    (cmd.files/upsert-file-object-thumbnail! conn params)
    nil))


;; --- Mutation: upsert file thumbnail

(s/def ::upsert-file-thumbnail ::cmd.files/upsert-file-thumbnail)

(sv/defmethod ::upsert-file-thumbnail
  "Creates or updates the file thumbnail. Mainly used for paint the
  grid thumbnails."
  {::doc/added "1.13"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (cmd.files/check-edition-permissions! conn profile-id file-id)
    (cmd.files/upsert-file-thumbnail conn params)
    nil))


;; --- MUTATION COMMAND: create-temp-file

(s/def ::create-temp-file ::cmd.files.temp/create-temp-file)

(sv/defmethod ::create-temp-file
  {::doc/added "1.7"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id project-id] :as params}]
  (db/with-atomic [conn pool]
    (proj/check-edition-permissions! conn profile-id project-id)
    (cmd.files.create/create-file conn (assoc params :deleted-at (dt/in-future {:days 1})))))

;; --- MUTATION COMMAND: update-temp-file

(s/def ::update-temp-file ::cmd.files.temp/update-temp-file)

(sv/defmethod ::update-temp-file
  {::doc/added "1.7"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (cmd.files.temp/update-temp-file conn params)
    nil))

;; --- MUTATION COMMAND: persist-temp-file

(s/def ::persist-temp-file ::cmd.files.temp/persist-temp-file)

(sv/defmethod ::persist-temp-file
  {::doc/added "1.7"
   ::doc/deprecated "1.17"}
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (cmd.files/check-edition-permissions! conn profile-id id)
    (cmd.files.temp/persist-temp-file conn params)))
