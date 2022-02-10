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
   [app.db :as db]
   [app.media :as media]
   [app.rpc.queries.teams :as teams]
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
(s/def ::content-type ::media/font-content-type)
(s/def ::data (s/map-of ::us/string any?))

(s/def ::create-font-variant
  (s/keys :req-un [::profile-id ::team-id ::data
                   ::font-id ::font-family ::font-weight ::font-style]))

(sv/defmethod ::create-font-variant
  [{:keys [pool] :as cfg} {:keys [team-id profile-id] :as params}]
  (teams/check-edition-permissions! pool profile-id team-id)
  (create-font-variant cfg params))

(defn create-font-variant
  [{:keys [storage pool] :as cfg} {:keys [data] :as params}]
  (let [data    (media/run {:cmd :generate-fonts :input data})
        storage (media/configure-assets-storage storage)]

    (when (and (not (contains? data "font/otf"))
               (not (contains? data "font/ttf"))
               (not (contains? data "font/woff"))
               (not (contains? data "font/woff2")))
      (ex/raise :type :validation
                :code :invalid-font-upload))

    (let [otf   (when-let [fdata (get data "font/otf")]
                  (sto/put-object storage {:content (sto/content fdata)
                                           :content-type "font/otf"
                                           :reference :team-font-variant
                                           :touched-at (dt/now)}))

          ttf   (when-let [fdata (get data "font/ttf")]
                  (sto/put-object storage {:content (sto/content fdata)
                                           :content-type "font/ttf"
                                           :touched-at (dt/now)
                                           :reference :team-font-variant}))

          woff1 (when-let [fdata (get data "font/woff")]
                  (sto/put-object storage {:content (sto/content fdata)
                                           :content-type "font/woff"
                                           :touched-at (dt/now)
                                           :reference :team-font-variant}))

          woff2 (when-let [fdata (get data "font/woff2")]
                  (sto/put-object storage {:content (sto/content fdata)
                                           :content-type "font/woff2"
                                           :touched-at (dt/now)
                                           :reference :team-font-variant}))]

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
                   :ttf-file-id (:id ttf)}))))

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
