;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files.temp
  (:require
   [app.common.exceptions :as ex]
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files.create :as files.create]
   [app.rpc.commands.files.update :as files.update]
   [app.rpc.doc :as-alias doc]
   [app.rpc.queries.projects :as proj]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]))

;; --- MUTATION COMMAND: create-temp-file

(s/def ::create-temp-file ::files.create/create-file)

(sv/defmethod ::create-temp-file
  {::doc/added "1.17"}
  [{:keys [pool] :as cfg} {:keys [profile-id project-id] :as params}]
  (db/with-atomic [conn pool]
    (proj/check-edition-permissions! conn profile-id project-id)
    (files.create/create-file conn (assoc params :deleted-at (dt/in-future {:days 1})))))

;; --- MUTATION COMMAND: update-temp-file

(defn update-temp-file
  [conn {:keys [profile-id session-id id revn changes] :as params}]
  (db/insert! conn :file-change
              {:id (uuid/next)
               :session-id session-id
               :profile-id profile-id
               :created-at (dt/now)
               :file-id id
               :revn revn
               :data nil
               :changes (blob/encode changes)}))

(s/def ::update-temp-file
  (s/keys :req-un [::files.update/changes
                   ::files.update/revn
                   ::files.update/session-id
                   ::files/id]))

(sv/defmethod ::update-temp-file
  {::doc/added "1.17"}
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (update-temp-file conn params)
    nil))

;; --- MUTATION COMMAND: persist-temp-file

(defn persist-temp-file
  [conn {:keys [id] :as params}]
  (let [file (db/get-by-id conn :file id)
        revs (db/query conn :file-change
                       {:file-id id}
                       {:order-by [[:revn :asc]]})
        revn (count revs)]

    (when (nil? (:deleted-at file))
      (ex/raise :type :validation
                :code :cant-persist-already-persisted-file))

    (loop [revs (seq revs)
           data (blob/decode (:data file))]
      (if-let [rev (first revs)]
        (recur (rest revs)
               (->> rev :changes blob/decode (cp/process-changes data)))
        (db/update! conn :file
                    {:deleted-at nil
                     :revn revn
                     :data (blob/encode data)}
                    {:id id})))
    nil))

(s/def ::persist-temp-file
  (s/keys :req-un [::files/id
                   ::files/profile-id]))

(sv/defmethod ::persist-temp-file
  {::doc/added "1.17"}
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id id)
    (persist-temp-file conn params)))
