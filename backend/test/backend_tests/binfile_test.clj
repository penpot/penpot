;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.binfile-test
  "Internal binfile test, no RPC involved"
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v3 :as v3]
   [app.common.data.macros :as dm]
   [app.common.features :as cfeat]
   [app.common.json :as json]
   [app.common.math :as mth]
   [app.common.pprint :as pp]
   [app.common.thumbnails :as thc]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io])
  (:import
   java.util.zip.ZipFile))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- update-file!
  [& {:keys [profile-id file-id changes revn] :or {revn 0}}]
  (let [params {::th/type :update-file
                ::rpc/profile-id profile-id
                :id file-id
                :session-id (uuid/random)
                :revn revn
                :vern 0
                :features cfeat/supported-features
                :changes changes}
        out    (th/command! params)]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (:result out)))

(defn- prepare-simple-file
  [profile]
  (let [page-id-1 (uuid/custom 1 1)
        page-id-2 (uuid/custom 1 2)
        shape-id  (uuid/custom 2 1)
        file      (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)
                                      :is-shared false})]
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-page
       :name "test 1"
       :id page-id-1}
      {:type :add-page
       :name "test 2"
       :id page-id-2}])

    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-obj
       :page-id page-id-1
       :id shape-id
       :parent-id uuid/zero
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id shape-id
              :name "image"
              :frame-id uuid/zero
              :parent-id uuid/zero
              :type :rect})}])

    (dissoc file :data)))

(defn- prepare-simple-file-with-ids
  [profile]
  (let [page-id-1 (uuid/custom 1 1)
        page-id-2 (uuid/custom 1 2)
        shape-id  (uuid/custom 2 1)
        file      (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)
                                      :is-shared false})]
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-page
       :name "test 1"
       :id page-id-1}
      {:type :add-page
       :name "test 2"
       :id page-id-2}])

    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-obj
       :page-id page-id-1
       :id shape-id
       :parent-id uuid/zero
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id shape-id
              :name "image"
              :frame-id uuid/zero
              :parent-id uuid/zero
              :type :rect})}])

    {:file-id (:id file)
     :page-id-1 page-id-1
     :page-id-2 page-id-2
     :shape-id shape-id}))

(t/deftest export-binfile-v3
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        output  (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream output))

    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/input output)
                     (v3/import-files!))]
      (t/is (= (count result) 1))
      (t/is (every? uuid? result)))))

(t/deftest export-binfile-v3-compact
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id-1 shape-id]} (prepare-simple-file-with-ids profile)
        file    {:id file-id}
        output  (tmp/tempfile :suffix ".zip")]

    (with-redefs [cf/flags (conj cf/flags :binfile-v3-compact)]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/ids #{file-id})
           (assoc ::bfc/embed-assets false)
           (assoc ::bfc/include-libraries false))
       (io/output-stream output)))

    ;; Verify manifest version
    (let [manifest (v3/get-manifest (str output))]
      (t/is (= 2 (:version manifest))))

    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/input output)
                     (v3/import-files!))]
      (t/is (= (count result) 1))
      (t/is (every? uuid? result))

      ;; Verify imported data has correct shapes with preserved geometry
      (let [imported-id  (first result)
            imported-data (-> (bfc/get-file th/*system* imported-id {:realize? true})
                              :data)
            pages-index   (get imported-data :pages-index)
            page          (get pages-index page-id-1)
            shape         (get-in page [:objects shape-id])]
        (t/is page "page should exist after import")
        (t/is shape "shape should exist after import")
        (t/is (= :rect (:type shape)))
        (t/is (mth/close? (:x shape) 0))
        (t/is (mth/close? (:y shape) 0))
        (t/is (mth/close? (:width shape) 0.01))
        (t/is (mth/close? (:height shape) 0.01))))))

(t/deftest export-binfile-v3-compact-round-trip
  (let [profile    (th/create-profile* 1)
        {:keys [file-id page-id-1 shape-id]} (prepare-simple-file-with-ids profile)
        output1    (tmp/tempfile :suffix ".zip")
        output2    (tmp/tempfile :suffix ".zip")]

    (with-redefs [cf/flags (conj cf/flags :binfile-v3-compact)]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/ids #{file-id})
           (assoc ::bfc/embed-assets false)
           (assoc ::bfc/include-libraries false))
       (io/output-stream output1)))

    ;; Verify compact manifest
    (let [manifest (v3/get-manifest (str output1))]
      (t/is (= 2 (:version manifest))))

    (let [result      (-> th/*system*
                          (assoc ::bfc/project-id (:default-project-id profile))
                          (assoc ::bfc/profile-id (:id profile))
                          (assoc ::bfc/input output1)
                          (v3/import-files!))
          imported-id (first result)]

      ;; Re-export as legacy (without compact flag) and verify
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/ids #{imported-id})
           (assoc ::bfc/embed-assets false)
           (assoc ::bfc/include-libraries false))
       (io/output-stream output2))

      (let [manifest2 (v3/get-manifest (str output2))
            result2   (-> th/*system*
                          (assoc ::bfc/project-id (:default-project-id profile))
                          (assoc ::bfc/profile-id (:id profile))
                          (assoc ::bfc/input output2)
                          (v3/import-files!))]
        (t/is (= 1 (:version manifest2)) "re-export without flag should be legacy")
        (t/is (= (count result2) 1))
        (t/is (every? uuid? result2))

        ;; Verify shapes survive the compact -> import -> legacy re-export round-trip
        (let [reimported-id   (first result2)
              reimported-data (-> (bfc/get-file th/*system* reimported-id {:realize? true})
                                  :data)
              pages-index     (get reimported-data :pages-index)
              page            (get pages-index page-id-1)
              shape           (get-in page [:objects shape-id])]
          (t/is page "page should exist after round-trip")
          (t/is shape "shape should exist after round-trip")
          (t/is (= :rect (:type shape)))
          (t/is (mth/close? (:x shape) 0))
          (t/is (mth/close? (:y shape) 0))
          (t/is (mth/close? (:width shape) 0.01))
          (t/is (mth/close? (:height shape) 0.01))))))

  (t/deftest export-binfile-v3-compact-page-structure
    (let [profile (th/create-profile* 1)
          {:keys [file-id page-id-1 shape-id]} (prepare-simple-file-with-ids profile)
          output  (tmp/tempfile :suffix ".zip")]

      (with-redefs [cf/flags (conj cf/flags :binfile-v3-compact)]
        (v3/export-files!
         (-> th/*system*
             (assoc ::bfc/ids #{file-id})
             (assoc ::bfc/embed-assets false)
             (assoc ::bfc/include-libraries false))
         (io/output-stream output)))

      (with-open [^ZipFile zip (ZipFile. (fs/file output))]
        (let [entries (iterator-seq (.entries zip))

              ;; Page entry should exist with embedded objects
              page-path (str "files/" file-id "/pages/" page-id-1 ".json")
              page-entry (.getEntry zip page-path)]
          (t/is page-entry "compact page entry should exist")

          ;; Per-shape entries should NOT exist in compact format
          (doseq [^java.util.zip.ZipEntry entry entries]
            (let [name (.getName entry)]
              (t/is (not (.startsWith name (str "files/" file-id "/pages/" page-id-1 "/")))
                    (str "should not have per-shape entries: " name))))

          ;; Verify page entry contains objects
          (with-open [reader (io/reader (.getInputStream zip page-entry))]
            (let [page-data (json/read reader :key-fn json/read-kebab-key)]
              (t/is (contains? page-data :objects)
                    "compact page should contain embedded objects")
              (t/is (> (count (:objects page-data)) 0)
                    "compact page objects should not be empty")
              (t/is (contains? (:objects page-data) shape-id)
                    "compact page should contain the shape"))))))))
