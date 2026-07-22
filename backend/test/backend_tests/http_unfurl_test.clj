;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.http-unfurl-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.unfurl :as unfurl]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [yetti.response :as-alias yres]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial
                       th/database-reset
                       th/clean-storage))

(def ^:private default-title
  "Penpot | Full-stack design")

(defn- run-handler
  [query-params]
  (let [cfg {::db/pool (:app.db/pool th/*system*)}]
    (#'unfurl/handler cfg {:query-params query-params})))

(defn- create-file-thumbnail!
  [file-id]
  (let [storage (::sto/storage th/*system*)
        object  (sto/put-object! storage {::sto/content (sto/content "thumbnail data")
                                          :bucket "file-thumbnail"
                                          :content-type "image/png"})]
    (db/insert! (:app.db/pool th/*system*) :file-thumbnail
                {:file-id file-id
                 :revn 1
                 :media-id (:id object)})
    object))

(t/deftest unfurl-without-params
  (let [response (run-handler {})]
    (t/is (= 200 (::yres/status response)))
    (t/is (str/includes? (::yres/body response) default-title))
    (t/is (str/includes? (::yres/body response) "/images/penpot-link-preview.png"))))

(t/deftest unfurl-file-without-thumbnail
  (let [profile  (th/create-profile* 1)
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})
        response (run-handler {:file-id (str (:id file))})]
    (t/is (= 200 (::yres/status response)))
    (t/is (str/includes? (::yres/body response) (str (:name file) " | Penpot")))
    (t/is (str/includes? (::yres/body response) "/images/penpot-link-preview.png"))))

(t/deftest unfurl-file-with-thumbnail
  (let [profile  (th/create-profile* 1)
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})
        object   (create-file-thumbnail! (:id file))
        response (run-handler {:file-id (str (:id file))})]
    (t/is (= 200 (::yres/status response)))
    (t/is (str/includes? (::yres/body response) (str (:name file) " | Penpot")))
    (t/is (str/includes? (::yres/body response) (str "/assets/by-id/" (:id object))))))

(t/deftest unfurl-non-existent-file
  (let [response (run-handler {:file-id (str (uuid/next))})]
    (t/is (= 200 (::yres/status response)))
    (t/is (str/includes? (::yres/body response) default-title))))

(t/deftest unfurl-invalid-file-id
  (let [response (run-handler {:file-id "not-a-uuid"})]
    (t/is (= 200 (::yres/status response)))
    (t/is (str/includes? (::yres/body response) default-title))))

(t/deftest unfurl-team-link
  (let [response (run-handler {:team-id (str (uuid/next))})]
    (t/is (= 200 (::yres/status response)))
    (t/is (str/includes? (::yres/body response) "Team dashboard | Penpot"))))

(t/deftest unfurl-project-link
  (let [response (run-handler {:team-id (str (uuid/next))
                               :project-id (str (uuid/next))})]
    (t/is (= 200 (::yres/status response)))
    (t/is (str/includes? (::yres/body response) "Project | Penpot"))))

(t/deftest unfurl-flag-disabled
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    (with-redefs [cf/flags (disj cf/flags :link-unfurl)]
      (let [response (run-handler {:file-id (str (:id file))})]
        (t/is (= 200 (::yres/status response)))
        (t/is (str/includes? (::yres/body response) default-title))
        (t/is (not (str/includes? (::yres/body response) (:name file))))))))
