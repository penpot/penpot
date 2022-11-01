;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.viewer
  (:require
   [app.common.exceptions :as ex]
   [app.db :as db]
   [app.rpc.commands.comments :as comments]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.rpc.queries.share-link :as slnk]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- Query: View Only Bundle

(defn- get-project
  [conn id]
  (db/get-by-id conn :project id {:columns [:id :name :team-id]}))

(defn- get-bundle
  [conn file-id profile-id features]
  (let [file    (files/get-file conn file-id features)
        project (get-project conn (:project-id file))
        libs    (files/get-file-libraries conn false file-id)
        users   (comments/get-file-comments-users conn file-id profile-id)

        links   (->> (db/query conn :share-link {:file-id file-id})
                     (mapv slnk/decode-share-link-row))

        fonts   (db/query conn :team-font-variant
                          {:team-id (:team-id project)
                           :deleted-at nil})]
    {:file file
     :users users
     :fonts fonts
     :project project
     :share-links links
     :libraries libs}))

(defn get-view-only-bundle
  [conn {:keys [profile-id file-id share-id features] :as params}]
  (let [slink  (slnk/retrieve-share-link conn file-id share-id)
        perms  (files/get-permissions conn profile-id file-id share-id)
        thumbs (files/retrieve-object-thumbnails conn file-id)
        bundle (-> (get-bundle conn file-id profile-id features)
                   (assoc :permissions perms)
                   (assoc-in [:file :thumbnails] thumbs))]

    ;; When we have neither profile nor share, we just return a not
    ;; found response to the user.
    (when (and (not profile-id)
               (not slink))
      (ex/raise :type :not-found
                :code :object-not-found))

    ;; When we have only profile, we need to check read permissions
    ;; on file.
    (when (and profile-id (not slink))
      (files/check-read-permissions! conn profile-id file-id))

    (cond-> bundle
      (some? slink)
      (assoc :share slink)

      (and (some? slink)
           (not (contains? (:flags slink) "view-all-pages")))
      (update-in [:file :data] (fn [data]
                                 (let [allowed-pages (:pages slink)]
                                   (-> data
                                       (update :pages (fn [pages] (filterv #(contains? allowed-pages %) pages)))
                                       (update :pages-index (fn [index] (select-keys index allowed-pages))))))))))

(s/def ::get-view-only-bundle
  (s/keys :req-un [::files/file-id]
          :opt-un [::files/profile-id
                   ::files/share-id
                   ::files/features]))

(sv/defmethod ::get-view-only-bundle
  {:auth false
   ::doc/added "1.17"}
  [{:keys [pool]} params]
  (with-open [conn (db/open pool)]
    (get-view-only-bundle conn params)))

