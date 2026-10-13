;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.restore-storage-objects-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc.commands.files :as files]
   [app.srepl.main :as srepl]
   [app.storage :as sto]
   [app.system :as sys]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; Restoring a file brings its rows back to life, so the storage objects those
;; rows reference must stop being marked for deletion: otherwise the storage GC
;; hits the foreign key and skips them forever.

(defn- put-object!
  ([bucket]
   (put-object! bucket {}))
  ([bucket metadata]
   (sto/put-object! (::sto/storage th/*system*)
                    (merge {::sto/content (sto/content "sobject")
                            :content-type "text/plain"
                            :bucket bucket}
                           metadata))))

(defn- find-object
  [id]
  (th/db-get :storage-object {:id id} {::db/remove-deleted false}))

(defn- mark-object!
  [id]
  (th/db-update! :storage-object {:deleted-at (ct/in-past {:days 1})} {:id id}))

(defn- restore-file!
  [file-id]
  (db/tx-run! th/*system*
              (fn [{:keys [::db/conn]}]
                (#'files/restore-files conn [file-id]))))

(t/deftest restoring-a-file-unmarks-the-objects-its-rows-reference
  (let [profile   (th/create-profile* 1)
        file      (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)})

        ;; The media object's thumbnail points at an object of another bucket:
        ;; that is the anomaly found in production. The marking task decides
        ;; with the table of the object's bucket, so it does not see that
        ;; reference and ends up marking an object that a live row still uses.
        media-id  (:id (put-object! "file-media-object"))
        thumb-id  (:id (put-object! "file-object-thumbnail"))
        bystander (:id (put-object! "file-object-thumbnail"))
        mobject   (th/create-file-media-object* {:file-id (:id file)
                                                 :media-id media-id})]

    (th/db-update! :file-media-object
                   {:thumbnail-id thumb-id
                    :deleted-at (ct/in-past {:days 1})}
                   {:id (:id mobject)})

    (t/testing "the fixture reproduces the anomaly"
      (t/is (= "file-object-thumbnail"
               (:bucket (meta (sto/get-object (::sto/storage th/*system*) thumb-id))))))

    ;; The objects end up marked as deleted, as the marking task leaves them.
    (mark-object! media-id)
    (mark-object! thumb-id)
    (mark-object! bystander)

    (restore-file! (:id file))

    (t/testing "the objects referenced by the restored rows are alive again"
      (t/is (nil? (:deleted-at (find-object media-id))))
      (t/is (nil? (:deleted-at (find-object thumb-id)))))

    (t/testing "objects of other rows are left alone"
      (t/is (some? (:deleted-at (find-object bystander)))))))

(t/deftest restoring-a-file-unmarks-the-objects-of-its-data
  (let [profile  (th/create-profile* 1)
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})
        data-id  (uuid/next)
        ;; file-data objects carry the ids of their row: the GC resolves the
        ;; reference with that pair.
        sobject  (:id (put-object! "file-data" {:file-id (:id file)
                                                :id data-id}))]

    (th/db-update! :file {:deleted-at (ct/in-past {:days 1})} {:id (:id file)})
    (th/db-insert! :file-data
                   {:file-id (:id file)
                    :id data-id
                    :type "main"
                    :backend "fs"
                    :deleted-at (ct/in-past {:days 1})
                    :metadata (db/json {"storage-ref-id" (str sobject)})})
    (mark-object! sobject)

    (restore-file! (:id file))

    (t/is (nil? (:deleted-at (find-object sobject))))))

(t/deftest restoring-a-team-unmarks-the-objects-of-its-fonts
  (let [profile  (th/create-profile* 1)
        team     (th/create-team* 1 {:profile-id (:id profile)})
        sobject  (:id (put-object! "team-font-variant"))]

    (th/db-insert! :team-font-variant
                   {:id (uuid/next)
                    :team-id (:id team)
                    :profile-id (:id profile)
                    :font-id (uuid/next)
                    :font-family "somefont"
                    :font-weight 400
                    :font-style "normal"
                    :deleted-at (ct/in-past {:days 1})
                    :woff1-file-id sobject})
    (mark-object! sobject)

    (with-redefs [sys/system th/*system*]
      (srepl/restore-team! (:id team)))

    (t/is (nil? (:deleted-at (find-object sobject))))))
