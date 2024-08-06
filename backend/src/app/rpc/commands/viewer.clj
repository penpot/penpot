;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.viewer
  (:require
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.comments :as comments]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
   [app.rpc.cond :as-alias cond]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

;; --- QUERY: View Only Bundle

(defn- remove-not-allowed-pages
  [data allowed]
  (-> data
      (update :pages (fn [pages] (filterv #(contains? allowed %) pages)))
      (update :pages-index select-keys allowed)))

(defn- get-view-only-bundle
  [{:keys [::db/conn] :as cfg} {:keys [profile-id file-id ::perms] :as params}]
  (let [file    (files/get-file cfg file-id)

        project (db/get conn :project
                        {:id (:project-id file)}
                        {:columns [:id :name :team-id]})

        team    (-> (db/get conn :team {:id (:team-id project)})
                    (teams/decode-row))

        members (into #{} (->> (teams/get-team-members conn (:team-id project))
                               (map :id)))

        perms   (assoc perms :in-team (contains? members profile-id))

        _       (-> (cfeat/get-team-enabled-features cf/flags team)
                    (cfeat/check-client-features! (:features params))
                    (cfeat/check-file-features! (:features file)))

        file    (cond-> file
                  (= :share-link (:type perms))
                  (update :data remove-not-allowed-pages (:pages perms))

                  :always
                  (update :data select-keys [:id :options :pages :pages-index :components]))

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
                          {:team-id (:id team)
                           :deleted-at nil})]

    {:users users
     :fonts fonts
     :project project
     :share-links links
     :libraries libs
     :file file
     :team team
     :permissions perms}))

(def schema:get-view-only-bundle
  [:map {:title "get-view-only-bundle"}
   [:file-id ::sm/uuid]
   [:share-id {:optional true} ::sm/uuid]
   [:features {:optional true} ::cfeat/features]])

(sv/defmethod ::get-view-only-bundle
  {::rpc/auth false
   ::doc/added "1.17"
   ::sm/params schema:get-view-only-bundle}
  [system {:keys [::rpc/profile-id file-id share-id] :as params}]
  (db/run! system
           (fn [{:keys [::db/conn] :as system}]
             (let [perms  (files/get-permissions conn profile-id file-id share-id)
                   params (-> params
                              (assoc ::perms perms)
                              (assoc :profile-id profile-id))]

               ;; When we have neither profile nor share, we just return a not
               ;; found response to the user.
               (when-not perms
                 (ex/raise :type :not-found
                           :code :object-not-found
                           :hint "object not found"))

               (get-view-only-bundle system params)))))


