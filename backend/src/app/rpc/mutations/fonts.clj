;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.mutations.fonts
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.media :as media]
   [app.rpc.queries.teams :as teams]
   [app.storage :as sto]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

(declare create-font-variant)

(def valid-weight #{100 200 300 400 500 600 700 800 900 950})
(def valid-style #{"normal" "italic"})

(s/def ::profile-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::name ::us/not-empty-string)
(s/def ::weight valid-weight)
(s/def ::style valid-style)
(s/def ::font-id (s/and ::us/string #(str/starts-with? % "custom-")))
(s/def ::content-type ::media/font-content-type)
(s/def ::data (s/map-of ::us/string any?))

(s/def ::create-font-variant
  (s/keys :req-un [::profile-id ::team-id ::data
                   ::font-id ::font-family ::font-weight ::font-style]))

(sv/defmethod ::create-font-variant
  [{:keys [pool] :as cfg} {:keys [team-id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (let [cfg (assoc cfg :conn conn)]
      (teams/check-edition-permissions! conn profile-id team-id)
      (create-font-variant cfg params))))

(defn create-font-variant
  [{:keys [conn storage] :as cfg} {:keys [data] :as params}]
  (let [data    (media/run cfg {:cmd :generate-fonts :input data :rlimit :font})
        storage (assoc storage :conn conn)
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

;; --- UPDATE FONT VARIANT

(s/def ::update-font-variant
  (s/keys :req-un [::profile-id ::team-id ::id ::font-family ::font-id]))

(sv/defmethod ::update-font-variant
  [{:keys [pool] :as cfg} {:keys [id team-id profile-id font-family font-id] :as params}]
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (db/update! conn :team-font-variant
                {:font-family font-family
                 :font-id font-id}
                {:id id
                 :team-id team-id})
    nil))

;; --- DELETE FONT VARIANT

(s/def ::delete-font-variant
  (s/keys :req-un [::profile-id ::team-id ::id]))

(sv/defmethod ::delete-font-variant
  [{:keys [pool] :as cfg} {:keys [id team-id profile-id font-family font-id] :as params}]
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id team-id)

    ;; Schedule object deletion
    (wrk/submit! {::wrk/task :delete-object
                  ::wrk/delay cf/deletion-delay
                  ::wrk/conn conn
                  :id id
                  :type :team-font-variant})

    (db/update! conn :team-font-variant
                {:deleted-at (dt/now)}
                {:id id
                 :team-id team-id})
    nil))
