;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.http-link-preview-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.link-preview :as link-preview]
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
    (#'link-preview/handler cfg {:query-params query-params})))

(defn- create-file-thumbnail!
  [file-id]
  (let [storage (::sto/storage th/*system*)
        object  (sto/put-object! storage {::sto/content (sto/content (th/tempfile "backend_tests/test_files/sample.png"))
                                          :bucket "file-thumbnail"
                                          :content-type "image/png"})]
    (db/insert! (:app.db/pool th/*system*) :file-thumbnail
                {:file-id file-id
                 :revn 1
                 :media-id (:id object)})
    object))

(t/deftest link-preview-without-params
  (let [response (run-handler {})]
    (t/is (= 200 (::yres/status response)))
    (t/is (str/includes? (::yres/body response) default-title))
    (t/is (str/includes? (::yres/body response) "/images/penpot-link-preview.png"))))

(t/deftest link-preview-file-without-thumbnail
  (let [profile  (th/create-profile* 1)
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})]
    (with-redefs [cf/flags (conj cf/flags :link-preview)]
      (let [response (run-handler {:file-id (str (:id file))})]
        (t/is (= 200 (::yres/status response)))
        (t/is (str/includes? (::yres/body response) (str (:name file) " | Penpot")))
        (t/is (str/includes? (::yres/body response) "/images/penpot-link-preview.png"))))))

(t/deftest link-preview-file-with-thumbnail
  (let [profile  (th/create-profile* 1)
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})
        object   (create-file-thumbnail! (:id file))]
    (with-redefs [cf/flags (conj cf/flags :link-preview)]
      (let [response (run-handler {:file-id (str (:id file))})]
        (t/is (= 200 (::yres/status response)))
        (t/is (str/includes? (::yres/body response) (str (:name file) " | Penpot")))
        (t/is (str/includes? (::yres/body response) (str "/assets/by-id/" (:id object))))))))

(t/deftest link-preview-non-existent-file
  (let [response (run-handler {:file-id (str (uuid/next))})]
    (t/is (= 200 (::yres/status response)))
    (t/is (str/includes? (::yres/body response) default-title))))

(t/deftest link-preview-invalid-file-id
  (let [response (run-handler {:file-id "not-a-uuid"})]
    (t/is (= 200 (::yres/status response)))
    (t/is (str/includes? (::yres/body response) default-title))))

(t/deftest link-preview-team-link
  (with-redefs [cf/flags (conj cf/flags :link-preview)]
    (let [response (run-handler {:team-id (str (uuid/next))})]
      (t/is (= 200 (::yres/status response)))
      (t/is (str/includes? (::yres/body response) "Team dashboard | Penpot")))))

(t/deftest link-preview-project-link
  (with-redefs [cf/flags (conj cf/flags :link-preview)]
    (let [response (run-handler {:team-id (str (uuid/next))
                                 :project-id (str (uuid/next))})]
      (t/is (= 200 (::yres/status response)))
      (t/is (str/includes? (::yres/body response) "Project | Penpot")))))

(t/deftest link-preview-flag-disabled
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    (with-redefs [cf/flags (disj cf/flags :link-preview)]
      (let [response (run-handler {:file-id (str (:id file))})]
        (t/is (= 200 (::yres/status response)))
        (t/is (str/includes? (::yres/body response) default-title))
        (t/is (not (str/includes? (::yres/body response) (:name file))))))))

(t/deftest link-preview-deleted-file
  ;; A deleted file never leaks its name; crawlers get the generic card.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    (th/mark-file-deleted* {:id (:id file)})
    (with-redefs [cf/flags (conj cf/flags :link-preview)]
      (let [response (run-handler {:file-id (str (:id file))})]
        (t/is (= 200 (::yres/status response)))
        (t/is (str/includes? (::yres/body response) default-title))
        (t/is (not (str/includes? (::yres/body response) (:name file))))))))

(t/deftest link-preview-file-with-only-deleted-thumbnail
  ;; A file whose only thumbnail is deleted keeps its title but falls back
  ;; to the default image.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        object  (create-file-thumbnail! (:id file))]
    (db/update! th/*system* :file-thumbnail
                {:deleted-at (ct/now)}
                {:file-id (:id file)})
    (with-redefs [cf/flags (conj cf/flags :link-preview)]
      (let [response (run-handler {:file-id (str (:id file))})]
        (t/is (= 200 (::yres/status response)))
        (t/is (str/includes? (::yres/body response) (str (:name file) " | Penpot")))
        (t/is (str/includes? (::yres/body response) "/images/penpot-link-preview.png"))
        (t/is (not (str/includes? (::yres/body response) (str (:id object)))))))))

(t/deftest link-preview-file-picks-latest-thumbnail
  ;; With several thumbnail revisions, the latest non-deleted one wins.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        pool    (:app.db/pool th/*system*)
        storage (::sto/storage th/*system*)
        old     (sto/put-object! storage {::sto/content (sto/content (th/tempfile "backend_tests/test_files/sample.jpg"))
                                          :bucket "file-thumbnail"
                                          :content-type "image/jpeg"})
        latest  (sto/put-object! storage {::sto/content (sto/content (th/tempfile "backend_tests/test_files/sample.png"))
                                          :bucket "file-thumbnail"
                                          :content-type "image/png"})]
    (db/insert! pool :file-thumbnail
                {:file-id (:id file)
                 :revn 1
                 :media-id (:id old)
                 :deleted-at (ct/now)})
    (db/insert! pool :file-thumbnail
                {:file-id (:id file)
                 :revn 2
                 :media-id (:id latest)})
    (with-redefs [cf/flags (conj cf/flags :link-preview)]
      (let [response (run-handler {:file-id (str (:id file))})]
        (t/is (= 200 (::yres/status response)))
        (t/is (str/includes? (::yres/body response) (str "/assets/by-id/" (:id latest))))
        (t/is (not (str/includes? (::yres/body response) (str (:id old)))))))))

(t/deftest link-preview-escapes-file-name
  ;; Hostile file names are HTML-escaped in the rendered meta tags.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :name "<script>alert(\"x\")</script> & co"})]
    (with-redefs [cf/flags (conj cf/flags :link-preview)]
      (let [response (run-handler {:file-id (str (:id file))})]
        (t/is (= 200 (::yres/status response)))
        (t/is (not (str/includes? (::yres/body response) "<script>alert")))
        (t/is (str/includes? (::yres/body response) "&lt;script&gt;"))))))

(t/deftest link-preview-response-headers
  ;; The preview page is explicit HTML, never cached nor indexed.
  (let [response (run-handler {})]
    (t/is (= 200 (::yres/status response)))
    (t/is (= "text/html; charset=utf-8"
             (get (::yres/headers response) "content-type")))
    (t/is (str/includes? (get (::yres/headers response) "cache-control") "no-store"))
    (t/is (str/includes? (::yres/body response) "name=\"robots\" content=\"noindex\""))))

(t/deftest link-preview-file-beats-project-and-team
  ;; With file, project and team ids present, the file card wins.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    (with-redefs [cf/flags (conj cf/flags :link-preview)]
      (let [response (run-handler {:file-id (str (:id file))
                                   :project-id (str (uuid/next))
                                   :team-id (str (uuid/next))})]
        (t/is (= 200 (::yres/status response)))
        (t/is (str/includes? (::yres/body response) (str (:name file) " | Penpot")))
        (t/is (not (str/includes? (::yres/body response) "Project | Penpot")))))))

(t/deftest link-preview-malformed-file-id-with-project
  ;; A present-but-malformed file-id is decisive: it renders the generic
  ;; card instead of falling through to the project card.
  (with-redefs [cf/flags (conj cf/flags :link-preview)]
    (let [response (run-handler {:file-id "not-a-uuid"
                                 :project-id (str (uuid/next))})]
      (t/is (= 200 (::yres/status response)))
      (t/is (str/includes? (::yres/body response) default-title))
      (t/is (not (str/includes? (::yres/body response) "Project | Penpot"))))))

(t/deftest link-preview-absent-file-id-with-project
  ;; Without any file-id key, the project card still applies.
  (with-redefs [cf/flags (conj cf/flags :link-preview)]
    (let [response (run-handler {:project-id (str (uuid/next))})]
      (t/is (= 200 (::yres/status response)))
      (t/is (str/includes? (::yres/body response) "Project | Penpot")))))
