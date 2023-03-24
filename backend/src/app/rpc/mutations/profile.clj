;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.mutations.profile
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.db :as db]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.media :as media]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.storage :as sto]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

;; --- Helpers & Specs

(s/def ::email ::us/email)
(s/def ::fullname ::us/not-empty-string)
(s/def ::lang ::us/string)
(s/def ::path ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::password ::us/not-empty-string)
(s/def ::old-password (s/nilable ::us/string))
(s/def ::theme ::us/string)

;; --- MUTATION: Update Profile (own)

(s/def ::update-profile
  (s/keys :req-un [::fullname ::profile-id]
          :opt-un [::lang ::theme]))

(sv/defmethod ::update-profile
  {::doc/added "1.0"
   ::doc/deprecated "1.18"}
  [{:keys [::db/pool] :as cfg} {:keys [profile-id fullname lang theme] :as params}]
  (db/with-atomic [conn pool]
    ;; NOTE: we need to retrieve the profile independently if we use
    ;; it or not for explicit locking and avoid concurrent updates of
    ;; the same row/object.
    (let [profile (-> (db/get-by-id conn :profile profile-id ::db/for-update? true)
                      (profile/decode-row))

          ;; Update the profile map with direct params
          profile (-> profile
                      (assoc :fullname fullname)
                      (assoc :lang lang)
                      (assoc :theme theme))
          ]

      (db/update! conn :profile
                  {:fullname fullname
                   :lang lang
                   :theme theme
                   :props (db/tjson (:props profile))}
                  {:id profile-id})

      (-> profile
          (profile/strip-private-attrs)
          (d/without-nils)
          (rph/with-meta {::audit/props (audit/profile->props profile)})))))


;; --- MUTATION: Update Password

(s/def ::update-profile-password
  (s/keys :req-un [::profile-id ::password ::old-password]))

(sv/defmethod ::update-profile-password
  {::doc/added "1.0"
   ::doc/deprecated "1.18"}
  [{:keys [::db/pool] :as cfg} {:keys [password] :as params}]
  (db/with-atomic [conn pool]
    (let [cfg        (assoc cfg ::db/conn conn)
          profile    (#'profile/validate-password! cfg params)
          session-id (::session/id params)]
      (when (= (str/lower (:email profile))
               (str/lower (:password params)))
        (ex/raise :type :validation
                  :code :email-as-password
                  :hint "you can't use your email as password"))
      (profile/update-profile-password! cfg (assoc profile :password password))
      (#'profile/invalidate-profile-session! cfg (:id profile) session-id)
      nil)))


;; --- MUTATION: Update Photo

(s/def ::file ::media/upload)
(s/def ::update-profile-photo
  (s/keys :req-un [::profile-id ::file]))

(sv/defmethod ::update-profile-photo
  {::doc/added "1.0"
   ::doc/deprecated "1.18"}
  [cfg {:keys [file] :as params}]
  ;; Validate incoming mime type
  (media/validate-media-type! file #{"image/jpeg" "image/png" "image/webp"})
  (let [cfg (update cfg ::sto/storage media/configure-assets-storage)]
    (profile/update-profile-photo cfg params)))

;; --- MUTATION: Request Email Change

(s/def ::request-email-change
  (s/keys :req-un [::email]))

(sv/defmethod ::request-email-change
  {::doc/added "1.0"
   ::doc/deprecated "1.18"}
  [{:keys [::db/pool] :as cfg} {:keys [profile-id email] :as params}]
  (db/with-atomic [conn pool]
    (let [profile (db/get-by-id conn :profile profile-id)
          cfg     (assoc cfg ::profile/conn conn)
          params  (assoc params
                         :profile profile
                         :email (str/lower email))]

      (if (contains? cf/flags :smtp)
        (#'profile/request-email-change! cfg params)
        (#'profile/change-email-immediately! cfg params)))))

;; --- MUTATION: Update Profile Props

(s/def ::props map?)
(s/def ::update-profile-props
  (s/keys :req-un [::profile-id ::props]))

(sv/defmethod ::update-profile-props
  {::doc/added "1.0"
   ::doc/deprecated "1.18"}
  [{:keys [::db/pool] :as cfg} {:keys [profile-id props]}]
  (db/with-atomic [conn pool]
    (let [profile (profile/get-profile conn profile-id ::db/for-update? true)
          props   (reduce-kv (fn [props k v]
                               ;; We don't accept namespaced keys
                               (if (simple-ident? k)
                                 (if (nil? v)
                                   (dissoc props k)
                                   (assoc props k v))
                                 props))
                             (:props profile)
                             props)]

      (db/update! conn :profile
                  {:props (db/tjson props)}
                  {:id profile-id})

      (profile/filter-props props))))


;; --- MUTATION: Delete Profile

(s/def ::delete-profile
  (s/keys :req-un [::profile-id]))

(sv/defmethod ::delete-profile
  {::doc/added "1.0"
   ::doc/deprecated "1.18"}
  [{:keys [::db/pool] :as cfg} {:keys [profile-id] :as params}]
  (db/with-atomic [conn pool]
    (let [teams      (#'profile/get-owned-teams-with-participants conn profile-id)
          deleted-at (dt/now)]

      ;; If we found owned teams with participants, we don't allow
      ;; delete profile until the user properly transfer ownership or
      ;; explicitly removes all participants from the team
      (when (some pos? (map :participants teams))
        (ex/raise :type :validation
                  :code :owner-teams-with-people
                  :hint "The user need to transfer ownership of owned teams."
                  :context {:teams (mapv :id teams)}))

      (doseq [{:keys [id]} teams]
        (db/update! conn :team
                    {:deleted-at deleted-at}
                    {:id id}))

      (db/update! conn :profile
                  {:deleted-at deleted-at}
                  {:id profile-id})

      (rph/with-transform {} (session/delete-fn cfg)))))
