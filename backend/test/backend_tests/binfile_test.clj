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
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io])
  (:import
   java.io.ByteArrayInputStream
   java.io.DataInputStream
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
  ;; N1-07: read-obj! must reject objects exceeding default-max-binary-entry-size
  ;; before attempting to allocate the buffer
  (let [size (+ bfc/default-max-binary-entry-size 1)
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
  ;; import must reject ZIP files exceeding max-zip-entries (default-max-zip-entries)
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
  ;; import must reject storage objects exceeding default-max-binary-entry-size
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-media profile)
        output  (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream output))

    ;; Import with max-binary-entry-size=1 — the media object will exceed this
    (let [cfg (-> th/*system*
                  (assoc ::bfc/project-id (:default-project-id profile))
                  (assoc ::bfc/profile-id (:id profile))
                  (assoc ::bfc/input output)
                  (assoc ::bfc/import-max-binary-entry-size 1))
          out (try
                (v3/import-files! cfg)
                :no-error
                (catch Throwable e
                  (let [d (or (ex-data e) (some-> (ex-cause e) ex-data))]
                    d)))]
      (t/is (= :validation (:type out)))
      (t/is (= :max-file-size-reached (:code out))))))

;; --- GHSA-qcw7-v626-g6cf: decompression-bomb guards on JSON/text entries

(def ^:private bomb-entry-size
  "Decompressed size of the test bomb entries. Over the 20 MiB default
  per-entry limit, small enough to stay fast and lean under the guard."
  (* 1024 1024 25))

(defn- write-bomb-entry!
  "Writes a zip entry whose content is a single JSON string of `size` bytes
  made of a repeated char. Streams in chunks, so neither the writer nor the
  (guarded) reader ever needs to hold the full payload in memory. Compresses
  ~1:1000, like the reported exploit."
  [^ZipOutputStream zos ^String entry-name ^long size]
  (.putNextEntry zos (ZipEntry. entry-name))
  (let [w     (OutputStreamWriter. zos "UTF-8")
        chunk (apply str (repeat 8192 \A))]
    (.write w "\"")
    (loop [remaining size]
      (when (pos? remaining)
        (let [n (min remaining (count chunk))]
          (.write ^Writer w ^String chunk (int 0) (int n))
          (recur (- remaining n)))))
    (.write w "\"")
    (.flush w))
  (.closeEntry zos))

(defn- replace-zip-entry!
  "Copies the zip at `src-path` to `dst-path`, replacing the entry
  `entry-name` with a bomb entry of `bomb-size` decompressed bytes."
  [src-path dst-path entry-name bomb-size]
  (with-open [zin (ZipFile. (fs/file src-path))
              out (io/output-stream dst-path)
              zos (ZipOutputStream. out)]
    (.setLevel zos Deflater/BEST_COMPRESSION)
    (doseq [entry (iterator-seq (.entries zin))]
      (let [entry-name' (.getName ^ZipEntry entry)]
        (if (= entry-name' entry-name)
          (write-bomb-entry! zos entry-name bomb-size)
          (do
            (.putNextEntry zos (ZipEntry. entry-name'))
            (with-open [in (.getInputStream zin entry)]
              (io/copy in zos))
            (.closeEntry zos)))))))

(defn- try-import-files!
  "Runs v3/import-files! and returns the ex-data of the raised error,
  or :no-error when the import unexpectedly succeeds."
  [cfg]
  (try
    (v3/import-files! cfg)
    :no-error
    (catch Throwable e
      (or (ex-data e) (some-> (ex-cause e) ex-data)))))

(t/deftest import-rejects-oversized-json-entry
  ;; GHSA-qcw7-v626-g6cf: a single files/<id>.json entry expanding beyond
  ;; the per-entry text limit must be rejected with :max-file-size-reached
  ;; instead of exhausting the heap. The manifest comes from a real export
  ;; so it is valid; only the file entry is replaced by the bomb.
  (let [profile  (th/create-profile* 1)
        file     (prepare-simple-file profile)
        exported (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream exported))

    (let [bombed (tmp/tempfile :suffix ".zip")]
      (replace-zip-entry! exported bombed
                          (str "files/" (:id file) ".json")
                          bomb-entry-size)
      (let [cfg (-> th/*system*
                    (assoc ::bfc/project-id (:default-project-id profile))
                    (assoc ::bfc/profile-id (:id profile))
                    (assoc ::bfc/input bombed))
            out (try-import-files! cfg)]
        (t/is (= :validation (:type out)))
        (t/is (= :max-file-size-reached (:code out)))))))

(t/deftest get-manifest-rejects-oversized-manifest
  ;; GHSA-qcw7-v626-g6cf: the synchronous manifest read on the RPC thread
  ;; (v3/get-manifest) must reject a manifest.json bomb the same way.
  ;; Decoding/validation is never reached, so the payload needs no schema.
  (let [bombed (tmp/tempfile :suffix ".zip")]
    (with-open [out (io/output-stream bombed)
                zos (ZipOutputStream. out)]
      (.setLevel zos Deflater/BEST_COMPRESSION)
      (write-bomb-entry! zos "manifest.json" bomb-entry-size))
    (let [out (try
                (v3/get-manifest bombed)
                :no-error
                (catch Throwable e
                  (or (ex-data e) (some-> (ex-cause e) ex-data))))]
      (t/is (= :validation (:type out)))
      (t/is (= :max-file-size-reached (:code out))))))

(t/deftest import-rejects-excessive-total-text-size
  ;; The job-wide cumulative budget must reject an import whose entries,
  ;; each individually under the per-entry cap, exceed the total limit.
  ;; A tiny total budget over a legitimate small export proves the
  ;; cumulative counter fires independently of the per-entry guard.
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        output  (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream output))

    (let [cfg (-> th/*system*
                  (assoc ::bfc/project-id (:default-project-id profile))
                  (assoc ::bfc/profile-id (:id profile))
                  (assoc ::bfc/input output)
                  (assoc ::bfc/import-max-text-total-size 100))
          out (try-import-files! cfg)]
      (t/is (= :validation (:type out)))
      (t/is (= :max-file-size-reached (:code out))))))

(defn- text-entries-sizes
  "Returns the decompressed sizes of every `.json` entry in the zip at
  `zip-path`. Used to pick a cumulative budget that sits between the
  largest single entry and the summed total."
  [zip-path]
  (with-open [zin (ZipFile. (fs/file zip-path))]
    (->> (iterator-seq (.entries zin))
         (filter #(.endsWith ^String (.getName ^ZipEntry %) ".json"))
         (map #(.getSize ^ZipEntry %))
         (remove neg?)
         vec)))

(t/deftest import-rejects-accumulated-text-across-entries
  ;; The cumulative budget must account bytes across entries sharing one
  ;; counter: a budget just over the largest single entry (so no entry
  ;; alone can trip it) but under the summed total (so the running total
  ;; must trip it) is rejected. If the shared counter ever regressed to
  ;; a fresh atom per entry, every entry alone would pass and the valid
  ;; import would succeed, so this test would go red.
  (let [profile  (th/create-profile* 1)
        file     (prepare-simple-file profile)
        exported (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream exported))

    (let [sizes      (text-entries-sizes exported)
          max-single (apply max 0 sizes)
          summed     (reduce + 0 sizes)
          budget     (inc max-single)]
      ;; Preconditions that make the test meaningful: more than one
      ;; entry worth of text, so the trip can only come from
      ;; accumulation, never from a single entry.
      (t/is (> summed budget))
      (let [cfg (-> th/*system*
                    (assoc ::bfc/project-id (:default-project-id profile))
                    (assoc ::bfc/profile-id (:id profile))
                    (assoc ::bfc/input exported)
                    (assoc ::bfc/import-max-text-total-size budget))
            out (try-import-files! cfg)]
        (t/is (= :validation (:type out)))
        (t/is (= :max-file-size-reached (:code out)))
        (t/is (some? (:path out)))))))

(t/deftest size-limiting-stream-counts-skip
  ;; Skipped bytes were already decompressed, so they must count against
  ;; the budget like read bytes do.
  (let [payload   (.getBytes "abcdefghijklmnopqrstuvwxyz" "UTF-8")
        mk-stream (fn [cap]
                    (@#'v3/size-limiting-stream
                     (ByteArrayInputStream. payload) cap (atom 0) "test-entry"))]
    ;; Skipping past the cap trips the guard.
    (let [out (try
                (with-open [s (mk-stream 10)]
                  (.skip ^java.io.InputStream s 20)
                  :no-error)
                (catch clojure.lang.ExceptionInfo e
                  (ex-data e)))]
      (t/is (= :validation (:type out)))
      (t/is (= :max-file-size-reached (:code out)))
      (t/is (= "test-entry" (:path out))))
    ;; Skipping under the cap leaves the remainder accounted: 6 skipped
    ;; plus 10 read over a cap of 10 trips the guard.
    (let [out (try
                (with-open [s (mk-stream 10)]
                  (.skip ^java.io.InputStream s 6)
                  (.read ^java.io.InputStream s (byte-array 10))
                  :no-error)
                (catch clojure.lang.ExceptionInfo e
                  (ex-data e)))]
      (t/is (= :validation (:type out)))
      (t/is (= :max-file-size-reached (:code out))))))
