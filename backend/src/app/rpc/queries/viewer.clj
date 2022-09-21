;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.queries.viewer
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.commands.comments :as comments]
   [app.rpc.queries.files :as files]
   [app.rpc.queries.share-link :as slnk]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]
   [promesa.core :as p]))

;; --- Query: View Only Bundle

(defn- retrieve-project
  [pool id]
  (db/get-by-id pool :project id {:columns [:id :name :team-id]}))

(defn- retrieve-bundle
  [{:keys [pool] :as cfg} file-id profile-id components-v2]
  (p/let [file    (files/retrieve-file cfg file-id components-v2)
          project (retrieve-project pool (:project-id file))
          libs    (files/retrieve-file-libraries cfg false file-id)
          users   (comments/get-file-comments-users pool file-id profile-id)

          links   (->> (db/query pool :share-link {:file-id file-id})
                       (mapv slnk/decode-share-link-row))

          fonts   (db/query pool :team-font-variant
                            {:team-id (:team-id project)
                             :deleted-at nil})]
    {:file file
     :users users
     :fonts fonts
     :project project
     :share-links links
     :libraries libs}))

(s/def ::file-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::share-id ::us/uuid)
(s/def ::components-v2 ::us/boolean)

(s/def ::view-only-bundle
  (s/keys :req-un [::file-id] :opt-un [::profile-id ::share-id ::components-v2]))

(sv/defmethod ::view-only-bundle {:auth false}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id share-id components-v2] :as params}]
  (p/let [slink  (slnk/retrieve-share-link pool file-id share-id)
          perms  (files/get-permissions pool profile-id file-id share-id)
          thumbs (files/retrieve-object-thumbnails cfg file-id)
          bundle (p/-> (retrieve-bundle cfg file-id profile-id components-v2)
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
      (files/check-read-permissions! pool profile-id file-id))

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
