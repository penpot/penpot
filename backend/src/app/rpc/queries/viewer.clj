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
   [app.rpc.queries.teams :as teams]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- Query: View Only Bundle

(defn- decode-share-link-row
  [row]
  (-> row
      (update :flags db/decode-pgarray #{})
      (update :pages db/decode-pgarray #{})))

(defn- retrieve-project
  [conn id]
  (db/get-by-id conn :project id {:columns [:id :name :team-id]}))

(defn- retrieve-share-link
  [{:keys [conn]} file-id id]
  (some-> (db/get-by-params conn :share-link
                            {:id id :file-id file-id}
                            {:check-not-found false})
          (decode-share-link-row)))

(defn- retrieve-bundle
  [{:keys [conn] :as cfg} file-id]
  (let [file    (files/retrieve-file cfg file-id)
        project (retrieve-project conn (:project-id file))
        libs    (files/retrieve-file-libraries cfg false file-id)
        users   (teams/retrieve-users conn (:team-id project))

        links   (->> (db/query conn :share-link {:file-id file-id})
                     (mapv decode-share-link-row))

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
          bundle (retrieve-bundle cfg file-id)
          slink  (retrieve-share-link cfg file-id share-id)]

      ;; When we have neither profile nor share, we just return a not
      ;; found response to the user.
      (when (and (not profile-id)
                 (not slink))
        (ex/raise :type :not-found
                  :code :object-not-found))

      ;; When we have only profile, we need to check read permissiones
      ;; on file.
      (when (and profile-id (not slink))
        (files/check-read-permissions! conn profile-id file-id))

      (cond-> bundle
        ;; If we have current profile, put
        (some? profile-id)
        (as-> $ (let [edit? (boolean (files/has-edit-permissions? conn profile-id file-id))
                      read? (boolean (files/has-read-permissions? conn profile-id file-id))]
                  (-> (assoc $ :permissions {:read read? :edit edit?})
                      (cond-> (not edit?) (dissoc :share-links)))))

        (some? slink)
        (assoc :share slink)

        (and (some? slink)
             (not (contains? (:flags slink) "view-all-pages")))
        (update-in [:file :data] (fn [data]
                                   (let [allowed-pages (:pages slink)]
                                     (-> data
                                         (update :pages (fn [pages] (filterv #(contains? allowed-pages %) pages)))
                                         (update :pages-index (fn [index] (select-keys index allowed-pages)))))))))))

;; --- Query: Viewer Bundle (by Page ID)

;; DEPRECATED: should be removed in 1.9.x

(declare check-shared-token!)
(declare retrieve-shared-token)

(s/def ::id ::us/uuid)
(s/def ::page-id ::us/uuid)
(s/def ::token ::us/string)

(s/def ::viewer-bundle
  (s/keys :req-un [::file-id ::page-id]
          :opt-un [::profile-id ::token]))

(sv/defmethod ::viewer-bundle {:auth false}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id page-id token] :as params}]
  (db/with-atomic [conn pool]
    (let [cfg     (assoc cfg :conn conn)
          file    (files/retrieve-file cfg file-id)
          project (retrieve-project conn (:project-id file))
          page    (get-in file [:data :pages-index page-id])
          file    (merge (dissoc file :data)
                         (select-keys (:data file) [:colors :media :typographies]))
          libs    (files/retrieve-file-libraries cfg false file-id)
          users   (teams/retrieve-users conn (:team-id project))

          fonts   (db/query conn :team-font-variant
                            {:team-id (:team-id project)
                             :deleted-at nil})

          bundle  {:file file
                   :page page
                   :users users
                   :fonts fonts
                   :project project
                   :libraries libs}]

      (if (string? token)
        (do
          (check-shared-token! conn file-id page-id token)
          (assoc bundle :token token))
        (let [stoken (retrieve-shared-token conn file-id page-id)]
          (files/check-read-permissions! conn profile-id file-id)
          (assoc bundle :token (:token stoken)))))))

(defn check-shared-token!
  [conn file-id page-id token]
  (let [sql "select exists(select 1 from file_share_token where file_id=? and page_id=? and token=?) as exists"]
    (when-not (:exists (db/exec-one! conn [sql file-id page-id token]))
      (ex/raise :type :not-found
                :code :object-not-found))))

(defn retrieve-shared-token
  [conn file-id page-id]
  (let [sql "select * from file_share_token where file_id=? and page_id=?"]
    (db/exec-one! conn [sql file-id page-id])))
