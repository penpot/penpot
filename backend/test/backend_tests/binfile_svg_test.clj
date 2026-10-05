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
  (let [result (bfc/sanitize-imported-svg {:content-type "image/svg+xml" :bucket "file-media-object"}
                                          (utf8bytes evil-xhref-svg))]
    (t/is (some? result))
    (t/is (not (str/includes? (bytes-str (:bytes result)) "javascript:")))
    (t/is (not (str/includes? (bytes-str (:bytes result)) "PWNMARK_XHREF")))))

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

(defn- write-temp-svg
  "Write `text` to a tempfile and return `[path size]`."
  [text]
  (let [path (fs/create-tempfile :prefix "penpot-svg-import-test-" :suffix ".svg")
        data (utf8bytes text)]
    (spit (str path) text)
    [path (alength data)]))

(defn- tamper-svg-entry!
  "Copy `src` zip to `dst` zip replacing the `objects/<storage-id>.svg`
   payload with `evil-bytes` and fixing its `.json` sidecar (size and
   blake2b hash) so the bundle passes the integrity checks."
  [src dst storage-id ^bytes evil-bytes]
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
                  updated (assoc parsed
                                 "size" (alength evil-bytes)
                                 "hash" (sto/calculate-hash (ByteArrayInputStream. evil-bytes)))]
              (.putNextEntry zout (ZipEntry. ^String name))
              (.write zout (utf8bytes (json/write-str updated)))
              (.closeEntry zout))

            :else
            (do (.putNextEntry zout (ZipEntry. ^String name))
                (with-open [in (.getInputStream zin entry)]
                  (io/copy in zout))
                (.closeEntry zout))))))))

(t/deftest import-binfile-v3-sanitizes-smuggled-svg
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
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
        media-id (:media-id uploaded)
        _       (t/is (uuid? media-id))
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
                                                             :mtype "image/svg+xml"}}]})}]})
        _       (t/is (nil? (:error upd)) (str "update-file failed: " (pr-str (:error upd))))
        bundle  (tmp/tempfile :prefix "penpot-export-" :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream bundle))

    (let [evil (tmp/tempfile :prefix "penpot-evil-" :suffix ".zip")]
      (tamper-svg-entry! bundle evil media-id (utf8bytes evil-svg))

      (let [result (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/input evil)
                       (v3/import-files!))]
        (t/is (= 1 (count result)))

        (let [rows    (th/db-query :file-media-object {:file-id (first result)})
              storage (:app.storage/storage th/*system*)]
          (t/is (= 1 (count rows)))
          (let [served (bytes-str (sto/get-object-bytes storage (sto/get-object storage (:media-id (first rows)))))]
            (t/is (not (str/includes? served "<script")))
            (t/is (not (str/includes? served "PWNMARK_SCRIPT")))))))))
