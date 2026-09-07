;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.files-share
  "Share link related rpc mutation methods."
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

;; --- MUTATION: Create Share Link

(declare create-share-link)

(def ^:private schema:create-share-link
  [:map {:title "create-share-link"}
   [:file-id ::sm/uuid]
   [:who-comment [:string {:max 250}]]
   [:who-inspect [:string {:max 250}]]
   [:pages [:set ::sm/uuid]]])

(sv/defmethod ::create-share-link
  "Creates a share-link object.

  Share links are resources that allows external users access to specific
  pages of a file with specific permissions (who-comment and who-inspect)."
  {::doc/added "1.18"
   ::doc/module :files
   ::sm/params schema:create-share-link
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id file-id] :as params}]
  (files/check-edition-permissions! conn profile-id file-id)
  (create-share-link conn (assoc params :profile-id profile-id)))

(defn create-share-link
  [conn {:keys [profile-id file-id pages who-comment who-inspect]}]
  (let [pages (db/create-array conn "uuid" pages)
        slink (db/insert! conn :share-link
                          {:id (uuid/random)
                           :file-id file-id
                           :who-comment who-comment
                           :who-inspect who-inspect
                           :pages pages
                           :owner-id profile-id})]

    (update slink :pages db/decode-pgarray #{})))

;; --- MUTATION: Delete Share Link

(def ^:private schema:delete-share-link
  [:map {:title "delete-share-link"}
   [:id ::sm/uuid]])

(sv/defmethod ::delete-share-link
  {::doc/added "1.18"
   ::doc/module ::files
   ::sm/params schema:delete-share-link
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id] :as params}]
  (let [slink (db/get-by-id conn :share-link id)]
    (files/check-edition-permissions! conn profile-id (:file-id slink))

    ;; Verify caller owns this specific share-link, OR has admin access.
    ;; Note: :is-admin already includes :is-owner (see bfc/get-file-permissions),
    ;; so we only need to check :is-admin here.
    (let [perms (bfc/get-file-permissions conn profile-id (:file-id slink))]
      (when-not (or (= (:owner-id slink) profile-id)
                    (:is-admin perms))
        (ex/raise :type :authorization
                  :code :not-share-link-owner
                  :hint "You can only delete share-links you created")))

    (db/delete! conn :share-link {:id id})
    nil))
