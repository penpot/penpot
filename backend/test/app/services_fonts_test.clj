;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.services-fonts-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.storage :as sto]
   [app.test-helpers :as th]
   [clojure.java.io :as io]
   [clojure.test :as t]
   [datoteka.core :as fs]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest ttf-font-upload-1
  (let [prof    (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)
        font-id (uuid/custom 10 1)

        ttfdata (-> (io/resource "app/test_files/font-1.ttf")
                    (fs/slurp-bytes))

        params  {::th/type :create-font-variant
                 :profile-id (:id prof)
                 :team-id team-id
                 :font-id font-id
                 :font-family "somefont"
                 :font-weight 400
                 :font-style "normal"
                 :data {"font/ttf" ttfdata}}
        out     (th/mutation! params)]

    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (let [result (:result out)]
      (t/is (uuid? (:id result)))
      (t/is (uuid? (:ttf-file-id result)))
      (t/is (uuid? (:otf-file-id result)))
      (t/is (uuid? (:woff1-file-id result)))
      (t/is (uuid? (:woff2-file-id result)))
      (t/are [k] (= (get params k)
                    (get result k))
        :team-id
        :font-id
        :font-family
        :font-weight
        :font-style))))

(t/deftest ttf-font-upload-2
  (let [prof    (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)
        font-id (uuid/custom 10 1)

        data    (-> (io/resource "app/test_files/font-1.woff")
                    (fs/slurp-bytes))

        params  {::th/type :create-font-variant
                 :profile-id (:id prof)
                 :team-id team-id
                 :font-id font-id
                 :font-family "somefont"
                 :font-weight 400
                 :font-style "normal"
                 :data {"font/woff" data}}
        out     (th/mutation! params)]

    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (let [result (:result out)]
      (t/is (uuid? (:id result)))
      (t/is (uuid? (:ttf-file-id result)))
      (t/is (uuid? (:otf-file-id result)))
      (t/is (uuid? (:woff1-file-id result)))
      (t/is (uuid? (:woff2-file-id result)))
      (t/are [k] (= (get params k)
                    (get result k))
        :team-id
        :font-id
        :font-family
        :font-weight
        :font-style))))





