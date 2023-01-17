;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.mutations.fonts
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.media :as media]
   [app.rpc.commands.fonts :as fonts]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.quotes :as quotes]
   [app.storage :as sto]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]))

(declare create-font-variant)

(def valid-weight #{100 200 300 400 500 600 700 800 900 950})
(def valid-style #{"normal" "italic"})

(s/def ::id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::name ::us/not-empty-string)
(s/def ::weight valid-weight)
(s/def ::style valid-style)
(s/def ::font-id ::us/uuid)
(s/def ::data (s/map-of ::us/string any?))

(s/def ::create-font-variant
  (s/keys :req-un [::profile-id ::team-id ::data
                   ::font-id ::font-family ::font-weight ::font-style]))

(declare create-font-variant)

(sv/defmethod ::create-font-variant
  {::doc/added "1.3"
   ::doc/deprecated "1.18"
   ::webhooks/event? true}
  [{:keys [pool] :as cfg} {:keys [team-id profile-id] :as params}]
  (let [cfg (update cfg ::sto/storage media/configure-assets-storage)]
    (teams/check-edition-permissions! pool profile-id team-id)
    (quotes/check-quote! pool {::quotes/id ::quotes/font-variants-per-team
                               ::quotes/profile-id profile-id
                               ::quotes/team-id team-id})
    (fonts/create-font-variant cfg params)))

;; --- UPDATE FONT FAMILY

(s/def ::update-font
  (s/keys :req-un [::profile-id ::team-id ::id ::name]))

(sv/defmethod ::update-font
  {::doc/added "1.3"
   ::doc/deprecated "1.18"
   ::webhooks/event? true}
  [{:keys [pool] :as cfg} {:keys [team-id profile-id id name] :as params}]
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
  (s/keys :req-un [::profile-id ::team-id ::id]))

(sv/defmethod ::delete-font
  {::doc/added "1.3"
   ::doc/deprecated "1.18"
   ::webhooks/event? true}
  [{:keys [pool] :as cfg} {:keys [id team-id profile-id] :as params}]
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
  (s/keys :req-un [::profile-id ::team-id ::id]))

(sv/defmethod ::delete-font-variant
  {::doc/added "1.3"
   ::doc/deprecated "1.18"
   ::webhooks/event? true}
  [{:keys [pool] :as cfg} {:keys [id team-id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (let [variant (db/update! conn :team-font-variant
                              {:deleted-at (dt/now)}
                              {:id id :team-id team-id})]
      (rph/with-meta (rph/wrap)
        {::audit/props {:font-family (:font-family variant)
                        :font-id (:font-id variant)}}))))
