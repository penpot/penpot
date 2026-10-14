;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.binfile-svg-test
  "SVG sanitization on the binfile import path.

   Second case of GHSA-ffhp-m958-qxvr: a crafted `.penpot` bundle can
   smuggle an unsanitized SVG storage object past the upload-path
   sanitizer, and the raw bytes are served same-origin as
   `image/svg+xml`."
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v1 :as v1]
   [app.binfile.v3 :as v3]
   [app.common.features :as cfeat]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [backend-tests.helpers :as th]
   [clojure.data.json :as json]
   [clojure.java.io :as jio]
   [clojure.string :as str]
   [clojure.test :as t]
   [datoteka.fs :as fs]
   [datoteka.io :as io])
  (:import
   java.io.ByteArrayInputStream
   java.util.zip.ZipEntry
   java.util.zip.ZipFile
   java.util.zip.ZipOutputStream))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private evil-svg
  "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"64\"><script>PWNMARK_SCRIPT</script><circle r=\"20\"/></svg>")

(def ^:private evil-xhref-svg
  "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:x=\"http://www.w3.org/1999/xlink\" width=\"64\" height=\"64\"><a x:href=\"javascript:PWNMARK_XHREF\"><text x=\"10\" y=\"30\">click</text></a></svg>")

(def ^:private clean-svg
  "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><circle r=\"4\"/></svg>")

(defn- utf8bytes
  [^String s]
  (.getBytes s "UTF-8"))

(defn- bytes-str
  [^bytes data]
  (String. data "UTF-8"))

(t/deftest sanitize-imported-svg-removes-script
  (let [result (bfc/sanitize-imported-svg {:content-type "image/svg+xml" :bucket "file-media-object"}
                                          (utf8bytes evil-svg))]
    (t/is (some? result))
    (t/is (not (str/includes? (bytes-str (:bytes result)) "<script")))
    (t/is (not (str/includes? (bytes-str (:bytes result)) "PWNMARK_SCRIPT")))))

(t/deftest sanitize-imported-svg-removes-aliased-xhref
  (let [object {:content-type "image/svg+xml; charset=utf-8" :bucket "file-media-object"}
        object (update object :content-type bfc/normalize-content-type)
        result (bfc/sanitize-imported-svg object (utf8bytes evil-xhref-svg))]
    (t/is (some? result))
    (t/is (not (str/includes? (bytes-str (:bytes result)) "javascript:")))
    (t/is (not (str/includes? (bytes-str (:bytes result)) "PWNMARK_XHREF")))))

(t/deftest svg-object-predicate-expects-canonical
  (t/is (true? (bfc/svg-object? {:content-type "image/svg+xml"})))
  (t/is (false? (bfc/svg-object? {:content-type "IMAGE/SVG+XML"})))
  (t/is (false? (bfc/svg-object? {:content-type nil})))
  (t/is (false? (bfc/svg-object? {}))))

(t/deftest sanitize-imported-svg-matches-obfuscated-spelling
  (t/testing "spellings normalized at the import boundary still sanitize"
    (doseq [ctype ["IMAGE/SVG+XML" "Image/Svg+Xml" "  image/svg+xml  " "image/svg+xml; charset=utf-8"]]
      (let [object {:content-type ctype :bucket "file-media-object"}
            object (update object :content-type bfc/normalize-content-type)
            result (bfc/sanitize-imported-svg object (utf8bytes evil-svg))]
        (t/is (some? result) (str "expected sanitize for " (pr-str ctype)))
        (when (some? result)
          (t/is (not (str/includes? (bytes-str (:bytes result)) "<script"))
                (str "script survived for " (pr-str ctype))))))))

(t/deftest sanitize-imported-svg-ignores-missing-or-blank-type
  (t/testing "ancient bundle entries without content-type pass through untouched"
    (let [raw (utf8bytes "not-an-svg")]
      (t/is (nil? (bfc/sanitize-imported-svg {} raw)))
      (t/is (nil? (bfc/sanitize-imported-svg {:content-type nil} raw)))
      (t/is (nil? (bfc/sanitize-imported-svg {:content-type ""} raw)))
      (t/is (nil? (bfc/sanitize-imported-svg {:content-type "   "} raw))))))

(t/deftest sanitize-imported-svg-ignores-non-svg
  (t/testing "objects that are not SVG pass through untouched"
    (let [raw (utf8bytes "not-an-svg")]
      (t/is (nil? (bfc/sanitize-imported-svg {:content-type "image/jpeg" :bucket "file-media-object"} raw)))
      (t/is (nil? (bfc/sanitize-imported-svg {:content-type "image/png" :bucket "file-media-object"} raw))))))

(t/deftest sanitize-imported-svg-reports-size-and-hash
  (let [result (bfc/sanitize-imported-svg {:content-type "image/svg+xml" :bucket "file-media-object"}
                                          (utf8bytes clean-svg))]
    (t/is (some? result))
    (t/is (= (alength ^bytes (:bytes result)) (:size result)))
    (t/is (str/starts-with? (:hash result) "blake2b:"))
    (t/is (str/includes? (bytes-str (:bytes result)) "<circle"))))

(t/deftest sanitize-imported-svg-rejects-broken-svg
  (t/testing "unparseable SVG raises the same validation error as the upload path"
    (t/is (thrown-with-msg? Exception #"SVG parsing failed during sanitization"
                            (bfc/sanitize-imported-svg {:content-type "image/svg+xml" :bucket "file-media-object"}
                                                       (utf8bytes "<svg><not-closed>"))))))

(t/deftest check-storage-content-type-allows-known-types
  (doseq [ctype ["image/svg+xml" "image/jpeg" "font/woff2" "application/octet-stream"]]
    (t/is (nil? (bfc/check-storage-content-type {:content-type ctype}))
          (str "expected pass for " (pr-str ctype)))))

(t/deftest check-storage-content-type-rejects-unknown-types
  (doseq [ctype ["text/html" "application/x-font-woff" nil "" "   "]]
    (let [object (if (nil? ctype) {} {:content-type ctype})
          out    (try (bfc/check-storage-content-type object)
                      nil
                      (catch clojure.lang.ExceptionInfo e
                        (ex-data e)))]
      (t/is (= :validation (:type out)) (str "expected validation for " (pr-str ctype)))
      (t/is (= :media-type-not-allowed (:code out)) (str "expected media-type-not-allowed for " (pr-str ctype))))))

(t/deftest storage-object-schema-constrains-content-type
  (t/testing "known stored types validate, unknown types do not"
    (let [base {:id (uuid/random) :size 10 :bucket "file-media-object"}]
      (doseq [ctype ["image/svg+xml" "image/jpeg" "image/png"
                     "font/woff2" "font/ttf" "application/octet-stream" "application/pdf"]]
        (t/is (some? (v3/validate-storage-object (assoc base :content-type ctype)))
              (str "expected valid for " (pr-str ctype))))
      (doseq [ctype ["" "   " "image /svg" "image/apng" "image/avif"
                     "application/x-font-woff" (apply str (repeat 200 "x"))]]
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (v3/validate-storage-object (assoc base :content-type ctype)))
              (str "expected rejection for " (pr-str ctype)))))))

(defn- write-temp-svg
  "Write `text` to a tempfile and return `[path size]`."
  [text]
  (let [path (fs/create-tempfile :prefix "penpot-svg-import-test-" :suffix ".svg")
        data (utf8bytes text)]
    (spit (str path) text :encoding "UTF-8")
    [path (alength data)]))

(defn- tamper-svg-entry!
  "Copy `src` zip to `dst` zip replacing the `objects/<storage-id>.svg`
   payload with `evil-bytes` and fixing its `.json` sidecar (size and
   blake2b hash) so the bundle passes the integrity checks. When
   `content-type` is given, the sidecar declares it instead of the
   original spelling."
  [src dst storage-id ^bytes evil-bytes & {:keys [content-type]}]
  (let [svg-name  (str "objects/" storage-id ".svg")
        json-name (str "objects/" storage-id ".json")]
    (with-open [zin  (ZipFile. (.toFile ^java.nio.file.Path src))
                zout (ZipOutputStream. (io/output-stream dst))]
      (doseq [entry (enumeration-seq (.entries zin))]
        (let [name (.getName ^ZipEntry entry)]
          (cond
            (= name svg-name)
            (do (.putNextEntry zout (ZipEntry. ^String name))
                (.write zout evil-bytes 0 (alength evil-bytes))
                (.closeEntry zout))

            (= name json-name)
            (let [raw     (slurp (.getInputStream zin entry) :encoding "UTF-8")
                  parsed  (json/read-str raw)
                  updated (cond-> (assoc parsed
                                         "size" (alength evil-bytes)
                                         "hash" (sto/calculate-hash (ByteArrayInputStream. evil-bytes)))
                            content-type (assoc "contentType" content-type))]
              (.putNextEntry zout (ZipEntry. ^String name))
              (.write zout (utf8bytes (json/write-str updated)))
              (.closeEntry zout))

            :else
            (do (.putNextEntry zout (ZipEntry. ^String name))
                (with-open [in (.getInputStream zin entry)]
                  (io/copy in zout))
                (.closeEntry zout))))))))

(defn- setup-file-with-svg-media
  "Upload `clean-svg`, attach it to a shape fill and return
   `[file uploaded]` ready to export."
  [profile n]
  (let [file    (th/create-file* n {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        [svg-path svg-size] (write-temp-svg clean-svg)
        out     (th/command! {::th/type :upload-file-media-object
                              ::rpc/profile-id (:id profile)
                              :file-id (:id file)
                              :is-local true
                              :name "benign.svg"
                              :content {:filename "benign.svg"
                                        :path svg-path
                                        :mtype "image/svg+xml"
                                        :size svg-size}})
        _       (t/is (nil? (:error out)) (str "upload failed: " (pr-str (:error out))))
        uploaded (:result out)
        page-id (first (get-in file [:data :pages]))
        shape-id (uuid/random)
        upd     (th/command! {::th/type :update-file
                              ::rpc/profile-id (:id profile)
                              :id (:id file)
                              :session-id (uuid/random)
                              :revn 0
                              :vern 0
                              :features cfeat/supported-features
                              :changes
                              [{:type :add-obj
                                :page-id page-id
                                :id shape-id
                                :parent-id uuid/zero
                                :frame-id uuid/zero
                                :components-v2 true
                                :obj (cts/setup-shape
                                      {:id shape-id
                                       :name "image"
                                       :frame-id uuid/zero
                                       :parent-id uuid/zero
                                       :type :rect
                                       :fills [{:fill-opacity 1
                                                :fill-image {:id (:id uploaded)
                                                             :width (:width uploaded)
                                                             :height (:height uploaded)
                                                             :mtype "image/svg+xml"}}]})}]})]
    (t/is (nil? (:error upd)) (str "update-file failed: " (pr-str (:error upd))))
    (t/is (uuid? (:media-id uploaded)))
    [file uploaded]))

(defn- export-tamper-import-v3
  "Export `file` to v3, tamper its SVG payload with `evil-text`
   (declaring `content-type` when given) and import the bundle.
   Returns `[served-bytes stored-content-type]` of the imported
   media."
  [profile file media-id evil-text content-type]
  (let [bundle (tmp/tempfile :prefix "penpot-export-" :suffix ".zip")]
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream bundle))

    (let [evil   (tmp/tempfile :prefix "penpot-evil-" :suffix ".zip")
          _      (tamper-svg-entry! bundle evil media-id (utf8bytes evil-text) :content-type content-type)
          result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/team-id (:default-team-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id (:default-team-id profile))
                     (assoc ::bfc/input evil)
                     (v3/import-files!))]
      (t/is (= 1 (count (:file-ids result))))
      (let [rows    (th/db-query :file-media-object {:file-id (first (:file-ids result))})
            storage (:app.storage/storage th/*system*)]
        (t/is (= 1 (count rows)))
        (let [object (sto/get-object storage (:media-id (first rows)))]
          [(bytes-str (sto/get-object-bytes storage object))
           (:content-type (meta object))])))))

(t/deftest import-binfile-v3-sanitizes-smuggled-svg
  (let [profile          (th/create-profile* 1)
        [file uploaded]  (setup-file-with-svg-media profile 1)
        [served _stored] (export-tamper-import-v3 profile file (:media-id uploaded) evil-svg nil)]
    (t/is (not (str/includes? served "<script")))
    (t/is (not (str/includes? served "PWNMARK_SCRIPT")))))

(t/deftest import-binfile-v3-sanitizes-obfuscated-content-type
  (t/testing "non-canonical content-type spellings still sanitize and store canonical"
    (doseq [[n ctype] [[2 "IMAGE/SVG+XML"]
                       [3 "image/svg+xml; charset=utf-8"]]]
      (let [profile          (th/create-profile* n)
            [file uploaded]  (setup-file-with-svg-media profile n)
            [served stored]  (export-tamper-import-v3 profile file (:media-id uploaded) evil-svg ctype)]
        (t/is (not (str/includes? served "<script")) (str "script survived for " (pr-str ctype)))
        (t/is (not (str/includes? served "PWNMARK_SCRIPT")) (str "marker survived for " (pr-str ctype)))
        (t/is (= "image/svg+xml" stored) (str "stored type not canonical for " (pr-str ctype)))))))

;; ----------------------------------------------------------------
;; v1 import path: exhaustive coverage (F2)
;; ----------------------------------------------------------------

(t/deftest sanitize-imported-svg-from-tempfile-resource
  (t/testing "bytes read back from a tempfile (v1 large-object shape) sanitize"
    (let [path (fs/create-tempfile :prefix "penpot-svg-resource-" :suffix ".svg")
          _    (spit (str path) evil-svg :encoding "UTF-8")
          raw  (with-open [istream (jio/input-stream path)]
                 (io/read istream))
          result (bfc/sanitize-imported-svg {:content-type "image/svg+xml"} raw)]
      (t/is (some? result))
      (t/is (not (str/includes? (bytes-str (:bytes result)) "<script")))
      (t/is (not (str/includes? (bytes-str (:bytes result)) "PWNMARK_SCRIPT"))))))

(defn- seed-file-with-svg-media
  "Seed storage and file_media_object rows directly (bypassing the
  upload sanitizer) so exports carry `svg-text` verbatim. Attaches
  the media to a shape fill and returns `[file media-id]`. The
  storage object declares `content-type`, which may use a
  non-canonical spelling to prove the predicate."
  [profile n svg-text content-type]
  (let [file       (th/create-file* n {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)
                                       :is-shared false})
        storage    (:app.storage/storage th/*system*)
        raw        (utf8bytes svg-text)
        content    (-> (sto/content raw)
                       (sto/wrap-with-hash (sto/calculate-hash (ByteArrayInputStream. raw))))
        sobj       (sto/put-object! storage {::sto/content content
                                             :content-type content-type
                                             :bucket "file-media-object"})
        fmo-id     (uuid/random)
        _          (th/db-insert! :file-media-object
                                  {:id fmo-id
                                   :file-id (:id file)
                                   :is-local true
                                   :name "seed.svg"
                                   :media-id (:id sobj)
                                   :width 64
                                   :height 64
                                   :mtype content-type})
        page-id    (first (get-in file [:data :pages]))
        shape-id   (uuid/random)
        upd        (th/command! {::th/type :update-file
                                 ::rpc/profile-id (:id profile)
                                 :id (:id file)
                                 :session-id (uuid/random)
                                 :revn 0
                                 :vern 0
                                 :features cfeat/supported-features
                                 :changes
                                 [{:type :add-obj
                                   :page-id page-id
                                   :id shape-id
                                   :parent-id uuid/zero
                                   :frame-id uuid/zero
                                   :components-v2 true
                                   :obj (cts/setup-shape
                                         {:id shape-id
                                          :name "image"
                                          :frame-id uuid/zero
                                          :parent-id uuid/zero
                                          :type :rect
                                          :fills [{:fill-opacity 1
                                                   :fill-image {:id fmo-id
                                                                :width 64
                                                                :height 64
                                                                :mtype "image/svg+xml"}}]})}]})]
    (t/is (nil? (:error upd)) (str "update-file failed: " (pr-str (:error upd))))
    [file (:id sobj)]))

(defn- export-v1
  "Export `file` to v1 and return the bundle path."
  [profile file]
  (let [bundle (tmp/tempfile :prefix "penpot-export-v1-" :suffix ".bin")]
    (v1/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream bundle))
    bundle))

(defn- export-import-v1
  "Export `file` to v1, import it back and return the served bytes
  of its single media object."
  [profile file]
  (let [bundle (export-v1 profile file)]
    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/input (io/input-stream bundle))
                     (v1/import-files!))]
      (t/is (= 1 (count result)))
      (let [rows    (th/db-query :file-media-object {:file-id (first result)})
            storage (:app.storage/storage th/*system*)]
        (t/is (= 1 (count rows)))
        (let [object (sto/get-object storage (:media-id (first rows)))]
          [(bytes-str (sto/get-object-bytes storage object))
           (:content-type (meta object))])))))

(t/deftest import-binfile-v1-sanitizes-seeded-svg
  (let [profile       (th/create-profile* 4)
        [file _seed]  (seed-file-with-svg-media profile 4 evil-svg "IMAGE/SVG+XML")
        [served stored] (export-import-v1 profile file)]
    (t/is (not (str/includes? served "<script")))
    (t/is (not (str/includes? served "PWNMARK_SCRIPT")))
    (t/is (= "image/svg+xml" stored))))

(t/deftest import-binfile-v1-rejects-unknown-content-type
  (let [profile      (th/create-profile* 6)
        [file _seed] (seed-file-with-svg-media profile 6 evil-svg "text/html")
        bundle       (export-v1 profile file)
        out          (try
                       (-> th/*system*
                           (assoc ::bfc/project-id (:default-project-id profile))
                           (assoc ::bfc/profile-id (:id profile))
                           (assoc ::bfc/input (io/input-stream bundle))
                           (v1/import-files!))
                       nil
                       (catch clojure.lang.ExceptionInfo e
                         (ex-data e)))]
    (t/is (= :validation (:type out)))
    (t/is (= :media-type-not-allowed (:code out)))
    (t/is (= [(:id file)]
             (mapv :id (th/db-query :file {:project-id (:default-project-id profile)}))))))

(def ^:private large-clean-svg
  (let [pad (apply str (repeat 110000 "<!--0123456789ABCDEF-->"))]
    (str "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"64\">" pad "<circle r=\"20\"/></svg>")))

(t/deftest import-binfile-v1-roundtrips-large-svg
  (t/testing "objects over the tempfile threshold (tempfile branch) import fine"
    (t/is (> (alength (utf8bytes large-clean-svg)) bfc/temp-file-threshold))
    (let [profile       (th/create-profile* 5)
          [file _seed]   (seed-file-with-svg-media profile 5 large-clean-svg "image/svg+xml")
          [served _stored] (export-import-v1 profile file)]
      (t/is (str/includes? served "circle")))))
