;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.fonts
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.quotes :as quotes]
   [app.storage :as sto]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [promesa.exec :as px]))

(def valid-weight #{100 200 300 400 500 600 700 800 900 950})
(def valid-style #{"normal" "italic"})

(s/def ::data (s/map-of ::us/string any?))
(s/def ::file-id ::us/uuid)
(s/def ::font-id ::us/uuid)
(s/def ::id ::us/uuid)
(s/def ::name ::us/not-empty-string)
(s/def ::project-id ::us/uuid)
(s/def ::style valid-style)
(s/def ::team-id ::us/uuid)
(s/def ::weight valid-weight)

;; --- QUERY: Get font variants

(s/def ::get-font-variants
  (s/and
   (s/keys :req [::rpc/profile-id]
           :opt-un [::team-id
                    ::file-id
                    ::project-id])
   (fn [o]
     (or (contains? o :team-id)
         (contains? o :file-id)
         (contains? o :project-id)))))

(sv/defmethod ::get-font-variants
  {::doc/added "1.18"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id file-id project-id] :as params}]
  (with-open [conn (db/open pool)]
    (cond
      (uuid? team-id)
      (do
        (teams/check-read-permissions! conn profile-id team-id)
        (db/query conn :team-font-variant
                  {:team-id team-id
                   :deleted-at nil}))

      (uuid? project-id)
      (let [project (db/get-by-id conn :project project-id {:columns [:id :team-id]})]
        (projects/check-read-permissions! conn profile-id project-id)
        (db/query conn :team-font-variant
                  {:team-id (:team-id project)
                   :deleted-at nil}))

      (uuid? file-id)
      (let [file    (db/get-by-id conn :file file-id {:columns [:id :project-id]})
            project (db/get-by-id conn :project (:project-id file) {:columns [:id :team-id]})]
        (files/check-read-permissions! conn profile-id file-id)
        (db/query conn :team-font-variant
                  {:team-id (:team-id project)
                   :deleted-at nil})))))


(declare create-font-variant)

(s/def ::create-font-variant
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id
                   ::data
                   ::font-id
                   ::font-family
                   ::font-weight
                   ::font-style]))

(sv/defmethod ::create-font-variant
  {::doc/added "1.18"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id] :as params}]
  (let [cfg (update cfg ::sto/storage media/configure-assets-storage)]
    (teams/check-edition-permissions! pool profile-id team-id)
    (quotes/check-quote! pool {::quotes/id ::quotes/font-variants-per-team
                               ::quotes/profile-id profile-id
                               ::quotes/team-id team-id})
    (create-font-variant cfg (assoc params :profile-id profile-id))))

(defn create-font-variant
  [{:keys [::sto/storage ::db/pool ::wrk/executor ::rpc/climit]} {:keys [data] :as params}]
  (letfn [(generate-fonts [data]
            (climit/with-dispatch (:process-font climit)
              (media/run {:cmd :generate-fonts :input data})))

          ;; Function responsible of calculating cryptographyc hash of
          ;; the provided data.
          (calculate-hash [data]
            (px/with-dispatch executor
              (sto/calculate-hash data)))

          (validate-data [data]
            (when (and (not (contains? data "font/otf"))
                       (not (contains? data "font/ttf"))
                       (not (contains? data "font/woff"))
                       (not (contains? data "font/woff2")))
              (ex/raise :type :validation
                        :code :invalid-font-upload))
            data)

          (persist-font-object [data mtype]
            (when-let [resource (get data mtype)]
              (p/let [hash    (calculate-hash resource)
                      content (-> (sto/content resource)
                                  (sto/wrap-with-hash hash))]
                (sto/put-object! storage {::sto/content content
                                          ::sto/touched-at (dt/now)
                                          ::sto/deduplicate? true
                                          :content-type mtype
                                          :bucket "team-font-variant"}))))

          (persist-fonts [data]
            (p/let [otf   (persist-font-object data "font/otf")
                    ttf   (persist-font-object data "font/ttf")
                    woff1 (persist-font-object data "font/woff")
                    woff2 (persist-font-object data "font/woff2")]

              (d/without-nils
               {:otf otf
                :ttf ttf
                :woff1 woff1
                :woff2 woff2})))

          (insert-into-db [{:keys [woff1 woff2 otf ttf]}]
            (db/insert! pool :team-font-variant
                        {:id (uuid/next)
                         :team-id (:team-id params)
                         :font-id (:font-id params)
                         :font-family (:font-family params)
                         :font-weight (:font-weight params)
                         :font-style (:font-style params)
                         :woff1-file-id (:id woff1)
                         :woff2-file-id (:id woff2)
                         :otf-file-id (:id otf)
                         :ttf-file-id (:id ttf)}))
          ]

    (->> (generate-fonts data)
         (p/fmap validate-data)
         (p/mcat executor persist-fonts)
         (p/fmap executor insert-into-db)
         (p/fmap (fn [result]
                   (let [params (update params :data (comp vec keys))]
                     (rph/with-meta result {::audit/replace-props params})))))))

;; --- UPDATE FONT FAMILY

(s/def ::update-font
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id ::id ::name]))

(sv/defmethod ::update-font
  {::doc/added "1.18"
   ::webhooks/event? true}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id team-id id name]}]
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (rph/with-meta
      (db/update! conn :team-font-variant
                  {:font-family name}
                  {:font-id id
                   :team-id team-id})
      {::audit/replace-props {:id id
                              :name name
                              :team-id team-id
                              :profile-id profile-id}})))

;; --- DELETE FONT

(s/def ::delete-font
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id ::id]))

(sv/defmethod ::delete-font
  {::doc/added "1.18"
   ::webhooks/event? true}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id id team-id]}]
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (let [font (db/update! conn :team-font-variant
                           {:deleted-at (dt/now)}
                           {:font-id id :team-id team-id})]
      (rph/with-meta (rph/wrap)
        {::audit/props {:id id
                        :team-id team-id
                        :name (:font-family font)
                        :profile-id profile-id}}))))

;; --- DELETE FONT VARIANT

(s/def ::delete-font-variant
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id ::id]))

(sv/defmethod ::delete-font-variant
  {::doc/added "1.18"
   ::webhooks/event? true}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id id team-id]}]
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (let [variant (db/update! conn :team-font-variant
                              {:deleted-at (dt/now)}
                              {:id id :team-id team-id})]
      (rph/with-meta (rph/wrap)
        {::audit/props {:font-family (:font-family variant)
                        :font-id (:font-id variant)}}))))

