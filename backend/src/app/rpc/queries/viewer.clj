;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.queries.viewer
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.queries.files :as files]
   [app.rpc.queries.share-link :as slnk]
   [app.rpc.queries.teams :as teams]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- Query: View Only Bundle

(defn- retrieve-project
  [conn id]
  (db/get-by-id conn :project id {:columns [:id :name :team-id]}))

(defn- retrieve-bundle
  [{:keys [conn] :as cfg} file-id]
  (let [file    (files/retrieve-file cfg file-id)
        project (retrieve-project conn (:project-id file))
        libs    (files/retrieve-file-libraries cfg false file-id)
        users   (teams/retrieve-users conn (:team-id project))

        links   (->> (db/query conn :share-link {:file-id file-id})
                     (mapv slnk/decode-share-link-row))

        fonts   (db/query conn :team-font-variant
                          {:team-id (:team-id project)
                           :deleted-at nil})]
    {:file file
     :users users
     :fonts fonts
     :project project
     :share-links links
     :libraries libs}))

(s/def ::file-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::share-id ::us/uuid)

(s/def ::view-only-bundle
  (s/keys :req-un [::file-id] :opt-un [::profile-id ::share-id]))

(sv/defmethod ::view-only-bundle {:auth false}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id share-id] :as params}]
  (db/with-atomic [conn pool]
    (let [cfg    (assoc cfg :conn conn)
          slink  (slnk/retrieve-share-link conn file-id share-id)
          perms  (files/get-permissions conn profile-id file-id share-id)

          bundle (some-> (retrieve-bundle cfg file-id)
                         (assoc :permissions perms))]

      ;; When we have neither profile nor share, we just return a not
      ;; found response to the user.
      (when (and (not profile-id)
                 (not slink))
        (ex/raise :type :not-found
                  :code :object-not-found))

      ;; When we have only profile, we need to check read permissions
      ;; on file.
      (when (and profile-id (not slink))
        (files/check-read-permissions! conn profile-id file-id))

      (cond-> bundle
        (some? slink)
        (assoc :share slink)

        (and (some? slink)
             (not (contains? (:flags slink) "view-all-pages")))
        (update-in [:file :data] (fn [data]
                                   (let [allowed-pages (:pages slink)]
                                     (-> data
                                         (update :pages (fn [pages] (filterv #(contains? allowed-pages %) pages)))
                                         (update :pages-index (fn [index] (select-keys index allowed-pages)))))))))))
