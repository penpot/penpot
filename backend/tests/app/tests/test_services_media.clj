;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tests.test-services-media
  (:require
   [clojure.test :as t]
   [datoteka.core :as fs]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.tests.helpers :as th]
   [app.util.storage :as ust]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest media-crud
  (let [prof       (th/create-profile th/*pool* 1)
        team-id    (:default-team-id prof)
        proj       (th/create-project th/*pool* (:id prof) team-id 1)
        file       (th/create-file th/*pool* (:id prof) (:id proj) false 1)
        object-id-1 (uuid/next)
        object-id-2 (uuid/next)]

    (t/testing "create media object from url to file"
      (let [url "https://raw.githubusercontent.com/uxbox/uxbox/develop/sample_media/images/unsplash/anna-pelzer.jpg"
            data {::th/type :add-media-object-from-url
                  :id object-id-1
                  :profile-id (:id prof)
                  :file-id (:id file)
                  :is-local true
                  :url url}
            out  (th/mutation! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (t/is (= object-id-1 (get-in out [:result :id])))
        (t/is (not (nil? (get-in out [:result :name]))))
        (t/is (= "image/jpeg" (get-in out [:result :mtype])))
        (t/is (= 1024 (get-in out [:result :width])))
        (t/is (= 683 (get-in out [:result :height])))

        (t/is (string? (get-in out [:result :path])))
        (t/is (string? (get-in out [:result :thumb-path])))
        ))

    (t/testing "upload media object to file"
      (let [content {:filename "sample.jpg"
                     :tempfile (th/tempfile "app/tests/_files/sample.jpg")
                     :content-type "image/jpeg"
                     :size 312043}
            data {::th/type :upload-media-object
                  :id object-id-2
                  :profile-id (:id prof)
                  :file-id (:id file)
                  :is-local true
                  :name "testfile"
                  :content content}
            out  (th/mutation! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (t/is (= object-id-2 (get-in out [:result :id])))
        (t/is (= "testfile" (get-in out [:result :name])))
        (t/is (= "image/jpeg" (get-in out [:result :mtype])))
        (t/is (= 800 (get-in out [:result :width])))
        (t/is (= 800 (get-in out [:result :height])))

        (t/is (string? (get-in out [:result :path])))
        (t/is (string? (get-in out [:result :thumb-path])))))

    ))
