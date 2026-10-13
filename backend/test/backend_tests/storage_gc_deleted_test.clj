;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.storage-gc-deleted-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; The GC processes the pending objects in chunks of 25 (see `clean-deleted`), so
;; a chunk boundary is what separates a blocked object from the objects of the
;; next chunk.

(def ^:private chunk-size 25)

(defn- put-object!
  "Puts a tiny object in storage and returns it."
  [deleted-at]
  (let [sobject (sto/put-object! (::sto/storage th/*system*)
                                 {::sto/content (sto/content "sobject")
                                  :content-type "text/plain"
                                  :bucket "file-media-object"})]
    (th/db-update! :storage-object {:deleted-at deleted-at} {:id (:id sobject)})
    sobject))

(defn- blocked-object!
  "Puts an object that cannot be deleted because a `file_media_object` points
  at it as its thumbnail (a `NO ACTION` foreign key)."
  [file-id]
  (let [media   (sto/put-object! (::sto/storage th/*system*)
                                 {::sto/content (sto/content "media")
                                  :content-type "text/plain"
                                  :bucket "file-media-object"})
        blocked (put-object! (ct/in-past "30m"))]

    (th/db-insert! :file-media-object
                   {:id (uuid/next)
                    :file-id file-id
                    :is-local true
                    :name "sample"
                    :media-id (:id media)
                    :thumbnail-id (:id blocked)
                    :width 100
                    :height 100
                    :mtype "image/svg+xml"})

    blocked))

(defn- find-object
  [id]
  (th/db-get :storage-object {:id id} {::db/remove-deleted false}))

(t/deftest deletes-the-pending-objects
  (let [objects (mapv (fn [_] (put-object! (ct/in-past "10m"))) (range 3))
        result  (th/run-task! :storage-gc-deleted {})]

    (t/is (= 3 (:deleted result)))

    (doseq [sobject objects]
      (t/is (nil? (find-object (:id sobject)))))))

(t/deftest blocked-chunk-does-not-stop-the-run
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})

        ;; The blocked object goes first in `deleted_at` order, so it lands in
        ;; the first chunk, together with the objects that fill it up. The last
        ;; object of the batch lands in the next chunk: it is the one that
        ;; proves the run kept going after the failure.
        blocked (blocked-object! (:id file))
        _       (mapv (fn [_] (put-object! (ct/in-past "20m"))) (range (dec chunk-size)))
        last-id (:id (put-object! (ct/in-past "5m")))

        result  (th/run-task! :storage-gc-deleted {})]

    (t/testing "the run finishes and only the object of the second chunk is deleted"
      (t/is (= 1 (:deleted result))))

    (t/testing "the blocked object is still pending"
      (t/is (some? (find-object (:id blocked)))))

    (t/testing "the object of the next chunk is gone"
      (t/is (nil? (find-object last-id))))))
