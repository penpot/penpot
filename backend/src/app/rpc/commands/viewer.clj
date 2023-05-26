;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.viewer
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.comments :as comments]
   [app.rpc.commands.files :as files]
   [app.rpc.cond :as-alias cond]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

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

(s/def ::get-view-only-bundle
  (s/keys :req-un [::files/file-id]
          :opt-un [::files/share-id
                   ::files/features]
          :opt [::rpc/profile-id]))

(sv/defmethod ::get-view-only-bundle
  {::rpc/auth false
   ::cond/get-object #(files/get-minimal-file %1 (:file-id %2))
   ::cond/key-fn files/get-file-etag
   ::cond/reuse-key? true
   ::doc/added "1.17"}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (get-view-only-bundle conn (assoc params :profile-id profile-id))))
