;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.fonts
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.logical-deletion :as ldel]
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
   [app.util.services :as sv]))

(def valid-weight #{100 200 300 400 500 600 700 800 900 950})
(def valid-style #{"normal" "italic"})

;; --- QUERY: Get font variants

(def ^:private
  schema:get-font-variants
  [:and
   [:map {:title "get-font-variants"}
    [:team-id {:optional true} ::sm/uuid]
    [:file-id {:optional true} ::sm/uuid]
    [:project-id {:optional true} ::sm/uuid]
    [:share-id {:optional true} ::sm/uuid]]
   [::sm/contains-any #{:team-id :file-id :project-id}]])

(sv/defmethod ::get-font-variants
  {::doc/added "1.18"
   ::sm/params schema:get-font-variants}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id file-id project-id share-id] :as params}]
  (dm/with-open [conn (db/open pool)]
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
            project (db/get-by-id conn :project (:project-id file) {:columns [:id :team-id]})
            perms   (files/get-permissions conn profile-id file-id share-id)]
        (files/check-read-permissions! perms)
        (db/query conn :team-font-variant
                  {:team-id (:team-id project)
                   :deleted-at nil})))))


(declare create-font-variant)

(def ^:private schema:create-font-variant
  [:map {:title "create-font-variant"}
   [:team-id ::sm/uuid]
   [:data [:map-of ::sm/text ::sm/any]]
   [:font-id ::sm/uuid]
   [:font-family ::sm/text]
   [:font-weight [::sm/one-of {:format "number"} valid-weight]]
   [:font-style [::sm/one-of {:format "string"} valid-style]]])

;; FIXME: IMPORTANT: refactor this, we should not hold a whole db
;; connection around the font creation

(sv/defmethod ::create-font-variant
  {::doc/added "1.18"
   ::climit/id [[:process-font/by-profile ::rpc/profile-id]
                [:process-font/global]]
   ::webhooks/event? true
   ::sm/params schema:create-font-variant}
  [cfg {:keys [::rpc/profile-id team-id] :as params}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn] :as cfg}]
                (teams/check-edition-permissions! conn profile-id team-id)
                (quotes/check! cfg {::quotes/id ::quotes/font-variants-per-team
                                    ::quotes/profile-id profile-id
                                    ::quotes/team-id team-id})
                (create-font-variant cfg (assoc params :profile-id profile-id)))))

(defn create-font-variant
  [{:keys [::sto/storage ::db/conn]} {:keys [data] :as params}]
  (letfn [(generate-missing! [data]
            (let [data (media/run {:cmd :generate-fonts :input data})]
              (when (and (not (contains? data "font/otf"))
                         (not (contains? data "font/ttf"))
                         (not (contains? data "font/woff"))
                         (not (contains? data "font/woff2")))
                (ex/raise :type :validation
                          :code :invalid-font-upload
                          :hint "invalid font upload, unable to generate missing font assets"))
              data))

          (prepare-font [data mtype]
            (when-let [resource (get data mtype)]
              (let [hash    (sto/calculate-hash resource)
                    content (-> (sto/content resource)
                                (sto/wrap-with-hash hash))]
                {::sto/content content
                 ::sto/touched-at (ct/now)
                 ::sto/deduplicate? true
                 :content-type mtype
                 :bucket "team-font-variant"})))

          (persist-fonts-files! [data]
            (let [otf-params (prepare-font data "font/otf")
                  ttf-params (prepare-font data "font/ttf")
                  wf1-params (prepare-font data "font/woff")
                  wf2-params (prepare-font data "font/woff2")]

              (cond-> {}
                (some? otf-params)
                (assoc :otf (sto/put-object! storage otf-params))
                (some? ttf-params)
                (assoc :ttf (sto/put-object! storage ttf-params))
                (some? wf1-params)
                (assoc :woff1 (sto/put-object! storage wf1-params))
                (some? wf2-params)
                (assoc :woff2 (sto/put-object! storage wf2-params)))))

          (insert-font-variant! [{:keys [woff1 woff2 otf ttf]}]
            (db/insert! conn :team-font-variant
                        {:id (uuid/next)
                         :team-id (:team-id params)
                         :font-id (:font-id params)
                         :font-family (:font-family params)
                         :font-weight (:font-weight params)
                         :font-style (:font-style params)
                         :woff1-file-id (:id woff1)
                         :woff2-file-id (:id woff2)
                         :otf-file-id (:id otf)
                         :ttf-file-id (:id ttf)}))]

    (let [data   (generate-missing! data)
          assets (persist-fonts-files! data)
          result (insert-font-variant! assets)]
      (vary-meta result assoc ::audit/replace-props (update params :data (comp vec keys))))))

;; --- UPDATE FONT FAMILY

(def ^:private
  schema:update-font
  [:map {:title "update-font"}
   [:team-id ::sm/uuid]
   [:id ::sm/uuid]
   [:name :string]])

(sv/defmethod ::update-font
  {::doc/added "1.18"
   ::webhooks/event? true
   ::sm/params schema:update-font}
  [cfg {:keys [::rpc/profile-id team-id id name]}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn]}]
                (teams/check-edition-permissions! conn profile-id team-id)

                (db/update! conn :team-font-variant
                            {:font-family name}
                            {:font-id id
                             :team-id team-id})

                (rph/with-meta (rph/wrap nil)
                  {::audit/replace-props {:id id
                                          :name name
                                          :team-id team-id
                                          :profile-id profile-id}}))))

;; --- DELETE FONT

(def ^:private
  schema:delete-font
  [:map {:title "delete-font"}
   [:team-id ::sm/uuid]
   [:id ::sm/uuid]])

(sv/defmethod ::delete-font
  {::doc/added "1.18"
   ::webhooks/event? true
   ::sm/params schema:delete-font
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id id team-id]}]
  (let [team  (teams/get-team conn
                              :profile-id profile-id
                              :team-id team-id)

        fonts (db/query conn :team-font-variant
                        {:team-id team-id
                         :font-id id
                         :deleted-at nil}
                        {::sql/for-update true})

        delay (ldel/get-deletion-delay team)
        tnow  (ct/in-future delay)]

    (teams/check-edition-permissions! (:permissions team))

    (when-not (seq fonts)
      (ex/raise :type :not-found
                :code :object-not-found))


    (doseq [font fonts]
      (db/update! conn :team-font-variant
                  {:deleted-at tnow}
                  {:id (:id font)}
                  {::db/return-keys false}))

    (rph/with-meta (rph/wrap)
      {::audit/props {:id id
                      :team-id team-id
                      :name (:font-family (peek fonts))
                      :profile-id profile-id}})))

;; --- DELETE FONT VARIANT

(def ^:private schema:delete-font-variant
  [:map {:title "delete-font-variant"}
   [:team-id ::sm/uuid]
   [:id ::sm/uuid]])

(sv/defmethod ::delete-font-variant
  {::doc/added "1.18"
   ::webhooks/event? true
   ::sm/params schema:delete-font-variant
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id id team-id]}]
  (let [team    (teams/get-team conn
                                :profile-id profile-id
                                :team-id team-id)
        variant (db/get conn :team-font-variant
                        {:id id :team-id team-id}
                        {::sql/for-update true})
        delay   (ldel/get-deletion-delay team)]

    (teams/check-edition-permissions! (:permissions team))
    (db/update! conn :team-font-variant
                {:deleted-at (ct/in-future delay)}
                {:id (:id variant)}
                {::db/return-keys false})

    (rph/with-meta (rph/wrap)
      {::audit/props {:font-family (:font-family variant)
                      :font-id (:font-id variant)}})))
