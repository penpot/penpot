;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.mutations.share-link
  "Share link related rpc mutation methods."
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::who-comment ::us/string)
(s/def ::who-inspect ::us/string)
(s/def ::pages (s/every ::us/uuid :kind set?))

;; --- Mutation: Create Share Link

(declare create-share-link)

(s/def ::create-share-link
  (s/keys :req-un [::profile-id ::file-id ::who-comment ::who-inspect ::pages]))

(sv/defmethod ::create-share-link
  "Creates a share-link object.

  Share links are resources that allows external users access to specific
  pages of a file with specific permissions (who-comment and who-inspect)."
  {::doc/added "1.5"
   ::doc/deprecated "1.18"}
  [{:keys [::db/pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (create-share-link conn params)))

(defn create-share-link
  [conn {:keys [profile-id file-id pages who-comment who-inspect]}]
  (let [pages (db/create-array conn "uuid" pages)
        slink (db/insert! conn :share-link
                          {:id (uuid/next)
                           :file-id file-id
                           :who-comment who-comment
                           :who-inspect who-inspect
                           :pages pages
                           :owner-id profile-id})]
    (update slink :pages db/decode-pgarray #{})))

;; --- Mutation: Delete Share Link

(s/def ::delete-share-link
  (s/keys :req-un [::profile-id ::id]))

(sv/defmethod ::delete-share-link
  {::doc/added "1.5"
   ::doc/deprecated "1.18"}
  [{:keys [::db/pool] :as cfg} {:keys [profile-id id] :as params}]
  (db/with-atomic [conn pool]
    (let [slink (db/get-by-id conn :share-link id)]
      (files/check-edition-permissions! conn profile-id (:file-id slink))
      (db/delete! conn :share-link {:id id})
      nil)))
