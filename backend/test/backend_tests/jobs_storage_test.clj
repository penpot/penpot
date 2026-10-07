;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.jobs-storage-test
  "The storage helpers of the unified jobs substrate: the objects a job
  owns through `job.resource_id`."
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs.storage :as js]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial th/database-reset th/clean-storage))

(defn- get-storage
  []
  (assoc (:app.storage/storage th/*system*) ::sto/backend :fs))

(defn- make-cfg
  []
  {::db/pool th/*pool*
   ::sto/storage (get-storage)})

(defn- get-object-row
  "The `storage_object` row, deleted rows included."
  [id]
  (th/db-get :storage-object {:id id} {::db/remove-deleted false}))

(defn- put-artifact
  [cfg profile-id content]
  (js/put-resource cfg profile-id {:content (sto/content content)
                                   :filename "artifact.penpot"
                                   :mtype "application/zip"}))

(t/deftest put-resource-stores-the-artifact-for-its-owner
  (let [cfg        (make-cfg)
        profile-id (:id (th/create-profile* 1))
        result     (put-artifact cfg profile-id "penpot-bytes")
        object     (sto/get-object (get-storage) (:resource-id result))
        row        (get-object-row (:resource-id result))]

    (t/testing "the object lives in the job-resource bucket"
      (t/is (= sto/job-resource-bucket (:bucket (meta object)))))

    (t/testing "the owner and the content type travel in the metadata"
      (t/is (= profile-id (:profile-id (meta object))))
      (t/is (= "application/zip" (:content-type (meta object)))))

    (t/testing "the object is readable: retention comes from the job row"
      (t/is (nil? (:deleted-at row))))

    (t/testing "it is touched right away so a crash leaves it reclaimable"
      (t/is (some? (:touched-at row))))

    (t/testing "the descriptor identifies the artifact"
      (t/is (uuid? (:resource-id result)))
      (t/is (= 12 (:size result)))
      (t/is (= "artifact.penpot" (:filename result)))
      (t/is (= "application/zip" (:mtype result)))
      (t/is (str/includes? (str (:resource-uri result))
                           (str (:resource-id result)))))))

(t/deftest put-resource-requires-an-owner
  (let [cfg (make-cfg)]
    (t/testing "an artifact without an owner is a caller bug"
      (t/is (thrown? Throwable
                     (js/put-resource cfg nil {:content (sto/content "x")
                                               :filename "x.penpot"
                                               :mtype "application/zip"}))))))

(t/deftest load-input-gives-the-handler-a-local-path
  (let [cfg        (make-cfg)
        profile-id (:id (th/create-profile* 1))
        staged     (put-artifact cfg profile-id "package-bytes")
        context    {:id          (uuid/next)
                    :resource-id (:resource-id staged)
                    :profile-id  profile-id}
        path       (js/load-input cfg context)]

    (t/is (some? path))
    (t/is (= "package-bytes" (slurp (fs/file path))))))

(t/deftest load-input-refuses-an-input-of-another-profile
  (let [cfg        (make-cfg)
        owner      (:id (th/create-profile* 1))
        stranger   (:id (th/create-profile* 2))
        staged     (put-artifact cfg owner "package-bytes")
        context    {:id          (uuid/next)
                    :resource-id (:resource-id staged)
                    :profile-id  stranger}]

    (t/testing "a handler never reads a package that is not its job's"
      (t/is (thrown? Exception (js/load-input cfg context))))))

(t/deftest load-input-refuses-a-missing-or-released-input
  (let [cfg        (make-cfg)
        profile-id (:id (th/create-profile* 1))
        staged     (put-artifact cfg profile-id "package-bytes")
        context    {:id          (uuid/next)
                    :resource-id (:resource-id staged)
                    :profile-id  profile-id}]

    (t/testing "a job without a resource reference has nothing to load"
      (t/is (thrown? Exception (js/load-input cfg (dissoc context :resource-id)))))

    (t/testing "an input that was already released cannot be read again"
      (t/is (true? (js/release-input cfg context)))
      (t/is (thrown? Exception (js/load-input cfg context))))))

(t/deftest release-input-is-idempotent-and-never-throws
  (let [cfg        (make-cfg)
        profile-id (:id (th/create-profile* 1))
        staged     (put-artifact cfg profile-id "package-bytes")
        context    {:id          (uuid/next)
                    :resource-id (:resource-id staged)
                    :profile-id  profile-id}]

    (t/is (true? (js/release-input cfg context)))
    (t/is (some? (:deleted-at (get-object-row (:resource-id staged)))))

    (t/testing "releasing twice is a no-op, not an error"
      (t/is (false? (js/release-input cfg context))))

    (t/testing "releasing a job without a resource is a no-op too"
      (t/is (nil? (js/release-input cfg (dissoc context :resource-id)))))))

(t/deftest release-input-clears-the-reference-of-the-job
  ;; a job that no longer owns its package must not keep pointing at a
  ;; deleted object: the object and the pointer are dropped together
  (let [cfg        (make-cfg)
        profile-id (:id (th/create-profile* 1))
        staged     (put-artifact cfg profile-id "package-bytes")
        job-id     (uuid/next)
        context    {:id          job-id
                    :resource-id (:resource-id staged)
                    :profile-id  profile-id}]

    (th/db-insert! :job {:id           job-id
                         :name         "import-binfile"
                         :tenant       (cf/get :tenant)
                         :queue        "binfile"
                         :params       (db/json {})
                         :priority     100
                         :max-retries  0
                         :retry-num    0
                         :status       "running"
                         :profile-id   profile-id
                         :resource-id  (:resource-id staged)
                         :scheduled-at (ct/now)
                         :created-at   (ct/now)
                         :modified-at  (ct/now)})

    (t/is (true? (js/release-input cfg context)))

    (t/testing "the object is marked as deleted"
      (t/is (some? (:deleted-at (get-object-row (:resource-id staged))))))

    (t/testing "and the job row no longer points at it"
      (t/is (nil? (:resource-id (th/db-get :job {:id job-id})))))

    (t/testing "releasing twice leaves the row alone"
      (t/is (false? (js/release-input cfg context)))
      (t/is (nil? (:resource-id (th/db-get :job {:id job-id})))))))

(t/deftest release-resource-releases-a-package-no-job-owns
  ;; the creation path stages the package before the job exists: when the
  ;; submit fails the package is released with no job id, so the object
  ;; goes and no row may be touched by it
  (let [cfg        (make-cfg)
        profile-id (:id (th/create-profile* 1))
        staged     (put-artifact cfg profile-id "package-bytes")
        job-id     (uuid/next)]
    (th/db-insert! :job {:id           job-id
                         :name         "import-binfile"
                         :tenant       (cf/get :tenant)
                         :queue        "binfile"
                         :params       (db/json {})
                         :priority     100
                         :max-retries  0
                         :retry-num    0
                         :status       "running"
                         :profile-id   profile-id
                         :resource-id  nil
                         :scheduled-at (ct/now)
                         :created-at   (ct/now)
                         :modified-at  (ct/now)})

    (let [before (th/db-get :job {:id job-id})]
      (t/is (true? (js/release-resource cfg (:resource-id staged))))

      (t/testing "the object is marked as deleted"
        (t/is (some? (:deleted-at (get-object-row (:resource-id staged))))))

      (t/testing "and the unrelated job row is exactly as it was"
        (t/is (= before (th/db-get :job {:id job-id})))))))
