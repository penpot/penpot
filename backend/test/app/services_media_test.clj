;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.services-media-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.storage :as sto]
   [app.test-helpers :as th]
   [clojure.test :as t]
   [datoteka.core :as fs]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest media-object-from-url
  (let [prof   (th/create-profile* 1)
        proj   (th/create-project* 1 {:profile-id (:id prof)
                                      :team-id (:default-team-id prof)})
        file   (th/create-file* 1 {:profile-id (:id prof)
                                   :project-id (:default-project-id prof)
                                   :is-shared false})
        url    "https://raw.githubusercontent.com/uxbox/uxbox/develop/sample_media/images/unsplash/anna-pelzer.jpg"
        params {::th/type :create-file-media-object-from-url
                :profile-id (:id prof)
                :file-id    (:id file)
                :is-local   true
                :url        url}
        out   (th/mutation! params)]

    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (let [{:keys [media-id thumbnail-id] :as result} (:result out)]
      (t/is (= (:id file) (:file-id result)))
      (t/is (= 1024 (:width result)))
      (t/is (= 683  (:height result)))
      (t/is (= "image/jpeg" (:mtype result)))
      (t/is (uuid? media-id))
      (t/is (uuid? thumbnail-id))
      (let [storage (:app.storage/storage th/*system*)
            mobj1   (sto/get-object storage media-id)
            mobj2   (sto/get-object storage thumbnail-id)]
        (t/is (sto/storage-object? mobj1))
        (t/is (sto/storage-object? mobj2))
        (t/is (= 122785 (:size mobj1)))
        (t/is (= 3303   (:size mobj2)))))
    ))

(t/deftest media-object-upload
  (let [prof   (th/create-profile* 1)
        proj   (th/create-project* 1 {:profile-id (:id prof)
                                      :team-id (:default-team-id prof)})
        file   (th/create-file* 1 {:profile-id (:id prof)
                                   :project-id (:default-project-id prof)
                                   :is-shared false})
        mfile  {:filename "sample.jpg"
                :tempfile (th/tempfile "app/test_files/sample.jpg")
                :content-type "image/jpeg"
                :size 312043}

        params {::th/type :upload-file-media-object
                :profile-id (:id prof)
                :file-id (:id file)
                :is-local true
                :name "testfile"
                :content mfile}
        out    (th/mutation! params)]

    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (let [{:keys [media-id thumbnail-id] :as result} (:result out)]
      (t/is (= (:id file) (:file-id result)))
      (t/is (= 800 (:width result)))
      (t/is (= 800  (:height result)))
      (t/is (= "image/jpeg" (:mtype result)))
      (t/is (uuid? media-id))
      (t/is (uuid? thumbnail-id))
      (let [storage (:app.storage/storage th/*system*)
            mobj1   (sto/get-object storage media-id)
            mobj2   (sto/get-object storage thumbnail-id)]
        (t/is (sto/storage-object? mobj1))
        (t/is (sto/storage-object? mobj2))
        (t/is (= 312043 (:size mobj1)))
        (t/is (= 3887   (:size mobj2)))))
    ))
