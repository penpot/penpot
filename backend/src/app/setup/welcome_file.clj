;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.setup.welcome-file
  (:require
   [app.common.logging :as l]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-update :as fupdate]
   [app.rpc.commands.management :as management]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.setup :as-alias setup]
   [app.setup.templates :as tmpl]
   [app.worker :as-alias wrk]))

(def ^:private page-id #uuid "2c6952ee-d00e-8160-8004-d2250b7210cb")
(def ^:private shape-id #uuid "765e9f82-c44e-802e-8004-d72a10b7b445")

(def ^:private update-path
  [:data :pages-index page-id :objects shape-id
   :content :children 0 :children 0 :children 0])

(def ^:private sql:mark-file-object-thumbnails-deleted
  "UPDATE file_tagged_object_thumbnail
      SET deleted_at = now()
    WHERE file_id = ?")

(def ^:private sql:mark-file-thumbnail-deleted
  "UPDATE file_thumbnail
      SET deleted_at = now()
    WHERE file_id = ?")

(defn- update-welcome-shape
  [_ file name]
  (let [text (str "Welcome to Penpot, " name "!")]
    (-> file
        (update-in update-path assoc :text text)
        (update-in [:data :pages-index page-id :objects shape-id] assoc :name "Welcome to Penpot!")
        (update-in [:data :pages-index page-id :objects shape-id] dissoc :position-data))))

(defn create-welcome-file
  [cfg {:keys [id fullname] :as profile}]
  (try
    (let [cfg             (dissoc cfg ::db/conn)
          params          {:profile-id (:id profile)
                           :project-id (:default-project-id profile)}
          template-stream (tmpl/get-template-stream cfg "welcome")
          file-id         (-> (management/clone-template cfg params template-stream)
                              first)
          file-name       (str fullname "'s first file")]

      (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                        (files/rename-file conn {:id file-id :name file-name})
                        (fupdate/update-file! cfg file-id update-welcome-shape fullname)
                        (profile/update-profile-props cfg id {:welcome-file-id file-id})
                        (db/exec-one! conn [sql:mark-file-object-thumbnails-deleted file-id])
                        (db/exec-one! conn [sql:mark-file-thumbnail-deleted file-id]))))

    (catch Throwable cause
      (l/error :hint "unexpected error on create welcome file " :cause cause))))

