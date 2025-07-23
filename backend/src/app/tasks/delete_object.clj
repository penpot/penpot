;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.delete-object
  "A generic task for object deletion cascade handling"
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.db :as db]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.profile :as profile]
   [integrant.core :as ig]))

(def ^:dynamic *team-deletion* false)

(defmulti delete-object
  (fn [_ props] (:object props)))

(defmethod delete-object :file
  [{:keys [::db/conn] :as cfg} {:keys [id deleted-at]}]
  (when-let [file (db/get* conn :file {:id id} {::db/remove-deleted false})]
    (l/trc :hint "marking for deletion" :rel "file" :id (str id)
           :deleted-at (ct/format-inst deleted-at))

    (db/update! conn :file
                {:deleted-at deleted-at}
                {:id id}
                {::db/return-keys false})

    (when (and (:is-shared file)
               (not *team-deletion*))
      ;; NOTE: we don't prevent file deletion on absorb operation failure
      (try
        (db/tx-run! cfg files/absorb-library! id)
        (catch Throwable cause
          (l/warn :hint "error on absorbing library"
                  :file-id id
                  :cause cause))))

    ;; Mark file change to be deleted
    (db/update! conn :file-change
                {:deleted-at deleted-at}
                {:file-id id})

    ;; Mark file media objects to be deleted
    (db/update! conn :file-media-object
                {:deleted-at deleted-at}
                {:file-id id})

    ;; Mark thumbnails to be deleted
    (db/update! conn :file-thumbnail
                {:deleted-at deleted-at}
                {:file-id id})

    (db/update! conn :file-tagged-object-thumbnail
                {:deleted-at deleted-at}
                {:file-id id})))

(defmethod delete-object :project
  [{:keys [::db/conn] :as cfg} {:keys [id deleted-at]}]
  (l/trc :hint "marking for deletion" :rel "project" :id (str id)
         :deleted-at (ct/format-inst deleted-at))

  (db/update! conn :project
              {:deleted-at deleted-at}
              {:id id}
              {::db/return-keys false})

  (doseq [file (db/query conn :file
                         {:project-id id}
                         {::db/columns [:id :deleted-at]})]
    (delete-object cfg (assoc file
                              :object :file
                              :deleted-at deleted-at))))

(defmethod delete-object :team
  [{:keys [::db/conn] :as cfg} {:keys [id deleted-at]}]
  (l/trc :hint "marking for deletion" :rel "team" :id (str id)
         :deleted-at (ct/format-inst deleted-at))
  (db/update! conn :team
              {:deleted-at deleted-at}
              {:id id}
              {::db/return-keys false})

  (db/update! conn :team-font-variant
              {:deleted-at deleted-at}
              {:team-id id}
              {::db/return-keys false})

  (binding [*team-deletion* true]
    (doseq [project (db/query conn :project
                              {:team-id id}
                              {::db/columns [:id :deleted-at]})]
      (delete-object cfg (assoc project
                                :object :project
                                :deleted-at deleted-at)))))

(defmethod delete-object :profile
  [{:keys [::db/conn] :as cfg} {:keys [id deleted-at]}]
  (l/trc :hint "marking for deletion" :rel "profile" :id (str id)
         :deleted-at (ct/format-inst deleted-at))

  (db/update! conn :profile
              {:deleted-at deleted-at}
              {:id id}
              {::db/return-keys false})

  (doseq [team (profile/get-owned-teams conn id)]
    (delete-object cfg (assoc team
                              :object :team
                              :deleted-at deleted-at))))

(defmethod delete-object :default
  [_cfg props]
  (l/wrn :hint "not implementation found" :rel (:object props)))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as task}]
    (db/tx-run! cfg delete-object props)))
