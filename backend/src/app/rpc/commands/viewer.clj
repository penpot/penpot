;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.viewer
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.comments :as comments]
   [app.rpc.commands.files :as files]
   [app.rpc.cond :as-alias cond]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

;; --- QUERY: View Only Bundle

(defn- get-project
  [conn id]
  (db/get-by-id conn :project id {:columns [:id :name :team-id]}))

(defn- get-bundle
  [conn file-id profile-id features]
  (let [file    (files/get-file conn file-id features)
        project (get-project conn (:project-id file))
        libs    (files/get-file-libraries conn file-id)
        users   (comments/get-file-comments-users conn file-id profile-id)
        links   (->> (db/query conn :share-link {:file-id file-id})
                     (mapv (fn [row]
                             (-> row
                                 (update :pages db/decode-pgarray #{})
                                 ;; NOTE: the flags are deprecated but are still present
                                 ;; on the table on old rows. The flags are pgarray and
                                 ;; for avoid decoding it (because they are no longer used
                                 ;; on frontend) we just dissoc the column attribute from
                                 ;; row.
                                 (dissoc :flags)))))

        fonts   (db/query conn :team-font-variant
                          {:team-id (:team-id project)
                           :deleted-at nil})]

    {:file file
     :users users
     :fonts fonts
     :project project
     :share-links links
     :libraries libs}))

(defn- remove-not-allowed-pages
  [data allowed]
  (-> data
      (update :pages (fn [pages] (filterv #(contains? allowed %) pages)))
      (update :pages-index select-keys allowed)))

(defn get-view-only-bundle
  [conn {:keys [profile-id file-id share-id features] :as params}]
  (let [perms  (files/get-permissions conn profile-id file-id share-id)
        bundle (-> (get-bundle conn file-id profile-id features)
                   (assoc :permissions perms))]

    ;; When we have neither profile nor share, we just return a not
    ;; found response to the user.
    (when-not perms
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "object not found"))

    (update bundle :file
            (fn [file]
              (cond-> file
                (= :share-link (:type perms))
                (update :data remove-not-allowed-pages (:pages perms))

                :always
                (update :data select-keys [:id :options :pages :pages-index :components]))))))

(sm/def! ::get-view-only-bundle
  [:map {:title "get-view-only-bundle"}
   [:file-id ::sm/uuid]
   [:share-id {:optional true} ::sm/uuid]
   [:features {:optional true} ::cfeat/features]])

(sv/defmethod ::get-view-only-bundle
  {::rpc/auth false
   ::doc/added "1.17"
   ::sm/params ::get-view-only-bundle}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (get-view-only-bundle conn (assoc params :profile-id profile-id))))
