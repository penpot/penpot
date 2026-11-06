;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.binfile-test
  "Internal binfile test, no RPC involved"
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v1 :as v1]
   [app.binfile.v3 :as v3]
   [app.common.features :as cfeat]
   [app.common.files.validate :as cfv]
   [app.common.pprint :as pp]
   [app.common.thumbnails :as thc]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [backend-tests.helpers :as th]
   [backend-tests.storage-test :as stt]
   [clojure.data.json :as json]
   [clojure.java.io :as jio]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io])
  (:import
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.io.DataInputStream
   java.io.DataOutputStream
   java.io.OutputStreamWriter
   java.io.Writer
   java.util.zip.Deflater
   java.util.zip.ZipEntry
   java.util.zip.ZipFile
   java.util.zip.ZipOutputStream))

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

(defn- export-file
  [file & {:keys [include-libraries]}]
  (let [output (tmp/tempfile :suffix ".zip")]
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries (boolean include-libraries)))
     (io/output-stream output))
    output))

(defn- import-bundle
  [profile path]
  (-> th/*system*
      (assoc ::bfc/project-id (:default-project-id profile))
      (assoc ::bfc/profile-id (:id profile))
      (assoc ::bfc/input path)
      (v3/import-files!)))

(defn- read-manifest
  [path]
  (with-open [zip (ZipFile. (.toFile ^java.nio.file.Path path))]
    (let [entry (.getEntry zip "manifest.json")]
      (with-open [input (.getInputStream zip entry)]
        (json/read-str (slurp input :encoding "UTF-8"))))))

(defn- tamper-manifest-relations
  [src dst relations]
  (with-open [zin  (ZipFile. (.toFile ^java.nio.file.Path src))
              zout (ZipOutputStream. (io/output-stream dst))]
    (doseq [entry (enumeration-seq (.entries zin))]
      (let [name (.getName ^ZipEntry entry)]
        (if (= name "manifest.json")
          (let [manifest (-> (slurp (.getInputStream zin entry) :encoding "UTF-8")
                             (json/read-str)
                             (assoc "relations" relations))]
            (.putNextEntry zout (ZipEntry. name))
            (.write zout (.getBytes (json/write-str manifest) "UTF-8"))
            (.closeEntry zout))
          (do
            (.putNextEntry zout (ZipEntry. name))
            (with-open [input (.getInputStream zin entry)]
              (io/copy input zout))
            (.closeEntry zout)))))))

(defn- prepare-victim-file
  [n]
  (let [profile  (th/create-profile* n)
        team     (th/create-team* n {:profile-id (:id profile)})
        project  (th/create-project* n {:team-id (:id team)
                                        :profile-id (:id profile)})
        file     (th/create-file* n {:profile-id (:id profile)
                                     :project-id (:id project)})]
    [profile file]))

(def ^:private svg-raw-page-id (uuid/custom 1 1))
(def ^:private svg-raw-root-id (uuid/custom 3 1))
(def ^:private svg-raw-child-id (uuid/custom 3 2))

(defn- prepare-svg-raw-file
  "A file containing an svg-raw subtree (an svg-raw parent with an
  svg-raw child), which is what importing an SVG produces."
  [profile]
  (let [page-id  svg-raw-page-id
        root-id  svg-raw-root-id
        child-id svg-raw-child-id

        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)
                                     :is-shared false})]
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-page
       :name "page 1"
       :id page-id}])

    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-obj
       :page-id page-id
       :id root-id
       :parent-id uuid/zero
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id root-id
              :name "svg-root"
              :frame-id uuid/zero
              :parent-id uuid/zero
              :type :svg-raw
              :content {:tag :svg :attrs {} :content []}})}
      {:type :add-obj
       :page-id page-id
       :id child-id
       :parent-id root-id
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id child-id
              :name "svg-text"
              :frame-id uuid/zero
              :parent-id root-id
              :type :svg-raw
              :content {:tag :text :attrs {} :content []}})}])

    (dissoc file :data)))

(t/deftest import-binfile-v3-preserves-svg-raw-children
  (let [profile (th/create-profile* 1)
        file    (prepare-svg-raw-file profile)
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
                     (v3/import-files!))
          imported (:result (th/command! {::th/type :get-file
                                          ::rpc/profile-id (:id profile)
                                          :id (first result)
                                          :components-v2 true}))
          root     (get-in imported [:data :pages-index svg-raw-page-id
                                     :objects svg-raw-root-id])]

      (t/is (= (count result) 1))

      ;; The child ids of an svg-raw shape must survive the JSON round
      ;; trip as uuids; when they came back as plain strings they no
      ;; longer resolved against the objects map.
      (t/is (every? uuid? (:shapes root)))
      (t/is (= [svg-raw-child-id] (vec (:shapes root))))

      ;; ...so the imported file passes referential integrity instead
      ;; of failing with :child-not-found on the next update-file.
      (t/is (nil? (cfv/validate-file imported []))))))

(defn- import-svg-attrs-asset
  "Imports the `svg-attrs-camel-case.penpot` asset, a real penpot export
  whose shapes carry `:svg-attrs` keys in camelCase (the format the
  binary export writes), and returns the imported file."
  [profile]
  (let [input  (-> "backend_tests/test_files/svg-attrs-camel-case.penpot"
                   io/resource
                   jio/file)
        result (-> th/*system*
                   (assoc ::bfc/project-id (:default-project-id profile))
                   (assoc ::bfc/profile-id (:id profile))
                   (assoc ::bfc/input input)
                   (v3/import-files!))]
    (bfc/get-file th/*system* (first result))))

(t/deftest import-binfile-v3-preserves-camel-case-svg-attrs
  ;; The json reader used by the v3 import rewrites every key of every
  ;; zip entry to kebab-case, and `:svg-attrs` is the one shape map
  ;; whose keys are camelCase react prop names. A shape exported with
  ;; `fillRule: "evenodd"` must not come back as `:fill-rule`, or the
  ;; renderer falls back to the default fill rule and the shape is
  ;; painted without its hole.
  (let [profile (th/create-profile* 1)
        file    (import-svg-attrs-asset profile)
        shape   (get-in file [:data :pages-index
                              (uuid/uuid "fc80ab5f-1bf2-817c-8008-b5408f039100")
                              :objects
                              (uuid/uuid "ce3641bd-48c8-804c-8008-b54d35cc6f80")])]

    (t/is (= {:fillRule "evenodd"}
             (:svg-attrs shape)))))

(t/deftest import-binfile-v3-preserves-camel-case-svg-attrs-on-components
  ;; Same guarantee for shapes stored inside a component: the v3 import
  ;; cleans those in a different code path than page shapes.
  (let [profile (th/create-profile* 1)
        file    (import-svg-attrs-asset profile)
        shape   (fn [component-id shape-id]
                  (-> file
                      (get-in [:data :components (uuid/uuid component-id) :objects])
                      (get (uuid/uuid shape-id))
                      :svg-attrs))]

    (t/is (= {:fillRule "evenodd"}
             (shape "fae4bc76-0cc2-8057-8008-b540912cdf78"
                    "fae4bc76-0cc2-8057-8008-b540912774cb")))

    (t/is (= {:fillRule "nonzero"}
             (shape "fae4bc76-0cc2-8057-8008-b540912c917b"
                    "fae4bc76-0cc2-8057-8008-b540912774c8")))))

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

(t/deftest import-binfile-v3-persists-manifest-metadata
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        output  (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream output))

    (let [result   (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/input output)
                       (v3/import-files!))
          imported (bfc/get-file th/*system* (first result))]

      (t/is (= (count result) 1))
      (t/is (some? (get-in imported [:metadata :generated-by])))
      (t/is (= "penpot" (get-in imported [:metadata :referer]))))))

(t/deftest read-obj-rejects-oversized-buffer
  ;; N1-07: read-obj! must reject objects exceeding max-object-size
  ;; before attempting to allocate the buffer
  (let [size (+ bfc/max-object-size 1)
        baos (java.io.ByteArrayOutputStream. 17)
        dos  (java.io.DataOutputStream. baos)]
    (.writeByte dos 5)
    (.writeLong dos (long size))
    (.flush dos)
    (let [input (java.io.DataInputStream.
                 (ByteArrayInputStream. (.toByteArray baos)))]
      (binding [v1/*position* (atom 0)]
        (let [out (try
                    (v1/read-obj! input)
                    nil
                    (catch clojure.lang.ExceptionInfo e
                      (ex-data e)))]
          ;; Without the guard, read-obj! will either OOM or proceed
          ;; to read-bytes! on a truncated stream (no :max-file-size-reached).
          ;; With the guard, it raises :validation :max-file-size-reached.
          (t/is (= :validation (:type out)))
          (t/is (= :max-file-size-reached (:code out))))))))

(t/deftest import-rejects-too-many-zip-entries
  ;; import must reject ZIP files exceeding max-zip-entries
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        output  (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream output))

    ;; Import with max-zip-entries=1 — the exported ZIP has more entries
    (let [cfg (-> th/*system*
                  (assoc ::bfc/project-id (:default-project-id profile))
                  (assoc ::bfc/profile-id (:id profile))
                  (assoc ::bfc/input output)
                  (assoc ::bfc/import-max-zip-entries 1))
          out (try
                (v3/import-files! cfg)
                :no-error
                (catch Throwable e
                  (let [d (or (ex-data e) (some-> (ex-cause e) ex-data))]
                    d)))]
      (t/is (= :validation (:type out)))
      (t/is (= :too-many-zip-entries (:code out))))))

(defn- prepare-file-with-media
  "Creates a file with a media object backed by a real storage object,
  so that v3 export produces objects/ entries."
  [profile]
  (let [storage (-> (:app.storage/storage th/*system*)
                    (stt/configure-storage-backend))

        sobject (sto/put-object! storage {::sto/content (sto/content "media-bytes")
                                          :content-type "image/svg+xml"
                                          :bucket "file-media-object"})

        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})

        mobj    (th/create-file-media-object* {:file-id (:id file)
                                               :is-local true
                                               :media-id (:id sobject)})]
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-media
       :object mobj}])

    (dissoc file :data)))

(t/deftest import-rejects-oversized-object
  ;; import must reject storage objects exceeding max-object-size
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-media profile)
        output  (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream output))

    ;; Import with max-object-size=1 — the media object will exceed this
    (let [cfg (-> th/*system*
                  (assoc ::bfc/project-id (:default-project-id profile))
                  (assoc ::bfc/profile-id (:id profile))
                  (assoc ::bfc/input output)
                  (assoc ::bfc/import-max-object-size 1))
          out (try
                (v3/import-files! cfg)
                :no-error
                (catch Throwable e
                  (let [d (or (ex-data e) (some-> (ex-cause e) ex-data))]
                    d)))]
      (t/is (= :validation (:type out)))
      (t/is (= :max-file-size-reached (:code out))))))

(def ^:private sql:current-idle-timeout
  "SELECT current_setting('idle_in_transaction_session_timeout')::interval
          = interval '30 minutes' AS ok")

(t/deftest binfile-transactions-use-a-finite-idle-timeout
  (db/tx-run! th/*system*
              (fn [{::db/keys [conn] :as cfg}]
                (t/testing "export transaction"
                  (bfc/set-transaction-idle-timeout cfg)
                  (t/is (true? (-> (db/exec-one! conn [sql:current-idle-timeout])
                                   :ok))))

                (t/testing "import transaction"
                  (bfc/configure-import-transaction cfg)
                  (t/is (true? (-> (db/exec-one! conn [sql:current-idle-timeout])
                                   :ok)))))))

;; --- Library relations security tests

(t/deftest import-v3-ignores-relations-outside-package
  (let [profile     (th/create-profile* 1)
        file        (prepare-simple-file profile)
        [_ victim]  (prepare-victim-file 3)
        bundle      (export-file file)
        evil        (tmp/tempfile :suffix ".zip")
        _           (tamper-manifest-relations bundle evil
                                               [[(str (:id file)) (str (:id victim))]])
        [file-id :as result] (import-bundle profile evil)]
    (t/is (= 1 (count result)))
    (t/is (empty? (th/db-query :file-library-rel {:file-id file-id})))
    (t/is (empty? (th/db-query :file-library-sync {:file-id file-id})))))

(defn- prepare-other-team
  [n]
  (let [profile (th/create-profile* n)
        team    (th/create-team* n {:profile-id (:id profile)})
        project (th/create-project* n {:team-id (:id team)
                                       :profile-id (:id profile)})
        file    (th/create-file* n {:profile-id (:id profile)
                                    :project-id (:id project)})
        library (th/create-file* (inc n) {:profile-id (:id profile)
                                          :project-id (:id project)})]
    [profile file library]))

(t/deftest import-v3-ignores-foreign-file-id
  (let [profile     (th/create-profile* 1)
        file        (prepare-simple-file profile)
        [_ victim]  (prepare-victim-file 3)
        bundle      (export-file file)
        evil        (tmp/tempfile :suffix ".zip")
        _           (tamper-manifest-relations bundle evil
                                               [[(str (:id victim)) (str (:id file))]])
        result      (import-bundle profile evil)]
    (t/is (= 1 (count result)))
    (t/is (empty? (th/db-query :file-library-rel {:file-id (:id victim)})))
    (t/is (empty? (th/db-query :file-library-sync {:file-id (:id victim)})))))

(t/deftest import-v3-ignores-unknown-library
  (let [profile     (th/create-profile* 1)
        file        (prepare-simple-file profile)
        bundle      (export-file file)
        evil        (tmp/tempfile :suffix ".zip")
        _           (tamper-manifest-relations bundle evil
                                               [[(str (:id file)) (str (uuid/random))]])
        [file-id :as result] (import-bundle profile evil)]
    (t/is (= 1 (count result)))
    (t/is (empty? (th/db-query :file-library-rel {:file-id file-id})))))

(t/deftest import-v3-ignores-self-library-relation
  (let [profile     (th/create-profile* 1)
        file        (prepare-simple-file profile)
        bundle      (export-file file)
        evil        (tmp/tempfile :suffix ".zip")
        _           (tamper-manifest-relations bundle evil
                                               [[(str (:id file)) (str (:id file))]])
        [file-id :as result] (import-bundle profile evil)]
    (t/is (= 1 (count result)))
    (t/is (empty? (th/db-query :file-library-rel {:file-id file-id})))))

(t/deftest import-v3-keeps-valid-library-relation
  (let [profile  (th/create-profile* 1)
        file     (prepare-simple-file profile)
        library  (th/create-file* 2 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})]
    (th/link-file-to-library* {:file-id (:id file)
                               :library-id (:id library)})
    (let [bundle   (export-file file :include-libraries true)
          result   (import-bundle profile bundle)
          new-file (->> result
                        (map #(bfc/get-file th/*system* %))
                        (some #(when (= (:name %) (:name file)) %)))]
      (t/is (= 2 (count result)))
      (t/is (some? new-file))
      (t/is (= 1 (count (th/db-query :file-library-rel
                                     {:file-id (:id new-file)})))))))

(t/deftest export-multi-file-includes-package-relations
  (let [profile          (th/create-profile* 1)
        file             (prepare-simple-file profile)
        library          (th/create-file* 2 {:profile-id (:id profile)
                                             :project-id (:default-project-id profile)})
        [_ other-file other-library] (prepare-other-team 3)
        _                (th/link-file-to-library* {:file-id (:id file)
                                                    :library-id (:id library)})
        _                (th/link-file-to-library* {:file-id (:id other-file)
                                                    :library-id (:id other-library)})
        _                (th/db-insert! :file-library-rel
                                        {:file-id (:id file)
                                         :library-file-id (:id other-library)})
        output           (tmp/tempfile :suffix ".zip")]
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file) (:id other-file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries true))
     (io/output-stream output))
    (let [rels (set (map vec (get (read-manifest output) "relations")))]
      (t/is (contains? rels [(str (:id file)) (str (:id library))]))
      (t/is (contains? rels [(str (:id other-file)) (str (:id other-library))]))
      (t/is (contains? rels [(str (:id file)) (str (:id other-library))])))))

;; --- Forbidden library relations cleanup (migration candidate)
;;
;; The two statements below are the exact cleanup proposed for a future
;; migration (see tmp/CHECK-LIBRARY-RELATIONS.sql, section 6). The sync
;; rows must go first: the second statement reads file_library_rel
;; while it still contains the forbidden rows.

(def ^:private sql:delete-forbidden-library-sync
  "WITH forbidden AS (
     SELECT flr.file_id, flr.library_file_id
       FROM file_library_rel flr
       JOIN file f ON f.id = flr.file_id
       JOIN project fp ON fp.id = f.project_id
       JOIN file l ON l.id = flr.library_file_id
       JOIN project lp ON lp.id = l.project_id
      WHERE fp.team_id <> lp.team_id
         OR flr.file_id = flr.library_file_id
   )
   DELETE FROM file_library_sync fls
   USING forbidden
   WHERE fls.file_id = forbidden.file_id
     AND fls.library_file_id = forbidden.library_file_id")

(def ^:private sql:delete-forbidden-library-rel
  "WITH forbidden AS (
     SELECT flr.file_id, flr.library_file_id
       FROM file_library_rel flr
       JOIN file f ON f.id = flr.file_id
       JOIN project fp ON fp.id = f.project_id
       JOIN file l ON l.id = flr.library_file_id
       JOIN project lp ON lp.id = l.project_id
      WHERE fp.team_id <> lp.team_id
         OR flr.file_id = flr.library_file_id
   )
   DELETE FROM file_library_rel flr
   USING forbidden
   WHERE flr.file_id = forbidden.file_id
     AND flr.library_file_id = forbidden.library_file_id")

(t/deftest cleanup-deletes-forbidden-library-relations
  (let [profile    (th/create-profile* 1)
        file       (prepare-simple-file profile)
        library    (th/create-file* 2 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)})
        [_ victim] (prepare-victim-file 3)
        _          (th/link-file-to-library* {:file-id (:id file)
                                              :library-id (:id library)})
        _          (th/db-insert! :file-library-rel
                                  {:file-id (:id file)
                                   :library-file-id (:id victim)})
        _          (th/db-insert! :file-library-sync
                                  {:file-id (:id file)
                                   :library-file-id (:id victim)})
        _          (th/db-insert! :file-library-rel
                                  {:file-id (:id file)
                                   :library-file-id (:id file)})
        _          (th/db-insert! :file-library-sync
                                  {:file-id (:id file)
                                   :library-file-id (:id file)})]
    (t/is (= 3 (count (th/db-query :file-library-rel {:file-id (:id file)}))))
    (th/db-exec! [sql:delete-forbidden-library-sync])
    (th/db-exec! [sql:delete-forbidden-library-rel])
    (t/testing "same-team relation and its sync row survive"
      (t/is (= [{:file-id (:id file) :library-file-id (:id library)}]
               (map #(select-keys % [:file-id :library-file-id])
                    (th/db-query :file-library-rel {:file-id (:id file)}))))
      (t/is (= [{:file-id (:id file) :library-file-id (:id library)}]
               (map #(select-keys % [:file-id :library-file-id])
                    (th/db-query :file-library-sync {:file-id (:id file)})))))
    (t/testing "cross-team and self relations and their sync rows are gone"
      (t/is (empty? (th/db-query :file-library-rel {:library-file-id (:id victim)})))
      (t/is (empty? (th/db-query :file-library-sync {:library-file-id (:id victim)})))
      (t/is (empty? (th/db-query :file-library-rel {:library-file-id (:id file)})))
      (t/is (empty? (th/db-query :file-library-sync {:library-file-id (:id file)}))))))
