;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.mutations.fonts
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.media :as media]
   [app.rpc.queries.teams :as teams]
   [app.storage :as sto]
   [app.util.rlimit :as rlimit]
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
(s/def ::content-type ::media/font-content-type)
(s/def ::data (s/map-of ::us/string any?))

(s/def ::create-font-variant
  (s/keys :req-un [::profile-id ::team-id ::data
                   ::font-id ::font-family ::font-weight ::font-style]))

(sv/defmethod ::create-font-variant
  {::rlimit/permits (cf/get :rlimit-font)}
  [{:keys [pool] :as cfg} {:keys [team-id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (let [cfg (assoc cfg :conn conn)]
      (teams/check-edition-permissions! conn profile-id team-id)
      (create-font-variant cfg params))))

(defn create-font-variant
  [{:keys [conn storage] :as cfg} {:keys [data] :as params}]
  (let [data    (media/run {:cmd :generate-fonts :input data})
        storage (media/configure-assets-storage storage conn)

        otf     (when-let [fdata (get data "font/otf")]
                  (sto/put-object storage {:content (sto/content fdata)
                                           :content-type "font/otf"}))

        ttf     (when-let [fdata (get data "font/ttf")]
                  (sto/put-object storage {:content (sto/content fdata)
                                           :content-type "font/ttf"}))

        woff1   (when-let [fdata (get data "font/woff")]
                  (sto/put-object storage {:content (sto/content fdata)
                                           :content-type "font/woff"}))

        woff2   (when-let [fdata (get data "font/woff2")]
                  (sto/put-object storage {:content (sto/content fdata)
                                           :content-type "font/woff2"}))]

    (when (and (nil? otf)
               (nil? ttf)
               (nil? woff1)
               (nil? woff2))
      (ex/raise :type :validation
                :code :invalid-font-upload))

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
                 :ttf-file-id (:id ttf)})))

;; --- UPDATE FONT FAMILY

(s/def ::update-font
  (s/keys :req-un [::profile-id ::team-id ::id ::name]))

(def sql:update-font
  "update team_font_variant
      set font_family = ?
    where team_id = ?
      and font_id = ?")

(sv/defmethod ::update-font
  [{:keys [pool] :as cfg} {:keys [team-id profile-id id name] :as params}]
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (db/exec-one! conn [sql:update-font name team-id id])
    nil))

;; --- DELETE FONT

(s/def ::delete-font
  (s/keys :req-un [::profile-id ::team-id ::id]))

(sv/defmethod ::delete-font
  [{:keys [pool] :as cfg} {:keys [id team-id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id team-id)

    (db/update! conn :team-font-variant
                {:deleted-at (dt/now)}
                {:font-id id :team-id team-id})
    nil))

;; --- DELETE FONT VARIANT

(s/def ::delete-font-variant
  (s/keys :req-un [::profile-id ::team-id ::id]))

(sv/defmethod ::delete-font-variant
  [{:keys [pool] :as cfg} {:keys [id team-id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id team-id)

    (db/update! conn :team-font-variant
                {:deleted-at (dt/now)}
                {:id id :team-id team-id})
    nil))
