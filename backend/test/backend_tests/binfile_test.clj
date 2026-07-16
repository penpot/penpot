;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.binfile-test
  "Internal binfile test, no RPC involved"
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v1 :as v1]
   [app.binfile.v3 :as v3]
   [app.common.data :as d]
   [app.common.features :as cfeat]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.page-chunks :as pc]
   [app.common.files.shape-compact :as fsc]
   [app.common.files.tokens :as cfo]
   [app.common.files.validate :as cfv]
   [app.common.geom.matrix :as gmt]
   ;; app.common.json (not clojure.data.json below): provides `read` with
   ;; `read-kebab-key`, used by the compact page-structure test.
   [app.common.json :as cjson]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.binfile :as binfile]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.tasks.export-binfile :as export-job]
   [app.util.events :as events]
   [backend-tests.helpers :as th]
   [backend-tests.storage-test :as stt]
   [clojure.data.json :as json]
   [clojure.java.io :as jio]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io])
  (:import
   com.github.luben.zstd.ZstdInputStream
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.io.DataInputStream
   java.io.DataOutputStream
   java.io.OutputStreamWriter
   java.io.Writer
   java.util.zip.Deflater
   java.util.zip.ZipEntry
   java.util.zip.ZipFile
   java.util.zip.ZipInputStream
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
  ([profile] (prepare-simple-file profile 1))
  ([profile idx]
   (let [page-id-1 (uuid/custom 1 1)
         page-id-2 (uuid/custom 1 2)
         shape-id  (uuid/custom 2 1)
         file      (th/create-file* idx {:profile-id (:id profile)
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

     (dissoc file :data))))

(defn- prepare-file-with-tokens-lib
  "A shared library and a linked file with different active token sets.
  The file has a rect with two applied tokens. Returns the created files,
  and the ids and tokens needed to check them."
  [profile]
  (let [lib-page-id    (uuid/custom 5 1)
        file-page-id   (uuid/custom 5 2)
        shape-id       (uuid/custom 5 3)
        set-id         (uuid/custom 5 4)
        theme-id       (uuid/custom 5 5)
        other-set-id   (uuid/custom 5 8)
        other-theme-id (uuid/custom 5 9)

        token-radius  (ctob/make-token :id (uuid/custom 5 6)
                                       :name "token-radius"
                                       :type :border-radius
                                       :value 10)

        token-opacity (ctob/make-token :id (uuid/custom 5 7)
                                       :name "token-opacity"
                                       :type :opacity
                                       :value 0.7)

        tokens-lib    (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :id set-id
                                                             :name "test-token-set"))
                          (ctob/add-theme (ctob/make-token-theme :id theme-id
                                                                 :name "test-theme"
                                                                 :sets #{"test-token-set"}))
                          (ctob/add-set (ctob/make-token-set :id other-set-id
                                                             :name "other-token-set"))
                          (ctob/add-theme (ctob/make-token-theme :id other-theme-id
                                                                 :name "other-theme"
                                                                 :sets #{"other-token-set"}))
                          (ctob/add-token other-set-id (ctob/make-token :id (uuid/custom 5 10)
                                                                        :name "other-token"
                                                                        :type :opacity
                                                                        :value 0.4))
                          (ctob/add-token set-id token-radius)
                          (ctob/add-token set-id token-opacity))

        library       (th/create-file* 1 {:profile-id (:id profile)
                                          :project-id (:default-project-id profile)
                                          :is-shared true
                                          :name "Tokens Library"})

        file          (th/create-file* 2 {:profile-id (:id profile)
                                          :project-id (:default-project-id profile)
                                          :is-shared false})]

    (th/link-file-to-library* {:file-id (:id file)
                               :library-id (:id library)})

    (update-file!
     :file-id (:id library)
     :profile-id (:id profile)
     :revn 0
     :changes
     (-> (pcb/empty-changes nil)
         (pcb/with-library-data (:data library))
         (pcb/add-empty-page lib-page-id "library page")
         (pcb/set-tokens-lib tokens-lib)
         (pcb/set-tokens-status (ctos/make-tokens-status :active-theme-ids #{other-theme-id}
                                                         :active-set-ids #{other-set-id}))
         (:redo-changes)))

    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :changes
     (-> (pcb/empty-changes nil)
         (pcb/with-library-data (:data file))
         (pcb/add-empty-page file-page-id "file page")
         (pcb/set-tokens-source (:id library))
         (pcb/set-tokens-status (ctos/make-tokens-status :active-theme-ids #{theme-id}
                                                         :active-set-ids #{set-id}))
         (:redo-changes)))

    (let [page  (-> (bfc/get-file th/*system* (:id file) {:realize? true})
                    (get-in [:data :pages-index file-page-id]))
          shape (as-> (cts/setup-shape
                       {:id shape-id
                        :name "rect"
                        :frame-id uuid/zero
                        :parent-id uuid/zero
                        :type :rect}) $
                  (cto/apply-token-to-shape {:shape $
                                             :token token-radius
                                             :attributes [:r1 :r2 :r3 :r4]})
                  (cto/apply-token-to-shape {:shape $
                                             :token token-opacity
                                             :attributes [:opacity]}))]
      (update-file!
       :file-id (:id file)
       :profile-id (:id profile)
       :revn 0
       :changes
       (-> (pcb/empty-changes nil)
           (pcb/with-page page)
           (pcb/with-objects (:objects page))
           (pcb/add-object shape)
           (:redo-changes))))

    {:library library
     :file file
     :page-id file-page-id
     :shape-id shape-id
     :token-radius token-radius
     :token-opacity token-opacity}))

(defn- export-file
  [file & {:keys [include-libraries]}]
  (let [output (tmp/tempfile :suffix ".zip")]
    (v3/export-files!
     (cond-> (-> th/*system*
                 (assoc ::bfc/file-ids #{(:id file)})
                 (assoc ::bfc/output (io/output-stream output)))
       include-libraries
       (assoc ::bfc/export-type :include-libraries)))
    output))

(defn- import-bundle
  [profile path]
  (-> th/*system*
      (assoc ::bfc/project-id (:default-project-id profile))
      (assoc ::bfc/team-id (:default-team-id profile))
      (assoc ::bfc/profile-id (:id profile))
      (assoc ::bfc/input path)
      (v3/import-files!)
      (:file-ids)))

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
  ([profile] (prepare-svg-raw-file profile 1))
  ([profile idx]
   (let [page-id  svg-raw-page-id
         root-id  svg-raw-root-id
         child-id svg-raw-child-id

         file     (th/create-file* idx {:profile-id (:id profile)
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

     (dissoc file :data))))

(t/deftest import-binfile-v3-preserves-svg-raw-children
  (let [profile (th/create-profile* 1)
        file    (prepare-svg-raw-file profile)
        output  (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{(:id file)})
         (assoc ::bfc/export-type :detach-libraries)
         (assoc ::bfc/output (io/output-stream output))))

    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id (:default-team-id profile))
                     (assoc ::bfc/input output)
                     (v3/import-files!))
          file-id  (first (:file-ids result))
          imported (:result (th/command! {::th/type :get-file
                                          ::rpc/profile-id (:id profile)
                                          :id file-id
                                          :components-v2 true}))
          root     (get-in imported [:data :pages-index svg-raw-page-id
                                     :objects svg-raw-root-id])]

      (t/is (= 1 (count (:file-ids result))))

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
  whose shapes carry `:svg-attrs` keys in camelCase (the spelling the
  binary export wrote at the time), and returns the imported file. The
  v3 reader kebab-izes every key, so the shapes come back with
  kebab-case keys."
  [profile]
  (let [input  (th/tempfile "backend_tests/test_files/svg-attrs-camel-case.penpot")
        result (-> th/*system*
                   (assoc ::bfc/project-id (:default-project-id profile))
                   (assoc ::bfc/profile-id (:id profile))
                   (assoc ::bfc/team-id (:default-team-id profile))
                   (assoc ::bfc/input input)
                   (v3/import-files!))]
    (bfc/get-file th/*system* (first (:file-ids result)))))

(t/deftest import-binfile-v3-normalizes-svg-attrs-to-kebab
  ;; The json reader used by the v3 import rewrites every key of every
  ;; zip entry to kebab-case, and birth stores `:svg-attrs` in kebab-case
  ;; too, so a shape exported with `fillRule: "evenodd"` stays kebab and
  ;; the renderer finds the fill rule it expects.
  (let [profile (th/create-profile* 1)
        file    (import-svg-attrs-asset profile)
        shape   (get-in file [:data :pages-index
                              (uuid/uuid "fc80ab5f-1bf2-817c-8008-b5408f039100")
                              :objects
                              (uuid/uuid "ce3641bd-48c8-804c-8008-b54d35cc6f80")])]

    (t/is (some? shape) "the fixture shape was found")
    (t/is (= {:fill-rule "evenodd"}
             (:svg-attrs shape)))))

(t/deftest import-binfile-v3-normalizes-svg-attrs-to-kebab-on-components
  ;; Same guarantee for shapes stored inside a component: the v3 import
  ;; cleans those in a different code path than page shapes.
  (let [profile (th/create-profile* 1)
        file    (import-svg-attrs-asset profile)
        shape   (fn [component-id shape-id]
                  (-> file
                      (get-in [:data :components (uuid/uuid component-id) :objects])
                      (get (uuid/uuid shape-id))
                      :svg-attrs))]

    (t/is (= {:fill-rule "evenodd"}
             (shape "fae4bc76-0cc2-8057-8008-b540912cdf78"
                    "fae4bc76-0cc2-8057-8008-b540912774cb")))

    (t/is (= {:fill-rule "nonzero"}
             (shape "fae4bc76-0cc2-8057-8008-b540912c917b"
                    "fae4bc76-0cc2-8057-8008-b540912774c8")))))

(def ^:private svg-defs-page-id (uuid/custom 4 1))
(def ^:private svg-defs-shape-id (uuid/custom 4 2))

(defn- prepare-svg-defs-file
  "A file with an svg-raw shape carrying a filter def, the stored
  kebab form birth produces (what an SVG import leaves behind)."
  ([profile] (prepare-svg-defs-file profile 1))
  ([profile idx]
   (let [page-id  svg-defs-page-id
         shape-id svg-defs-shape-id

         file     (th/create-file* idx {:profile-id (:id profile)
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
        :id shape-id
        :parent-id uuid/zero
        :frame-id uuid/zero
        :components-v2 true
        :obj (cts/setup-shape
              {:id shape-id
               :name "svg-filter"
               :frame-id uuid/zero
               :parent-id uuid/zero
               :type :svg-raw
               :content {:tag :rect
                         :attrs {:x "10" :fill "red"}
                         :content []}
               :svg-attrs {:fill-rule "evenodd"}
               :svg-transform (gmt/matrix 1 0 0 1 10 20)
               :svg-defs {"f1" {:tag :filter
                                :attrs {:id "f1"}
                                :content [{:tag :feGaussianBlur
                                           :attrs {:std-deviation "2"}
                                           :content []}]}}})}])

     (dissoc file :data))))

(defn- export-import-file
  "Exports `file` to a zip and imports it back, returning a map with
  the export output and the imported file."
  [profile file]
  (let [output (tmp/tempfile :suffix ".zip")]
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{(:id file)})
         (assoc ::bfc/export-type :detach-libraries)
         (assoc ::bfc/output (io/output-stream output))))
    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id (:default-team-id profile))
                     (assoc ::bfc/input output)
                     (v3/import-files!))]
      {:output output
       :imported (bfc/get-file th/*system* (first (:file-ids result)))})))

(defn- svg-defs-shape
  [file]
  (get-in file [:data :pages-index svg-defs-page-id :objects svg-defs-shape-id]))

(t/deftest import-binfile-v3-normalizes-svg-defs-to-kebab
  ;; Def node attrs converge to the stored kebab convention through an
  ;; export/import cycle: tags come back as keywords (the schema decoder
  ;; restores them from the stored strings), attr keys stay kebab, outer
  ;; def keys come back as strings (the decoder prefers the string
  ;; branch), and the string ids inside survive byte-identical.
  (let [profile  (th/create-profile* 1)
        file     (prepare-svg-defs-file profile)
        {:keys [imported]} (export-import-file profile file)
        shape    (svg-defs-shape imported)
        node     (get (:svg-defs shape) "f1")]
    (t/is (some? shape) "the svg shape was found")
    (t/is (= {:fill-rule "evenodd"} (:svg-attrs shape)))
    (t/is (some? node) "the filter def was found")
    (t/is (= :filter (:tag node)) "tags decode back to keywords")
    (t/is (= "f1" (get-in node [:attrs :id])) "def ids survive byte-identical")
    (t/is (= "2" (get-in node [:content 0 :attrs :std-deviation])))
    (t/is (gmt/matrix? (:svg-transform shape))
          "svg-transform comes back as a matrix, not a plain map")
    (t/is (= (gmt/matrix 1 0 0 1 10 20) (:svg-transform shape)))))

(t/deftest export-import-svg-roundtrip-converges
  ;; The determinism proof: export, import, export, import. The first
  ;; import lands on the kebab end state and the second trip leaves it
  ;; exactly there.
  (let [profile   (th/create-profile* 1)
        file      (prepare-svg-defs-file profile)
        trip-1    (:imported (export-import-file profile file))
        shape-1   (svg-defs-shape trip-1)
        trip-2    (:imported (export-import-file profile trip-1))
        shape-2   (svg-defs-shape trip-2)]
    (t/is (some? shape-1) "the svg shape survived the first trip")
    (t/is (some? shape-2) "the svg shape survived the second trip")
    (t/is (= {:fill-rule "evenodd"} (:svg-attrs shape-1))
          "first trip lands on the kebab end state")
    (t/is (= (:svg-attrs shape-1) (:svg-attrs shape-2))
          "svg-attrs are a fixed point")
    (t/is (= (:svg-defs shape-1) (:svg-defs shape-2))
          "svg-defs are a fixed point")))

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
         (assoc ::bfc/file-ids #{(:id file)})
         (assoc ::bfc/export-type :detach-libraries)
         (assoc ::bfc/output (io/output-stream output))))

    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id (:default-team-id profile))
                     (assoc ::bfc/input output)
                     (v3/import-files!))]
      (t/is (map? result))
      (t/is (= 1 (count (:file-ids result))))
      (t/is (every? uuid? (:file-ids result)))
      ;; No external libraries in simple case - resolution should be empty
      (t/is (= {} (:resolution result))))))

(t/deftest export-binfile-preserves-public-uri-subpath
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        config  (assoc cf/config :public-uri "https://example.com/penpot")
        params  {:file-id (:id file)
                 ::bfc/export-type :detach-libraries}
        uri     (binding [cf/config config]
                  (#'binfile/export-binfile th/*system* params))]
    (t/is (str/starts-with? (str uri)
                            "https://example.com/penpot/assets/by-id/"))))

(t/deftest import-binfile-v3-persists-manifest-metadata
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        output  (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{(:id file)})
         (assoc ::bfc/export-type :detach-libraries)
         (assoc ::bfc/output (io/output-stream output))))

    (let [result   (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/team-id (:default-team-id profile))
                       (assoc ::bfc/input output)
                       (v3/import-files!))
          imported (bfc/get-file th/*system* (first (:file-ids result)))]

      (t/is (= 1 (count (:file-ids result))))
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

(t/deftest slugify-name-test
  (t/is (= "my-design-system" (bfc/slugify-name "My Design System!")))
  (t/is (= "icons" (bfc/slugify-name "Icons")))
  (t/is (= "brand-colors-2024" (bfc/slugify-name "Brand Colors 2024")))
  (t/is (= "" (bfc/slugify-name "---"))))

(t/deftest export-includes-external-libraries
  (let [profile (th/create-profile* 1)
        ;; Create a shared library file
        library (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Icons Library"})
        ;; Create a file that uses the library
        file    (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})]

    ;; Link file to library
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file)
                 :library-file-id (:id library)})

    ;; Export without including libraries
    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Read the manifest and check external-libraries
      (let [manifest (v3/get-manifest th/*system* output)]
        (t/is (some? (:external-libraries manifest)))
        (t/is (= 1 (count (:external-libraries manifest))))
        (let [ext-lib (first (:external-libraries manifest))]
          (t/is (= (:id library) (:id ext-lib)))
          (t/is (= "Icons Library" (:name ext-lib)))
          (t/is (= "icons-library" (:slug ext-lib)))
          (t/is (= [(:id file)] (:used-by ext-lib))))))))

(defn- zip-entry-names
  [path]
  (with-open [zin (ZipFile. (fs/file path))]
    (into #{} (map #(.getName ^ZipEntry %)) (iterator-seq (.entries zin)))))

(defn- read-lines
  "Reads a JSONL stream into a vector of `[key value]` pairs, with the key
  normalized the way the reader does it: a uuid becomes a keyword and a
  `tag/page-id/frame-id` stays a string."
  [^java.io.Reader in]
  (with-open [reader (java.io.PushbackReader. in 4096)]
    (loop [acc []]
      (let [value (cjson/read reader
                              :key-fn cjson/read-kebab-key
                              :eof-error? false
                              :eof-value ::eof)]
        (if (= ::eof value)
          acc
          (recur (conj acc (if (and (vector? value) (= 2 (count value)))
                             [(cjson/read-kebab-key (first value)) (second value)]
                             value))))))))

(defn- read-zip-lines
  [^ZipFile zip path]
  (read-lines (io/reader (.getInputStream zip (.getEntry zip path)))))

(defn- read-zstd-zip-lines
  [^ZipFile zip path]
  (read-lines (java.io.InputStreamReader.
               (ZstdInputStream. (.getInputStream zip (.getEntry zip path)))
               java.nio.charset.StandardCharsets/UTF_8)))


(t/deftest export-detach-libraries-removes-tokens-library-although-keeps-applied-tokens
  (let [profile (th/create-profile* 1)
        {:keys [library file page-id shape-id token-radius token-opacity]}
        (prepare-file-with-tokens-lib profile)]

    ;; Precondition: before the export, the file really depends on the
    ;; tokens of the library
    (let [file-data (:data (bfc/get-file th/*system* (:id file)))]
      (t/is (= (:id library) (cfo/get-tokens-source file-data)))
      (t/is (some? (cfo/get-tokens-status file-data)))
      (t/is (nil? (cfo/get-tokens-lib file-data))))

    ;; Do the export, with type :detach-libraries
    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :detach-libraries)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Only the file is exported, not the library
      (let [manifest (v3/get-manifest th/*system* output)
            names    (zip-entry-names output)]
        (t/is (= [(:id file)] (mapv :id (:files manifest))))
        (t/is (empty? (:relations manifest)))
        (t/is (empty? (:external-libraries manifest)))
        (t/is (contains? names (str "files/" (:id file) ".json")))
        (t/is (not-any? #(str/includes? % (str (:id library))) names)))

      ;; The exported file has neither tokens library nor tokens status
      (let [names (zip-entry-names output)]
        (t/is (not (contains? names (str "files/" (:id file) "/tokens.json"))))
        (t/is (not (contains? names (str "files/" (:id file) "/tokens-status.json")))))

      ;; Import the file again
      (let [result    (-> th/*system*
                          (assoc ::bfc/project-id (:default-project-id profile))
                          (assoc ::bfc/team-id (:default-team-id profile))
                          (assoc ::bfc/profile-id (:id profile))
                          (assoc ::bfc/input output)
                          (v3/import-files!))
            imported  (bfc/get-file th/*system* (first (:file-ids result)))
            file-data (:data imported)
            shape     (get-in file-data [:pages-index page-id :objects shape-id])]

        (t/is (= 1 (count (:file-ids result))))

        ;; ...so the imported file has no tokens source, status or library
        (t/is (nil? (cfo/get-tokens-source file-data)))
        (t/is (nil? (cfo/get-tokens-status file-data)))
        (t/is (nil? (cfo/get-tokens-lib file-data)))

        ;; The shape keeps the applied tokens
        (t/is (some? shape))
        (t/is (cfo/token-applied? token-radius shape [:r1 :r2 :r3 :r4]))
        (t/is (cfo/token-applied? token-opacity shape [:opacity]))))))

(t/deftest export-merge-libraries-embeds-tokens-library-and-keeps-applied-tokens
  (let [profile (th/create-profile* 1)
        {:keys [library file page-id shape-id token-radius token-opacity]}
        (prepare-file-with-tokens-lib profile)]

    ;; Precondition: before the export, the file depends on the tokens
    ;; of the library and has no tokens library of its own
    (let [file-data (:data (bfc/get-file th/*system* (:id file)))]
      (t/is (= (:id library) (cfo/get-tokens-source file-data)))
      (t/is (nil? (cfo/get-tokens-lib file-data))))

    (let [library-tokens-lib (-> (bfc/get-file th/*system* (:id library))
                                 (:data)
                                 (cfo/get-tokens-lib))
          output             (tmp/tempfile :suffix ".zip")]

      (t/is (some? library-tokens-lib))

      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :merge-libraries)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Only the file is exported, not the library
      (let [manifest (v3/get-manifest th/*system* output)
            names    (zip-entry-names output)]
        (t/is (= [(:id file)] (mapv :id (:files manifest))))
        (t/is (empty? (:relations manifest)))
        (t/is (empty? (:external-libraries manifest)))
        (t/is (contains? names (str "files/" (:id file) ".json")))
        (t/is (not-any? #(str/includes? % (str (:id library))) names)))

      ;; The exported file carries its own tokens library
      (t/is (contains? (zip-entry-names output)
                       (str "files/" (:id file) "/tokens.json")))

      (let [result    (-> th/*system*
                          (assoc ::bfc/project-id (:default-project-id profile))
                          (assoc ::bfc/team-id (:default-team-id profile))
                          (assoc ::bfc/profile-id (:id profile))
                          (assoc ::bfc/input output)
                          (v3/import-files!))
            imported  (bfc/get-file th/*system* (first (:file-ids result)))
            file-data (:data imported)
            shape     (get-in file-data [:pages-index page-id :objects shape-id])]

        (t/is (= 1 (count (:file-ids result))))

        ;; The tokens library of the library file is now in the file...
        ;; (the import gives new ids to the sets and tokens, so compare
        ;; their content)
        (t/is (some? (cfo/get-tokens-lib file-data)))
        (t/is (= (ctob/export-dtcg-json library-tokens-lib)
                 (ctob/export-dtcg-json (cfo/get-tokens-lib file-data))))

        ;; ...so the file is its own tokens source
        (t/is (nil? (cfo/get-tokens-source file-data)))

        ;; Activation follows the consuming file, using the imported ids.
        (t/is (= {:active-themes #{"/test-theme"}
                  :active-sets #{"test-token-set"}}
                 (cfo/ids->names (cfo/get-tokens-status file-data)
                                 (cfo/get-tokens-lib file-data))))
        (t/is (= #{"token-radius" "token-opacity"}
                 (set (keys (cfo/get-tokens-in-active-sets
                             (cfo/get-tokens-status file-data)
                             (cfo/get-tokens-lib file-data))))))

        ;; The shape keeps the applied tokens
        (t/is (some? shape))
        (t/is (cfo/token-applied? token-radius shape [:r1 :r2 :r3 :r4]))
        (t/is (cfo/token-applied? token-opacity shape [:opacity]))))))

(t/deftest embed-assets-loads-the-library-components
  ;; The library is read without `:realize?`, so its `:components` is an
  ;; unloaded pointer map: `absorb-assets` walks it with `vals`, which calls
  ;; `load!`, and that raises when no loader is bound. The absorb has to run
  ;; under the library's own loader. This is a pre-existing failure, not a
  ;; regression of the migration-on-read work: the migration path returns the
  ;; pointer already loaded by the reattach comparison.
  (let [profile      (th/create-profile* 1)
        library      (th/create-file* 1 {:profile-id (:id profile)
                                         :project-id (:default-project-id profile)
                                         :is-shared true})
        file         (th/create-file* 2 {:profile-id (:id profile)
                                         :project-id (:default-project-id profile)})
        lib-page     (first (get-in library [:data :pages]))
        file-page    (first (get-in file [:data :pages]))
        component-id (uuid/random)
        main-id      (uuid/random)
        copy-id      (uuid/random)]

    (th/link-file-to-library* {:file-id (:id file) :library-id (:id library)})

    ;; The library owns one component.
    (update-file!
     :file-id (:id library)
     :profile-id (:id profile)
     :changes
     [{:type :add-obj
       :page-id lib-page
       :id main-id
       :parent-id uuid/zero
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id main-id
              :name "Board"
              :frame-id uuid/zero
              :parent-id uuid/zero
              :type :frame
              :main-instance true
              :component-root true
              :component-file (:id library)
              :component-id component-id})}
      {:type :add-component
       :path ""
       :name "Board"
       :main-instance-id main-id
       :main-instance-page lib-page
       :id component-id
       :anotation nil}])

    ;; The file instances it, so the absorb has a usage to embed.
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :changes
     [{:type :add-obj
       :page-id file-page
       :id copy-id
       :parent-id uuid/zero
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id copy-id
              :name "Board"
              :frame-id uuid/zero
              :parent-id uuid/zero
              :type :frame
              :main-instance false
              :component-root true
              :component-file (:id library)
              :component-id component-id})}])

    (t/testing "a plain library is absorbed"
      (let [file-data (-> (bfc/get-file th/*system* (:id file) :realize? true) :data)
            result    (bfc/embed-assets th/*system* file-data (:id file))]
        (t/is (contains? (:components result) component-id))))

    (t/testing "a library that needed a migration is absorbed too"
      ;; The scenario the review reported. It works even without the loader,
      ;; because `reattach-unchanged` loads the pointers it puts back; it
      ;; stays pinned so a future reattach that stops loading does not
      ;; silently break the absorb.
      (th/db-delete! :file-migration
                     {:file-id (:id library)
                      :name "0031-migrate-stroke-width-token-attr"})
      (let [file-data (-> (bfc/get-file th/*system* (:id file) :realize? true) :data)
            result    (bfc/embed-assets th/*system* file-data (:id file))]
        (t/is (contains? (:components result) component-id))))))

(t/deftest export-detach-libraries-preserves-own-tokens-and-status
  (let [profile (th/create-profile* 1)
        {:keys [library file page-id shape-id token-radius token-opacity]}
        (prepare-file-with-tokens-lib profile)
        file-data    (:data (bfc/get-file th/*system* (:id file)))
        library-data (:data (bfc/get-file th/*system* (:id library)))
        tokens-lib   (cfo/get-tokens-lib library-data)
        output       (tmp/tempfile :suffix ".zip")]

    ;; Keep the linked library, but use the file's own tokens.
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :changes
     (-> (pcb/empty-changes nil)
         (pcb/with-library-data file-data)
         (pcb/set-tokens-source nil)
         (pcb/set-tokens-lib tokens-lib)
         (:redo-changes)))

    (v3/export-files!
     (assoc th/*system*
            ::bfc/file-ids #{(:id file)}
            ::bfc/export-type :detach-libraries
            ::bfc/output (io/output-stream output)))

    (let [names (zip-entry-names output)]
      (t/is (contains? names (str "files/" (:id file) "/tokens.json")))
      (t/is (contains? names (str "files/" (:id file) "/tokens-status.json"))))

    (let [result    (v3/import-files!
                     (assoc th/*system*
                            ::bfc/project-id (:default-project-id profile)
                            ::bfc/team-id (:default-team-id profile)
                            ::bfc/profile-id (:id profile)
                            ::bfc/input output))
          imported  (bfc/get-file th/*system* (first (:file-ids result)))
          file-data (:data imported)
          shape     (get-in file-data [:pages-index page-id :objects shape-id])]
      (t/is (nil? (cfo/get-tokens-source file-data)))
      (t/is (= (ctob/export-dtcg-json tokens-lib)
               (ctob/export-dtcg-json (cfo/get-tokens-lib file-data))))
      (t/is (some? (cfo/get-tokens-status file-data)))
      (when-let [status (cfo/get-tokens-status file-data)]
        (t/is (= {:active-themes #{"/test-theme"}
                  :active-sets #{"test-token-set"}}
                 (cfo/ids->names status (cfo/get-tokens-lib file-data)))))
      (t/is (cfo/token-applied? token-radius shape [:r1 :r2 :r3 :r4]))
      (t/is (cfo/token-applied? token-opacity shape [:opacity])))))

(defn- import-output
  "Import the exported output in the default project of the profile and
  return the imported files, indexed by name."
  [profile output]
  (let [result (v3/import-files!
                (assoc th/*system*
                       ::bfc/project-id (:default-project-id profile)
                       ::bfc/team-id (:default-team-id profile)
                       ::bfc/profile-id (:id profile)
                       ::bfc/input output))]
    (into {}
          (map (fn [id]
                 (let [file (bfc/get-file th/*system* id)]
                   [(:name file) file])))
          (:file-ids result))))

(t/deftest export-include-libraries-keeps-tokens-status-of-the-file
  (let [profile (th/create-profile* 1)
        {:keys [library file]} (prepare-file-with-tokens-lib profile)
        output (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (assoc th/*system*
            ::bfc/file-ids #{(:id file)}
            ::bfc/export-type :include-libraries
            ::bfc/output (io/output-stream output)))

    (let [files        (import-output profile output)
          library'     (get files (:name library))
          file'        (get files (:name file))
          file-data    (:data file')
          library-lib  (cfo/get-tokens-lib (:data library'))
          file-status  (cfo/get-tokens-status file-data)]

      (t/is (= 2 (count files)))
      (t/is (= (:id library') (cfo/get-tokens-source file-data)))

      ;; The active sets of the file are resolved against the sets of the
      ;; imported library, so they are not lost.
      (t/is (= {:active-themes #{"/test-theme"}
                :active-sets #{"test-token-set"}}
               (cfo/ids->names file-status library-lib)))
      (t/is (= #{"token-radius" "token-opacity"}
               (set (keys (cfo/get-tokens-in-active-sets file-status library-lib))))))))

(t/deftest export-file-with-deleted-tokens-library-embeds-its-tokens
  (let [profile    (th/create-profile* 1)
        {:keys [library file]} (prepare-file-with-tokens-lib profile)
        tokens-lib (-> (bfc/get-file th/*system* (:id library))
                       (:data)
                       (cfo/get-tokens-lib))
        output     (tmp/tempfile :suffix ".zip")]

    ;; The state left by deleting the library: in the trash and unlinked,
    ;; but still the tokens source of the file.
    (th/db-update! :file {:deleted-at (ct/in-future {:days 7})} {:id (:id library)})
    (th/db-delete! :file-library-rel {:library-file-id (:id library)})

    (v3/export-files!
     (assoc th/*system*
            ::bfc/file-ids #{(:id file)}
            ::bfc/export-type :include-libraries
            ::bfc/output (io/output-stream output)))

    (t/is (contains? (zip-entry-names output)
                     (str "files/" (:id file) "/tokens.json")))

    (let [files     (import-output profile output)
          file-data (:data (get files (:name file)))]
      (t/is (= 1 (count files)))
      (t/is (nil? (cfo/get-tokens-source file-data)))
      (t/is (= (ctob/export-dtcg-json tokens-lib)
               (ctob/export-dtcg-json (cfo/get-tokens-lib file-data))))
      (t/is (= {:active-themes #{"/test-theme"}
                :active-sets #{"test-token-set"}}
               (cfo/ids->names (cfo/get-tokens-status file-data)
                               (cfo/get-tokens-lib file-data)))))))

(t/deftest export-file-with-missing-tokens-library-removes-tokens-source
  (let [profile (th/create-profile* 1)
        {:keys [library file]} (prepare-file-with-tokens-lib profile)
        output  (tmp/tempfile :suffix ".zip")]

    ;; The tokens source points to a library that does not exist anymore
    (th/db-delete! :file-library-rel {:library-file-id (:id library)})
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :changes
     (-> (pcb/empty-changes nil)
         (pcb/with-library-data (:data (bfc/get-file th/*system* (:id file))))
         (pcb/set-tokens-source (uuid/next))
         (:redo-changes)))

    (v3/export-files!
     (assoc th/*system*
            ::bfc/file-ids #{(:id file)}
            ::bfc/export-type :include-libraries
            ::bfc/output (io/output-stream output)))

    (let [files     (import-output profile output)
          file-data (:data (get files (:name file)))]
      (t/is (= 1 (count files)))
      (t/is (nil? (cfo/get-tokens-source file-data)))
      (t/is (nil? (cfo/get-tokens-status file-data)))
      (t/is (nil? (cfo/get-tokens-lib file-data))))))

(t/deftest import-auto-links-single-candidate
  (let [profile (th/create-profile* 1)
        ;; Create a shared library file
        library (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Icons Library"})
        ;; Create a file that uses the library
        file    (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})]

    ;; Link file to library
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file)
                 :library-file-id (:id library)})

    ;; Export without including libraries
    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Remove the source library to simulate a cross-environment import
      ;; where the original library does not exist in the target team.
      (db/update! th/*system* :file
                  {:deleted-at (ct/now)}
                  {:id (:id library)})

      ;; Now create a new shared library with the same name in the same team
      ;; (simulating the library existing in the target environment)
      (let [library2 (th/create-file* 3 {:profile-id (:id profile)
                                         :project-id (:default-project-id profile)
                                         :is-shared true
                                         :name "Icons Library"})

            result   (-> th/*system*
                         (assoc ::bfc/project-id (:default-project-id profile))
                         (assoc ::bfc/profile-id (:id profile))
                         (assoc ::bfc/team-id (:default-team-id profile))
                         (assoc ::bfc/input output)
                         (v3/import-files!))]

        ;; Check that the library was auto-linked in the resolution
        (let [resolution (:resolution result)
              file-id    (first (:file-ids result))
              file-res   (get resolution file-id)]
          ;; File should have name
          (t/is (some? (:name file-res)))
          ;; File should have one auto-linked library in :done
          (t/is (= 1 (count (:done file-res))))
          (let [done-entry (first (:done file-res))]
            (t/is (= (:id library) (:id done-entry)))
            (t/is (= (:id library2) (:linked-to done-entry))))
          ;; No pending candidates
          (t/is (= [] (:pending file-res))))

        ;; Verify the file-library-rel was created
        (let [rels (db/query th/*system* :file-library-rel
                             {:library-file-id (:id library2)})]
          (t/is (= 1 (count rels))))))))

(t/deftest import-no-auto-link-no-match
  (let [profile (th/create-profile* 1)
        ;; Create a shared library file
        library (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Icons Library"})
        ;; Create a file that uses the library
        file    (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})]

    ;; Link file to library
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file)
                 :library-file-id (:id library)})

    ;; Export without including libraries
    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :detach-libraries)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Remove the source library to simulate a cross-environment import
      ;; where no matching library exists in the target team.
      (db/update! th/*system* :file
                  {:deleted-at (ct/now)}
                  {:id (:id library)})

      ;; Import without any matching library in the team
      (let [result (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/team-id (:default-team-id profile))
                       (assoc ::bfc/input output)
                       (v3/import-files!))]

        ;; No auto-linking should happen - resolution should be empty
        (t/is (= {} (:resolution result)))))))

(t/deftest import-returns-multi-match-candidates
  (let [profile (th/create-profile* 1)
        ;; Create a shared library file
        library (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Icons Library"})
        ;; Create a file that uses the library
        file    (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})]

    ;; Link file to library
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file)
                 :library-file-id (:id library)})

    ;; Export without including libraries
    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Remove the source library to simulate a cross-environment import
      ;; where the original library does not exist in the target team.
      (db/update! th/*system* :file
                  {:deleted-at (ct/now)}
                  {:id (:id library)})

      ;; Create TWO shared libraries with the same name
      (let [library2 (th/create-file* 3 {:profile-id (:id profile)
                                         :project-id (:default-project-id profile)
                                         :is-shared true
                                         :name "Icons Library"})
            library3 (th/create-file* 4 {:profile-id (:id profile)
                                         :project-id (:default-project-id profile)
                                         :is-shared true
                                         :name "Icons Library"})

            result   (-> th/*system*
                         (assoc ::bfc/project-id (:default-project-id profile))
                         (assoc ::bfc/profile-id (:id profile))
                         (assoc ::bfc/team-id (:default-team-id profile))
                         (assoc ::bfc/input output)
                         (v3/import-files!))]

        ;; No auto-linking (multi-match) - check resolution structure
        (let [resolution (:resolution result)
              file-id    (first (:file-ids result))
              file-res   (get resolution file-id)]
          ;; File should have name
          (t/is (some? (:name file-res)))
          ;; No auto-linked libraries
          (t/is (= [] (:done file-res)))
          ;; Should have pending candidates
          (t/is (= 1 (count (:pending file-res))))
          (let [pending-entry (first (:pending file-res))]
            (t/is (= (:id library) (:id pending-entry)))
            (t/is (= 2 (count (:candidates pending-entry))))
            ;; Each candidate should have project info
            (doseq [candidate (:candidates pending-entry)]
              (t/is (some? (:project-id candidate)))
              (t/is (some? (:project-name candidate))))))

        ;; No file-library-rel should be created automatically
        (let [rels (db/query th/*system* :file-library-rel
                             {:library-file-id (:id library2)})]
          (t/is (= 0 (count rels))))
        (let [rels (db/query th/*system* :file-library-rel
                             {:library-file-id (:id library3)})]
          (t/is (= 0 (count rels))))))))

(t/deftest import-auto-link-respects-library-permissions
  (let [owner   (th/create-profile* 1)
        team    (th/create-team* 1 {:profile-id (:id owner)})
        viewer  (th/create-profile* 2)
        _       (th/create-team-role* {:team-id    (:id team)
                                       :profile-id (:id viewer)
                                       :role       :viewer})

        library (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id (:default-project-id owner)
                                    :is-shared true
                                    :name "Icons Library"})
        file    (th/create-file* 2 {:profile-id (:id owner)
                                    :project-id (:default-project-id owner)
                                    :is-shared false})]

    ;; Link file to library
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file)
                 :library-file-id (:id library)})

    ;; Export with link-later to compute external-libraries
    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Remove the source library and recreate a matching one owned by owner
      (db/update! th/*system* :file
                  {:deleted-at (ct/now)}
                  {:id (:id library)})

      ;; Create a project in the team for the matched library and import.
      (let [project  (th/create-project* 1 {:profile-id (:id owner)
                                            :team-id    (:id team)})

            library2 (th/create-file* 3 {:profile-id (:id owner)
                                         :project-id (:id project)
                                         :is-shared true
                                         :name "Icons Library"})

            result   (-> th/*system*
                         (assoc ::bfc/project-id (:id project))
                         (assoc ::bfc/profile-id (:id viewer))
                         (assoc ::bfc/team-id (:id team))
                         (assoc ::bfc/input output)
                         (v3/import-files!))]

        ;; Auto-link must be skipped because viewer cannot edit the library
        (let [resolution (:resolution result)
              file-id    (first (:file-ids result))
              file-res   (get resolution file-id)]
          ;; No auto-linked libraries - file may not be in resolution map at all
          (t/is (or (nil? file-res)
                    (= [] (:done file-res)))))

        ;; No file-library-rel should have been created
        (let [rels (db/query th/*system* :file-library-rel
                             {:library-file-id (:id library2)})]
          (t/is (= 0 (count rels))))

        ;; Control: the same import performed by the owner (who has edit
        ;; permission on the library) should auto-link.
        (let [result (-> th/*system*
                         (assoc ::bfc/project-id (:id project))
                         (assoc ::bfc/profile-id (:id owner))
                         (assoc ::bfc/team-id (:id team))
                         (assoc ::bfc/input output)
                         (v3/import-files!))]

          (let [resolution (:resolution result)
                file-id    (first (:file-ids result))
                file-res   (get resolution file-id)]
            ;; Should have name
            (t/is (some? (:name file-res)))
            ;; Should have one auto-linked library
            (t/is (= 1 (count (:done file-res))))
            (t/is (= (:id library2) (:linked-to (first (:done file-res))))))

          (let [rels (db/query th/*system* :file-library-rel
                               {:library-file-id (:id library2)})]
            (t/is (= 1 (count rels)))))))))

(t/deftest import-auto-link-only-files-that-used-library
  (let [profile (th/create-profile* 1)
        library (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Icons Library"})
        file1   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file2   (th/create-file* 3 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})]

    ;; Only file1 uses the library
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file1)
                 :library-file-id (:id library)})

    ;; Export both files without including libraries
    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file1) (:id file2)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Remove the source library and recreate a matching one
      (db/update! th/*system* :file
                  {:deleted-at (ct/now)}
                  {:id (:id library)})

      (let [library2 (th/create-file* 4 {:profile-id (:id profile)
                                         :project-id (:default-project-id profile)
                                         :is-shared true
                                         :name "Icons Library"})

            result   (-> th/*system*
                         (assoc ::bfc/project-id (:default-project-id profile))
                         (assoc ::bfc/profile-id (:id profile))
                         (assoc ::bfc/team-id (:default-team-id profile))
                         (assoc ::bfc/input output)
                         (v3/import-files!))]

        ;; The library should be auto-linked for the file that used it
        (let [resolution (:resolution result)
              ;; Find the file that has auto-linked libraries
              file-with-done (d/seek #(seq (:done %)) (vals resolution))]
          (t/is (some? file-with-done))
          ;; Should have name
          (t/is (some? (:name file-with-done)))
          (t/is (= 1 (count (:done file-with-done))))
          (t/is (= (:id library2) (:linked-to (first (:done file-with-done)))))

          ;; But only one file-library-rel should exist (for file1)
          (let [rels (db/query th/*system* :file-library-rel
                               {:library-file-id (:id library2)})]
            (t/is (= 1 (count rels)))))))))

;; =============================================================================
;; COMPREHENSIVE LINK-LATER TESTS
;; =============================================================================

(defn- import-sample-file
  "Import the file-with-library.penpot sample file and return
  {:profile :file :library :team}. The sample contains a library and
  a file that uses it."
  ([]
   (import-sample-file th/*system*))
  ([system]
   (let [profile (th/create-profile* system 1 {})
         input (th/tempfile "backend_tests/test_files/file-with-library.penpot")
         result (-> system
                    (assoc ::bfc/project-id (:default-project-id profile))
                    (assoc ::bfc/profile-id (:id profile))
                    (assoc ::bfc/team-id (:default-team-id profile))
                    (assoc ::bfc/input input)
                    (v3/import-files!))
         file-ids (:file-ids result)
         ;; Find the file-library-rel to identify which id is the file
         ;; and which is the library. Relation: file-id -> library-file-id.
         rels (keep #(when-let [r (db/query system :file-library-rel
                                            {:file-id %})]
                       (first r))
                    file-ids)
         rel (first rels)
         file-id (:file-id rel)
         library-id (:library-file-id rel)]
     {:profile profile
      :file-id file-id
      :library-id library-id
      :all-file-ids (set file-ids)
      :team-id (:default-team-id profile)})))

(defn- create-named-library
  "Create a shared library with the given name in the given team."
  ([team-id name]
   (create-named-library th/*system* 1 team-id name))
  ([system i team-id name]
   (let [profile (th/create-profile* system i {})
         project (th/create-project* system i {:profile-id (:id profile)
                                               :team-id team-id})]
     (th/create-file* system i {:profile-id (:id profile)
                                :project-id (:id project)
                                :is-shared true
                                :name name}))))

(defn- get-file-shapes
  "Get all shapes from a file's data."
  [file-data]
  (let [pages (vals (:pages-index file-data))]
    (mapcat vals (map :objects pages))))

;; -----------------------------------------------------------------------------
;; Category 1: Same-Team Round-Trip
;; -----------------------------------------------------------------------------

(t/deftest link-later-same-team-round-trip
  (let [{:keys [profile file-id team-id]} (import-sample-file)
        _ (t/is (some? file-id))
        output (tmp/tempfile :suffix ".zip")]
    ;; Export file with link-later
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{file-id})
         (assoc ::bfc/export-type :link-later)
         (assoc ::bfc/output (io/output-stream output))))

    ;; Verify manifest has external-libraries
    (let [manifest (v3/get-manifest th/*system* output)
          ext-libs (:external-libraries manifest)]
      (t/is (some? ext-libs))
      (t/is (pos? (count ext-libs))))

    ;; Re-import in same team
    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id team-id)
                     (assoc ::bfc/input output)
                     (v3/import-files!))
          resolution (:resolution result)
          new-file-id (first (:file-ids result))
          file-res (get resolution new-file-id)]

      ;; Should have auto-linked library
      (t/is (some? file-res))
      (t/is (some? (:name file-res)))
      (t/is (= 1 (count (:done file-res))))
      (t/is (= [] (:pending file-res)))

      ;; Verify file-library-rel was created
      (let [rels (db/query th/*system* :file-library-rel {:file-id new-file-id})]
        (t/is (pos? (count rels)))))))

(t/deftest link-later-same-team-idempotent
  (let [{:keys [profile file-id team-id]} (import-sample-file)
        output (tmp/tempfile :suffix ".zip")]
    ;; Export file with link-later
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{file-id})
         (assoc ::bfc/export-type :link-later)
         (assoc ::bfc/output (io/output-stream output))))

    ;; First import
    (let [result1 (-> th/*system*
                      (assoc ::bfc/project-id (:default-project-id profile))
                      (assoc ::bfc/profile-id (:id profile))
                      (assoc ::bfc/team-id team-id)
                      (assoc ::bfc/input output)
                      (v3/import-files!))]
      (t/is (= 1 (count (:file-ids result1)))))

    ;; Second import (should succeed)
    (let [result2 (-> th/*system*
                      (assoc ::bfc/project-id (:default-project-id profile))
                      (assoc ::bfc/profile-id (:id profile))
                      (assoc ::bfc/team-id team-id)
                      (assoc ::bfc/input output)
                      (v3/import-files!))]
      (t/is (= 1 (count (:file-ids result2))))
      (let [resolution (:resolution result2)
            new-file-id (first (:file-ids result2))
            file-res (get resolution new-file-id)]
        (t/is (some? file-res))
        (t/is (= 1 (count (:done file-res))))))))

(t/deftest import-rejects-an-in-place-import
  ;; The in-place import was removed: it never carried the previous storage
  ;; pointer, so it could leave the replaced object unreclaimed. A caller that
  ;; still sets `::bfc/file-id` fails instead of importing as new files.
  (let [{:keys [profile file-id team-id]} (import-sample-file)
        output (tmp/tempfile :suffix ".zip")
        cause  (try
                 (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id team-id)
                     (assoc ::bfc/file-id file-id)
                     (assoc ::bfc/input output)
                     (v3/import-files!))
                 nil
                 (catch clojure.lang.ExceptionInfo cause cause))]
    (t/is (= :not-supported (:code (ex-data cause))))))

;; -----------------------------------------------------------------------------
;; Category 2: Cross-Team Migration
;; -----------------------------------------------------------------------------

(t/deftest link-later-cross-team-library-pre-exists
  (let [{:keys [profile file-id team-id]} (import-sample-file)
        ;; Create a second team with a library named "LIbrary"
        team2 (th/create-team* 2 {:profile-id (:id profile)})
        library2 (create-named-library th/*system* 10 (:id team2) "LIbrary")
        output (tmp/tempfile :suffix ".zip")]

    ;; Export file with link-later from team 1
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{file-id})
         (assoc ::bfc/export-type :link-later)
         (assoc ::bfc/output (io/output-stream output))))

    ;; Import in team 2 (where library with same name exists)
    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id (:id team2))
                     (assoc ::bfc/input output)
                     (v3/import-files!))
          resolution (:resolution result)
          new-file-id (first (:file-ids result))
          file-res (get resolution new-file-id)]

      ;; Should auto-link to library2
      (t/is (some? file-res))
      (t/is (= 1 (count (:done file-res))))
      (t/is (= (:id library2) (:linked-to (first (:done file-res)))))

      ;; Verify file-library-rel was created
      (let [rels (db/query th/*system* :file-library-rel
                           {:library-file-id (:id library2)})]
        (t/is (= 1 (count rels)))))))

(t/deftest link-later-cross-team-no-library
  (let [{:keys [profile file-id]} (import-sample-file)
        team2 (th/create-team* 2 {:profile-id (:id profile)})
        output (tmp/tempfile :suffix ".zip")]

    ;; Export file with link-later
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{file-id})
         (assoc ::bfc/export-type :link-later)
         (assoc ::bfc/output (io/output-stream output))))

    ;; Import in team 2 (no library exists)
    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id (:id team2))
                     (assoc ::bfc/input output)
                     (v3/import-files!))
          resolution (:resolution result)]

      ;; No linking should happen
      (t/is (= {} resolution)))))

(t/deftest link-later-cross-team-different-library-name
  (let [{:keys [profile file-id]} (import-sample-file)
        team2 (th/create-team* 2 {:profile-id (:id profile)})
        library2 (create-named-library th/*system* 10 (:id team2) "Buttons Library")
        output (tmp/tempfile :suffix ".zip")]

    ;; Export file with link-later
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{file-id})
         (assoc ::bfc/export-type :link-later)
         (assoc ::bfc/output (io/output-stream output))))

    ;; Import in team 2 (library with different name)
    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id (:id team2))
                     (assoc ::bfc/input output)
                     (v3/import-files!))
          resolution (:resolution result)]

      ;; No linking (slug mismatch)
      (t/is (= {} resolution)))))

(t/deftest link-later-cross-team-library-not-shared
  (let [{:keys [profile file-id]} (import-sample-file)
        team2 (th/create-team* 2 {:profile-id (:id profile)})
        ;; Create a private (non-shared) library
        _ (let [priv-project (th/create-project* th/*system* 20 {:profile-id (:id profile)
                                                                 :team-id (:id team2)})
                priv-lib (th/create-file* th/*system* 21 {:profile-id (:id profile)
                                                          :project-id (:id priv-project)
                                                          :is-shared false
                                                          :name "LIbrary"})])
        output (tmp/tempfile :suffix ".zip")]

    ;; Export file with link-later
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{file-id})
         (assoc ::bfc/export-type :link-later)
         (assoc ::bfc/output (io/output-stream output))))

    ;; Import in team 2 (library exists but not shared)
    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id (:id team2))
                     (assoc ::bfc/input output)
                     (v3/import-files!))
          resolution (:resolution result)]

      ;; No linking (library not shared)
      (t/is (= {} resolution)))))

(t/deftest link-later-cross-team-library-deleted
  (let [{:keys [profile file-id]} (import-sample-file)
        team2 (th/create-team* 2 {:profile-id (:id profile)})
        library2 (create-named-library th/*system* 10 (:id team2) "LIbrary")
        output (tmp/tempfile :suffix ".zip")]

    ;; Export file with link-later
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{file-id})
         (assoc ::bfc/export-type :link-later)
         (assoc ::bfc/output (io/output-stream output))))

    ;; Delete the library in team 2
    (db/update! th/*system* :file
                {:deleted-at (ct/now)}
                {:id (:id library2)})

    ;; Import in team 2 (library deleted)
    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id (:id team2))
                     (assoc ::bfc/input output)
                     (v3/import-files!))
          resolution (:resolution result)]

      ;; No linking (library deleted)
      (t/is (= {} resolution)))))

;; -----------------------------------------------------------------------------
;; Category 3: Multiple Libraries
;; -----------------------------------------------------------------------------

(t/deftest link-later-multiple-libraries-both-match
  (let [profile (th/create-profile* 1)
        team-id (:default-team-id profile)
        ;; Create two libraries
        lib1 (th/create-file* 1 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared true
                                 :name "Icons Library"})
        lib2 (th/create-file* 2 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared true
                                 :name "Colors Library"})
        ;; Create file linked to both
        file (th/create-file* 3 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared false})]

    ;; Link file to both libraries
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib1)})
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib2)})

    ;; Export with link-later
    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Delete original libraries
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib1)})
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib2)})

      ;; Create new libraries with same names
      (let [lib1b (th/create-file* 10 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)
                                       :is-shared true
                                       :name "Icons Library"})
            lib2b (th/create-file* 11 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)
                                       :is-shared true
                                       :name "Colors Library"})

            result (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/team-id team-id)
                       (assoc ::bfc/input output)
                       (v3/import-files!))
            resolution (:resolution result)
            new-file-id (first (:file-ids result))
            file-res (get resolution new-file-id)]

        ;; Both libraries should be auto-linked
        (t/is (some? file-res))
        (t/is (= 2 (count (:done file-res))))
        (t/is (= [] (:pending file-res)))

        ;; Verify both linked-to ids
        (let [linked-ids (set (map :linked-to (:done file-res)))]
          (t/is (contains? linked-ids (:id lib1b)))
          (t/is (contains? linked-ids (:id lib2b))))))))

(t/deftest link-later-multiple-libraries-one-matches
  (let [profile (th/create-profile* 1)
        team-id (:default-team-id profile)
        lib1 (th/create-file* 1 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared true
                                 :name "Icons Library"})
        lib2 (th/create-file* 2 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared true
                                 :name "Colors Library"})
        file (th/create-file* 3 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared false})]

    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib1)})
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib2)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Delete both libraries
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib1)})
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib2)})

      ;; Only recreate Icons (not Colors)
      (let [lib1b (th/create-file* 10 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)
                                       :is-shared true
                                       :name "Icons Library"})

            result (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/team-id team-id)
                       (assoc ::bfc/input output)
                       (v3/import-files!))
            resolution (:resolution result)
            new-file-id (first (:file-ids result))
            file-res (get resolution new-file-id)]

        ;; Only Icons should be auto-linked
        (t/is (some? file-res))
        (t/is (= 1 (count (:done file-res))))
        (t/is (= (:id lib1b) (:linked-to (first (:done file-res)))))))))

(t/deftest link-later-multiple-libraries-both-multi-match
  (let [profile (th/create-profile* 1)
        team-id (:default-team-id profile)
        lib1 (th/create-file* 1 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared true
                                 :name "Icons Library"})
        lib2 (th/create-file* 2 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared true
                                 :name "Colors Library"})
        file (th/create-file* 3 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared false})]

    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib1)})
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib2)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Delete original libraries
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib1)})
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib2)})

      ;; Create TWO of each library (multi-match)
      (let [_ (th/create-file* 10 {:profile-id (:id profile)
                                   :project-id (:default-project-id profile)
                                   :is-shared true
                                   :name "Icons Library"})
            _ (th/create-file* 11 {:profile-id (:id profile)
                                   :project-id (:default-project-id profile)
                                   :is-shared true
                                   :name "Icons Library"})
            _ (th/create-file* 12 {:profile-id (:id profile)
                                   :project-id (:default-project-id profile)
                                   :is-shared true
                                   :name "Colors Library"})
            _ (th/create-file* 13 {:profile-id (:id profile)
                                   :project-id (:default-project-id profile)
                                   :is-shared true
                                   :name "Colors Library"})

            result (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/team-id team-id)
                       (assoc ::bfc/input output)
                       (v3/import-files!))
            resolution (:resolution result)
            new-file-id (first (:file-ids result))
            file-res (get resolution new-file-id)]

        ;; Both libraries should be pending (multi-match)
        (t/is (some? file-res))
        (t/is (= [] (:done file-res)))
        (t/is (= 2 (count (:pending file-res))))

        ;; Each pending should have 2 candidates
        (doseq [pending-entry (:pending file-res)]
          (t/is (= 2 (count (:candidates pending-entry)))))))))

(t/deftest link-later-multiple-libraries-mixed-single-and-multi
  (let [profile (th/create-profile* 1)
        team-id (:default-team-id profile)
        lib1 (th/create-file* 1 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared true
                                 :name "Icons Library"})
        lib2 (th/create-file* 2 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared true
                                 :name "Colors Library"})
        file (th/create-file* 3 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared false})]

    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib1)})
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib2)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Delete original libraries
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib1)})
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib2)})

      ;; Create ONE Icons (single match) and TWO Colors (multi-match)
      (let [lib1b (th/create-file* 10 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)
                                       :is-shared true
                                       :name "Icons Library"})
            _ (th/create-file* 11 {:profile-id (:id profile)
                                   :project-id (:default-project-id profile)
                                   :is-shared true
                                   :name "Colors Library"})
            _ (th/create-file* 12 {:profile-id (:id profile)
                                   :project-id (:default-project-id profile)
                                   :is-shared true
                                   :name "Colors Library"})

            result (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/team-id team-id)
                       (assoc ::bfc/input output)
                       (v3/import-files!))
            resolution (:resolution result)
            new-file-id (first (:file-ids result))
            file-res (get resolution new-file-id)]

        ;; Icons done, Colors pending
        (t/is (some? file-res))
        (t/is (= 1 (count (:done file-res))))
        (t/is (= (:id lib1b) (:linked-to (first (:done file-res)))))
        (t/is (= 1 (count (:pending file-res))))
        (t/is (= 2 (count (:candidates (first (:pending file-res))))))))))

;; -----------------------------------------------------------------------------
;; Category 4: Multiple Files
;; -----------------------------------------------------------------------------

(t/deftest link-later-multiple-files-same-library
  (let [profile (th/create-profile* 1)
        team-id (:default-team-id profile)
        lib (th/create-file* 1 {:profile-id (:id profile)
                                :project-id (:default-project-id profile)
                                :is-shared true
                                :name "Icons Library"})
        file1 (th/create-file* 2 {:profile-id (:id profile)
                                  :project-id (:default-project-id profile)
                                  :is-shared false})
        file2 (th/create-file* 3 {:profile-id (:id profile)
                                  :project-id (:default-project-id profile)
                                  :is-shared false})]

    ;; Both files use the library
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file1) :library-file-id (:id lib)})
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file2) :library-file-id (:id lib)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file1) (:id file2)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Delete original library and recreate
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib)})
      (let [lib-b (th/create-file* 10 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)
                                       :is-shared true
                                       :name "Icons Library"})

            result (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/team-id team-id)
                       (assoc ::bfc/input output)
                       (v3/import-files!))
            resolution (:resolution result)]

        ;; Both files should have auto-linked
        (t/is (= 2 (count (keys resolution))))
        (doseq [[file-id file-res] resolution]
          (t/is (= 1 (count (:done file-res))))
          (t/is (= (:id lib-b) (:linked-to (first (:done file-res))))))))))

(t/deftest link-later-multiple-files-only-one-uses-library
  (let [profile (th/create-profile* 1)
        team-id (:default-team-id profile)
        lib (th/create-file* 1 {:profile-id (:id profile)
                                :project-id (:default-project-id profile)
                                :is-shared true
                                :name "Icons Library"})
        file1 (th/create-file* 2 {:profile-id (:id profile)
                                  :project-id (:default-project-id profile)
                                  :is-shared false})
        file2 (th/create-file* 3 {:profile-id (:id profile)
                                  :project-id (:default-project-id profile)
                                  :is-shared false})]

    ;; Only file1 uses the library
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file1) :library-file-id (:id lib)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file1) (:id file2)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Delete original library and recreate
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib)})
      (let [lib-b (th/create-file* 10 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)
                                       :is-shared true
                                       :name "Icons Library"})

            result (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/team-id team-id)
                       (assoc ::bfc/input output)
                       (v3/import-files!))
            resolution (:resolution result)]

        ;; Only file1 should have auto-linked
        (let [files-with-done (filter #(seq (:done (val %))) resolution)]
          (t/is (= 1 (count files-with-done)))
          (let [[file-id file-res] (first files-with-done)]
            (t/is (= (:id lib-b) (:linked-to (first (:done file-res)))))))))))

(t/deftest link-later-multiple-files-different-libraries
  (let [profile (th/create-profile* 1)
        team-id (:default-team-id profile)
        lib-icons (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)
                                      :is-shared true
                                      :name "Icons Library"})
        lib-colors (th/create-file* 2 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)
                                       :is-shared true
                                       :name "Colors Library"})
        file1 (th/create-file* 3 {:profile-id (:id profile)
                                  :project-id (:default-project-id profile)
                                  :is-shared false})
        file2 (th/create-file* 4 {:profile-id (:id profile)
                                  :project-id (:default-project-id profile)
                                  :is-shared false})]

    ;; file1 uses Icons, file2 uses Colors
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file1) :library-file-id (:id lib-icons)})
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file2) :library-file-id (:id lib-colors)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file1) (:id file2)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Delete original libraries and recreate
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib-icons)})
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib-colors)})
      (let [lib-icons-b (th/create-file* 10 {:profile-id (:id profile)
                                             :project-id (:default-project-id profile)
                                             :is-shared true
                                             :name "Icons Library"})
            lib-colors-b (th/create-file* 11 {:profile-id (:id profile)
                                              :project-id (:default-project-id profile)
                                              :is-shared true
                                              :name "Colors Library"})

            result (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/team-id team-id)
                       (assoc ::bfc/input output)
                       (v3/import-files!))
            resolution (:resolution result)]

        ;; Each file should have its respective library
        (t/is (= 2 (count (keys resolution))))
        (doseq [[file-id file-res] resolution]
          (t/is (= 1 (count (:done file-res))))
          (let [linked-id (:linked-to (first (:done file-res)))]
            (t/is (or (= linked-id (:id lib-icons-b))
                      (= linked-id (:id lib-colors-b))))))))))

;; -----------------------------------------------------------------------------
;; Category 5: Permission Scenarios
;; -----------------------------------------------------------------------------

(t/deftest link-later-permission-viewer-cannot-link
  (let [owner (th/create-profile* 1)
        team (th/create-team* 1 {:profile-id (:id owner)})
        viewer (th/create-profile* 2)
        _ (th/create-team-role* {:team-id (:id team)
                                 :profile-id (:id viewer)
                                 :role :viewer})
        project (th/create-project* 1 {:profile-id (:id owner)
                                       :team-id (:id team)})
        lib (th/create-file* 1 {:profile-id (:id owner)
                                :project-id (:id project)
                                :is-shared true
                                :name "Icons Library"})
        file (th/create-file* 2 {:profile-id (:id owner)
                                 :project-id (:id project)
                                 :is-shared false})]

    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Import as viewer (no edit permission on library)
      (let [result (-> th/*system*
                       (assoc ::bfc/project-id (:id project))
                       (assoc ::bfc/profile-id (:id viewer))
                       (assoc ::bfc/team-id (:id team))
                       (assoc ::bfc/input output)
                       (v3/import-files!))
            resolution (:resolution result)
            new-file-id (first (:file-ids result))
            file-res (get resolution new-file-id)]

        ;; No auto-link (viewer lacks edit permission)
        (t/is (or (nil? file-res)
                  (= [] (:done file-res))))))))

(t/deftest link-later-permission-editor-can-link
  (let [owner (th/create-profile* 1)
        team (th/create-team* 1 {:profile-id (:id owner)})
        editor (th/create-profile* 2)
        _ (th/create-team-role* {:team-id (:id team)
                                 :profile-id (:id editor)
                                 :role :editor})
        project (th/create-project* 1 {:profile-id (:id owner)
                                       :team-id (:id team)})
        lib (th/create-file* 1 {:profile-id (:id owner)
                                :project-id (:id project)
                                :is-shared true
                                :name "Icons Library"})
        file (th/create-file* 2 {:profile-id (:id owner)
                                 :project-id (:id project)
                                 :is-shared false})]

    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Import as editor (has edit permission)
      (let [result (-> th/*system*
                       (assoc ::bfc/project-id (:id project))
                       (assoc ::bfc/profile-id (:id editor))
                       (assoc ::bfc/team-id (:id team))
                       (assoc ::bfc/input output)
                       (v3/import-files!))
            resolution (:resolution result)
            new-file-id (first (:file-ids result))
            file-res (get resolution new-file-id)]

        ;; Auto-link should succeed
        (t/is (some? file-res))
        (t/is (= 1 (count (:done file-res))))))))

;; -----------------------------------------------------------------------------
;; Category 6: Edge Cases
;; -----------------------------------------------------------------------------

(t/deftest link-later-edge-special-chars-in-name
  (let [profile (th/create-profile* 1)
        team-id (:default-team-id profile)
        lib (th/create-file* 1 {:profile-id (:id profile)
                                :project-id (:default-project-id profile)
                                :is-shared true
                                :name "Icons & Buttons!"})
        file (th/create-file* 2 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared false})]

    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Verify slug in manifest
      (let [manifest (v3/get-manifest th/*system* output)
            ext-lib (first (:external-libraries manifest))]
        (t/is (= "icons-buttons" (:slug ext-lib)))))))

(t/deftest link-later-edge-empty-slug-library
  (let [profile (th/create-profile* 1)
        team-id (:default-team-id profile)
        ;; Library name that slugifies to empty
        lib (th/create-file* 1 {:profile-id (:id profile)
                                :project-id (:default-project-id profile)
                                :is-shared true
                                :name "---"})
        file (th/create-file* 2 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared false})]

    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Library with empty slug should be dropped from external-libraries
      (let [manifest (v3/get-manifest th/*system* output)
            ext-libs (:external-libraries manifest)]
        (t/is (or (nil? ext-libs)
                  (empty? ext-libs)))))))

(t/deftest link-later-edge-file-without-libraries
  (let [profile (th/create-profile* 1)
        file (th/create-file* 1 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared false})
        output (tmp/tempfile :suffix ".zip")]

    ;; Export file with no libraries
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{(:id file)})
         (assoc ::bfc/export-type :link-later)
         (assoc ::bfc/output (io/output-stream output))))

    ;; Manifest should have no external-libraries
    (let [manifest (v3/get-manifest th/*system* output)]
      (t/is (nil? (:external-libraries manifest))))

    ;; Import should succeed with empty resolution
    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id (:default-team-id profile))
                     (assoc ::bfc/input output)
                     (v3/import-files!))]
      (t/is (= {} (:resolution result))))))

(t/deftest link-later-edge-case-insensitive-match
  (let [profile (th/create-profile* 1)
        team-id (:default-team-id profile)
        lib (th/create-file* 1 {:profile-id (:id profile)
                                :project-id (:default-project-id profile)
                                :is-shared true
                                :name "ICONS Library"})
        file (th/create-file* 2 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared false})]

    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Delete original and create with different case
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib)})
      (let [lib-b (th/create-file* 10 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)
                                       :is-shared true
                                       :name "icons library"})

            result (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/team-id team-id)
                       (assoc ::bfc/input output)
                       (v3/import-files!))
            resolution (:resolution result)
            new-file-id (first (:file-ids result))
            file-res (get resolution new-file-id)]

        ;; Should match (slug is lowercase)
        (t/is (some? file-res))
        (t/is (= 1 (count (:done file-res))))
        (t/is (= (:id lib-b) (:linked-to (first (:done file-res)))))))))

;; -----------------------------------------------------------------------------
;; Category 7: Reference Integrity
;; -----------------------------------------------------------------------------

(t/deftest link-later-reference-integrity-component-file-remapped
  (let [{:keys [profile file-id team-id file]} (import-sample-file)
        output (tmp/tempfile :suffix ".zip")]

    ;; Export file with link-later
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{file-id})
         (assoc ::bfc/export-type :link-later)
         (assoc ::bfc/output (io/output-stream output))))

    ;; Re-import in same team
    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id team-id)
                     (assoc ::bfc/input output)
                     (v3/import-files!))
          new-file-id (first (:file-ids result))
          resolution (:resolution result)
          file-res (get resolution new-file-id)]

      ;; Verify auto-link happened
      (t/is (= 1 (count (:done file-res))))
      (let [linked-lib-id (:linked-to (first (:done file-res)))]
        ;; Get the imported file's data
        (let [imported (:result (th/command! {::th/type :get-file
                                              ::rpc/profile-id (:id profile)
                                              :id new-file-id
                                              :components-v2 true}))
              shapes (get-file-shapes (:data imported))]
          ;; Check that component-file references point to the new library
          (doseq [shape shapes]
            (when (contains? shape :component-file)
              (t/is (= linked-lib-id (:component-file shape))
                    "component-file should reference the linked library"))))))))

(t/deftest link-later-reference-integrity-no-match-dangling-refs
  (let [{:keys [profile file-id]} (import-sample-file)
        team2 (th/create-team* 2 {:profile-id (:id profile)})
        output (tmp/tempfile :suffix ".zip")]

    ;; Export file with link-later
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{file-id})
         (assoc ::bfc/export-type :link-later)
         (assoc ::bfc/output (io/output-stream output))))

    ;; Import in team 2 (no library exists)
    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id (:id team2))
                     (assoc ::bfc/input output)
                     (v3/import-files!))
          new-file-id (first (:file-ids result))]

      ;; Get the original library id from the manifest
      (let [manifest (v3/get-manifest th/*system* output)
            original-lib-id (:id (first (:external-libraries manifest)))]
        ;; Get the imported file's data
        (let [imported (:result (th/command! {::th/type :get-file
                                              ::rpc/profile-id (:id profile)
                                              :id new-file-id
                                              :components-v2 true}))
              file-data (:data imported)
              pages (vals (:pages-index file-data))
              all-shapes (mapcat vals (map :objects pages))
              shapes-with-refs (filter #(contains? % :component-file) all-shapes)]
          ;; component-file refs should remain as original (dangling)
          (t/is (seq shapes-with-refs) "expected shapes with component-file refs")
          (doseq [shape shapes-with-refs]
            (t/is (= original-lib-id (:component-file shape))
                  "component-file should remain as original UUID when no match")))))))

;; -----------------------------------------------------------------------------
;; Category 8: Resolution Structure Verification
;; -----------------------------------------------------------------------------

(t/deftest link-later-resolution-structure-single-file-single-lib
  (let [profile (th/create-profile* 1)
        team-id (:default-team-id profile)
        lib (th/create-file* 1 {:profile-id (:id profile)
                                :project-id (:default-project-id profile)
                                :is-shared true
                                :name "Icons Library"})
        file (th/create-file* 2 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared false})]

    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Delete and recreate library
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib)})
      (let [lib-b (th/create-file* 10 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)
                                       :is-shared true
                                       :name "Icons Library"})

            result (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/team-id team-id)
                       (assoc ::bfc/input output)
                       (v3/import-files!))
            resolution (:resolution result)
            new-file-id (first (:file-ids result))
            file-res (get resolution new-file-id)]

        ;; Verify exact structure
        (t/is (map? file-res))
        (t/is (= new-file-id (:id file-res)))
        (t/is (some? (:name file-res)))
        (t/is (vector? (:done file-res)))
        (t/is (vector? (:pending file-res)))
        (t/is (= 1 (count (:done file-res))))

        ;; Verify done entry structure
        (let [done-entry (first (:done file-res))]
          (t/is (contains? done-entry :id))
          (t/is (contains? done-entry :name))
          (t/is (contains? done-entry :linked-to))
          (t/is (= (:id lib) (:id done-entry)))
          (t/is (= "Icons Library" (:name done-entry)))
          (t/is (= (:id lib-b) (:linked-to done-entry))))))))

(t/deftest link-later-resolution-structure-multi-match
  (let [profile (th/create-profile* 1)
        team-id (:default-team-id profile)
        lib (th/create-file* 1 {:profile-id (:id profile)
                                :project-id (:default-project-id profile)
                                :is-shared true
                                :name "Icons Library"})
        file (th/create-file* 2 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared false})]

    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file) :library-file-id (:id lib)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/export-type :link-later)
           (assoc ::bfc/output (io/output-stream output))))

      ;; Delete and create two libraries with same name
      (db/update! th/*system* :file {:deleted-at (ct/now)} {:id (:id lib)})
      (let [_ (th/create-file* 10 {:profile-id (:id profile)
                                   :project-id (:default-project-id profile)
                                   :is-shared true
                                   :name "Icons Library"})
            _ (th/create-file* 11 {:profile-id (:id profile)
                                   :project-id (:default-project-id profile)
                                   :is-shared true
                                   :name "Icons Library"})

            result (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/team-id team-id)
                       (assoc ::bfc/input output)
                       (v3/import-files!))
            resolution (:resolution result)
            new-file-id (first (:file-ids result))
            file-res (get resolution new-file-id)]

        ;; Verify pending structure
        (t/is (= [] (:done file-res)))
        (t/is (= 1 (count (:pending file-res))))

        (let [pending-entry (first (:pending file-res))]
          (t/is (contains? pending-entry :id))
          (t/is (contains? pending-entry :name))
          (t/is (contains? pending-entry :candidates))
          (t/is (= (:id lib) (:id pending-entry)))
          (t/is (= "Icons Library" (:name pending-entry)))
          (t/is (= 2 (count (:candidates pending-entry))))

          ;; Verify candidate structure
          (doseq [candidate (:candidates pending-entry)]
            (t/is (contains? candidate :id))
            (t/is (contains? candidate :name))
            (t/is (contains? candidate :project-id))
            (t/is (contains? candidate :project-name))))))))

;; -----------------------------------------------------------------------------
;; Code Review Regression Tests: Reference Integrity Bug Fix
;; -----------------------------------------------------------------------------

(t/deftest link-later-multi-match-leaves-refs-dangling
  (let [{:keys [profile file-id team-id library-id]} (import-sample-file)
        output (tmp/tempfile :suffix ".zip")]

    ;; Create a SECOND library with the same name in the same team
    (create-named-library th/*system* 2 team-id "LIbrary")

    ;; Export file with link-later
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{file-id})
         (assoc ::bfc/export-type :link-later)
         (assoc ::bfc/output (io/output-stream output))))

    ;; Re-import in same team
    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id team-id)
                     (assoc ::bfc/input output)
                     (v3/import-files!))
          new-file-id (first (:file-ids result))
          resolution (:resolution result)
          file-res (get resolution new-file-id)]

      ;; Multi-match should produce pending candidates, not auto-link
      (t/is (seq (:pending file-res))
            "multi-match should produce pending candidates")
      (t/is (= [] (:done file-res))
            "multi-match should NOT auto-link")

      ;; Refs must remain as original UUID (dangling), NOT remapped to any
      ;; of the candidate libraries
      (let [manifest (v3/get-manifest th/*system* output)
            original-lib-id (:id (first (:external-libraries manifest)))
            imported (:result (th/command! {::th/type :get-file
                                            ::rpc/profile-id (:id profile)
                                            :id new-file-id
                                            :components-v2 true}))
            file-data (:data imported)
            pages (vals (:pages-index file-data))
            all-shapes (mapcat vals (map :objects pages))
            shapes-with-refs (filter #(contains? % :component-file) all-shapes)]
        (t/is (seq shapes-with-refs) "expected shapes with component-file refs")
        ;; The key assertion: refs should NOT be remapped to any candidate
        ;; (they should remain as the original UUID from the manifest)
        (let [slug (-> manifest :external-libraries first :slug)
              matching (into #{} (map :id (bfc/find-shared-files-by-slug th/*system* team-id slug)))
              candidate-ids (disj matching original-lib-id)]
          (doseq [shape shapes-with-refs]
            (t/is (not (contains? candidate-ids (:component-file shape)))
                  "component-file must NOT be remapped to any candidate library")
            (t/is (= original-lib-id (:component-file shape))
                  "component-file must remain as original UUID on multi-match")))))))

(t/deftest link-later-no-edit-permission-leaves-refs-dangling
  (let [{:keys [profile file-id team-id library-id]} (import-sample-file)
        output (tmp/tempfile :suffix ".zip")]

    ;; Export file with link-later
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{file-id})
         (assoc ::bfc/export-type :link-later)
         (assoc ::bfc/output (io/output-stream output))))

    ;; Create a viewer profile (no edit permission on the library)
    (let [viewer (th/create-profile* th/*system* 2 {})
          _ (th/create-team-role* {:team-id team-id
                                   :profile-id (:id viewer)
                                   :role :viewer})]

      ;; Import as viewer
      (let [result (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id viewer))
                       (assoc ::bfc/team-id team-id)
                       (assoc ::bfc/input output)
                       (v3/import-files!))
            new-file-id (first (:file-ids result))
            resolution (:resolution result)
            file-res (get resolution new-file-id)]

        ;; Viewer lacks edit permission, so no auto-link
        (t/is (or (nil? file-res)
                  (= [] (:done file-res)))
              "viewer should NOT auto-link")

        ;; Refs must remain as original UUID (dangling)
        (let [manifest (v3/get-manifest th/*system* output)
              original-lib-id (:id (first (:external-libraries manifest)))
              imported (:result (th/command! {::th/type :get-file
                                              ::rpc/profile-id (:id viewer)
                                              :id new-file-id
                                              :components-v2 true}))
              file-data (:data imported)
              pages (vals (:pages-index file-data))
              all-shapes (mapcat vals (map :objects pages))
              shapes-with-refs (filter #(contains? % :component-file) all-shapes)]
          (t/is (seq shapes-with-refs) "expected shapes with component-file refs")
          (doseq [shape shapes-with-refs]
            (t/is (= original-lib-id (:component-file shape))
                  "component-file must remain as original UUID when viewer has no edit permission")))))))

(t/deftest export-type-takes-precedence-over-legacy-boolean
  (let [{:keys [file-id]} (import-sample-file)
        output (tmp/tempfile :suffix ".zip")]

    ;; Call export with BOTH type=:link-later AND include-libraries=true
    ;; type should win
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{file-id})
         (assoc ::bfc/export-type :link-later)
         (assoc ::bfc/include-libraries true)
         (assoc ::bfc/output (io/output-stream output))))

    ;; Verify manifest has external-libraries (only produced by link-later)
    (let [manifest (v3/get-manifest th/*system* output)
          ext-libs (:external-libraries manifest)]
      (t/is (some? ext-libs)
            "type :link-later should produce external-libraries even with include-libraries=true")
      (t/is (pos? (count ext-libs))))))

(t/deftest import-rejects-too-many-zip-entries
  ;; import must reject ZIP files exceeding max-zip-entries (default-max-zip-entries)
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        output  (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{(:id file)})
         (assoc ::bfc/export-type :detach-libraries)
         (assoc ::bfc/output (io/output-stream output))))

    ;; Import with max-zip-entries=1 — the exported ZIP has more entries
    (let [cfg (-> th/*system*
                  (assoc ::bfc/project-id (:default-project-id profile))
                  (assoc ::bfc/profile-id (:id profile))
                  (assoc ::bfc/team-id (:default-team-id profile))
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

        sobject (sto/put-object! storage {::sto/content (sto/content "<svg xmlns=\"http://www.w3.org/2000/svg\"/>")
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

(def ^:private png-bytes
  "A 1x1 transparent PNG, already compressed and small enough to buffer."
  (.decode (java.util.Base64/getDecoder)
           "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))

(defn- prepare-file-with-png-media
  "Like `prepare-file-with-media`, with a png storage object, whose payload is
  already compressed."
  [profile]
  (let [storage (-> (:app.storage/storage th/*system*)
                    (stt/configure-storage-backend))

        sobject (sto/put-object! storage {::sto/content (sto/content png-bytes)
                                          :content-type "image/png"
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

    {:file (dissoc file :data) :media-id (:id sobject)}))

(t/deftest import-rejects-oversized-object
  ;; import must reject storage objects exceeding default-max-binary-entry-size
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-media profile)
        output  (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{(:id file)})
         (assoc ::bfc/export-type :detach-libraries)
         (assoc ::bfc/output (io/output-stream output))))

    ;; Import with max-binary-entry-size=1 — the media object will exceed this
    (let [cfg (-> th/*system*
                  (assoc ::bfc/project-id (:default-project-id profile))
                  (assoc ::bfc/profile-id (:id profile))
                  (assoc ::bfc/team-id (:default-team-id profile))
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
         (assoc ::bfc/file-ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false)
         (assoc ::bfc/output (io/output-stream exported))))

    (let [bombed (tmp/tempfile :suffix ".zip")]
      (replace-zip-entry! exported bombed
                          (str "files/" (:id file) ".json")
                          bomb-entry-size)
      (let [cfg (-> th/*system*
                    (assoc ::bfc/project-id (:default-project-id profile))
                    (assoc ::bfc/profile-id (:id profile))
                    (assoc ::bfc/team-id (:default-team-id profile))
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
                (v3/get-manifest th/*system* bombed)
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
         (assoc ::bfc/file-ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false)
         (assoc ::bfc/output (io/output-stream output))))

    (let [cfg (-> th/*system*
                  (assoc ::bfc/project-id (:default-project-id profile))
                  (assoc ::bfc/profile-id (:id profile))
                  (assoc ::bfc/team-id (:default-team-id profile))
                  (assoc ::bfc/input output)
                  (assoc ::bfc/import-max-text-total-size 100))
          out (try-import-files! cfg)]
      (t/is (= :validation (:type out)))
      (t/is (= :max-file-size-reached (:code out))))))

(t/deftest default-text-total-budget-fits-giant-legit-files
  ;; A real-world giant export held ~437 MiB of JSON across ~178k
  ;; KB-sized entries without tripping any per-entry cap. The default
  ;; cumulative budget must clear that scale, or such files cannot be
  ;; imported out of the box.
  (t/is (>= bfc/default-max-text-total-size (* 1024 1024 450))))

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
         (assoc ::bfc/file-ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false)
         (assoc ::bfc/output (io/output-stream exported))))

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
                    (assoc ::bfc/team-id (:default-team-id profile))
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

(def ^:private sql:current-idle-timeout
  "SELECT current_setting('idle_in_transaction_session_timeout')::interval
          = interval '30 minutes' AS ok")

(t/deftest binfile-transactions-use-a-finite-idle-timeout
  ;; The binfile import and export transactions must set a finite
  ;; ceiling for idle_in_transaction_session_timeout (30 min) instead
  ;; of disabling it entirely: a stalled transaction must not retain a
  ;; pool connection without any upper bound. The ceiling is scoped
  ;; with SET LOCAL: once the transaction ends, the pool session
  ;; default is restored for subsequent transactions.
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

(def ^:private index-test-file-id "22222222-2222-2222-2222-222222222222")
(def ^:private index-test-page-id "44444444-4444-4444-4444-444444444444")

(defn- index-entries-of
  [names]
  (@#'v3/index-entries (map #(java.util.zip.ZipEntry. %) names)))

(t/deftest index-entries-classifies-entry-names
  (let [f     index-test-file-id
        page  index-test-page-id
        index (index-entries-of
               ["manifest.json"
                (str "files/" f ".json")
                (str "files/" f "/plugin-data.json")
                (str "files/" f "/tokens.json")
                (str "files/" f "/media.json")
                (str "files/" f "/media/chunks/1.json.zstd")
                "objects.json"
                "objects/chunks/2.json"
                "objects/11111111-1111-1111-1111-111111111111.json"
                (str "files/" f "/media/33333333-3333-3333-3333-333333333333.json")
                (str "files/" f "/colors/not-a-uuid.json")
                (str "files/" f "/components/comp.json")
                (str "files/" f "/typographies/t.json")
                (str "files/" f "/pages/" page ".json")
                (str "files/" f "/pages/" page "/shape.json")
                (str "files/" f "/pages/" page "/chunks/0.json.zstd")
                (str "files/" f "/thumbnails/medium/" page "/frame.json")])]

    (t/is (= [(parse-uuid "11111111-1111-1111-1111-111111111111")]
             (mapv :id (:objects index))))

    (t/is (some? (:objects-packed index))
          "objects.json is indexed as the packed metadata of the storage objects")
    (t/is (= 2 (:index (first (:objects-chunks index))))
          "objects/chunks/<n>.json is indexed by its index")

    (t/is (some? (get-in index [:media-packed (str f)]))
          "media.json is indexed as the packed family of the file")
    (t/is (= 1 (:index (first (get-in index [:media-chunks (str f)]))))
          "media/chunks/<n>.json.zstd is indexed by its index")

    (t/is (= #{f} (set (keys (:media index)))))
    (t/is (= [(parse-uuid "33333333-3333-3333-3333-333333333333")]
             (mapv :id (get (:media index) f))))

    (t/is (= #{f} (set (keys (:colors index)))))
    ;; non-uuid ids are preserved as nil, same as the matchers do today
    (t/is (= [nil] (mapv :id (get (:colors index) f))))

    (t/is (= #{f} (set (keys (:components index)))))
    (t/is (= #{f} (set (keys (:typographies index)))))

    (t/is (= #{f} (set (keys (:pages index)))))
    (t/is (= [(parse-uuid page)] (mapv :id (get (:pages index) f))))

    (t/is (= 0 (:index (first (get-in index [:page-chunks f page]))))
          "a .json.zstd chunk is indexed by its index")

    (t/is (= #{f} (set (keys (:shapes index)))))
    (t/is (= #{page} (set (keys (get (:shapes index) f)))))
    (t/is (= [nil] (mapv :id (get (get (:shapes index) f) page))))

    (t/is (= #{f} (set (keys (:thumbnails index)))))
    (let [thumb (first (get (:thumbnails index) f))]
      (t/is (= "medium" (:tag thumb)))
      (t/is (= (parse-uuid page) (:page-id thumb)))
      (t/is (= (parse-uuid "frame") (:frame-id thumb)))
      (t/is (= f (:file-id thumb))))

    (t/is (= #{f} (set (keys (:tokens index)))))))

(t/deftest index-entries-ignores-unknown-entry-names
  ;; unknown paths, wrong depth, wrong suffix or non-uuid file segments
  ;; are all ignored, same as today's anchored regexes
  (t/is (empty? (index-entries-of ["manifest.json"])))
  (t/is (empty? (index-entries-of [(str "files/" index-test-file-id ".json")])))
  (t/is (empty? (index-entries-of [(str "files/" index-test-file-id "/plugin-data.json")])))
  (t/is (empty? (index-entries-of ["objects/a/b.json"])))
  (t/is (empty? (index-entries-of [(str "files/" index-test-file-id "/media/a/b.json")])))
  (t/is (empty? (index-entries-of [(str "files/" index-test-file-id "/media/a.txt")])))
  (t/is (empty? (index-entries-of [(str "files/" index-test-file-id "/unknown/a.json")])))
  (t/is (empty? (index-entries-of [(str "files//media/a.json")]))))

(t/deftest index-entries-rejects-an-unregistered-codec
  (let [path  (str "files/" index-test-file-id "/media/"
                   "44444444-4444-4444-4444-444444444444.json.lz4")
        cause (try
                (index-entries-of [path])
                nil
                (catch clojure.lang.ExceptionInfo cause cause))]
    (t/is (= :unsupported-binfile-codec (:code (ex-data cause)))
          "an entry with a codec nobody registered fails instead of being ignored")
    (t/is (= path (:path (ex-data cause)))
          "and the error names the entry, so it can be found in the package")))

(t/deftest index-entries-requires-literal-json-suffix
  ;; deliberate tightening vs today's regexes: the dot is unescaped in
  ;; `([^/]+).json$` / `tokens.json$`, so today `tokensXjson` and
  ;; `objects/x-json` DO match; the classifier requires a literal
  ;; `.json` suffix and ignores them
  (t/is (empty? (index-entries-of [(str "files/" index-test-file-id "/tokensXjson")])))
  (t/is (empty? (index-entries-of ["objects/x-json"]))))

(t/deftest import-binfile-v3-multiple-files-preserves-per-file-content
  ;; the entries index must attribute every zip entry to the file it
  ;; belongs to: with several files in the same manifest, pages and
  ;; shapes of one file must not leak into another. The file also
  ;; carries library content (color, typography, component and tokens
  ;; set) so every rewired consumer is exercised on its present path.
  (let [profile (th/create-profile* 1)
        simple  (prepare-simple-file profile)
        color-id (uuid/custom 5 1)
        typo-id  (uuid/custom 5 2)
        comp-id  (uuid/custom 5 3)
        svg     (prepare-svg-raw-file profile 2)
        output  (tmp/tempfile :suffix ".zip")]

    (update-file!
     :file-id (:id simple)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-color
       :color {:id color-id
               :name "import-color"
               :color "#FF0000"}}
      {:type :add-typography
       :typography {:id typo-id
                    :name "import-typography"
                    :font-id "source-sans-pro"
                    :font-family "Source Sans Pro"
                    :font-variant-id "regular"
                    :font-size "16"
                    :font-weight "400"
                    :font-style "normal"
                    :line-height "1.4"
                    :letter-spacing "0"
                    :text-transform "none"}}
      {:type :add-component
       :id comp-id
       :name "import-component"
       :path ""
       :main-instance-id (uuid/custom 2 1)
       :main-instance-page (uuid/custom 1 1)}
      {:type :set-tokens-lib
       :tokens-lib (-> (ctob/make-tokens-lib)
                       (ctob/add-set (ctob/make-token-set :name "ImportSet")))}])

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{(:id simple) (:id svg)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false)
         (assoc ::bfc/output (io/output-stream output))))

    ;; import returns the imported file ids plus the library link
    ;; resolution; the test only needs the ids
    (let [result (:file-ids (-> th/*system*
                                (assoc ::bfc/project-id (:default-project-id profile))
                                (assoc ::bfc/profile-id (:id profile))
                                (assoc ::bfc/team-id (:default-team-id profile))
                                (assoc ::bfc/input output)
                                (v3/import-files!)))
          files  (map #(bfc/get-file th/*system* %) result)
          svg-imported (some #(when (contains? (get-in % [:data :pages-index
                                                          svg-raw-page-id
                                                          :objects])
                                               svg-raw-root-id)
                                %)
                             files)
          simple-imported (some #(when (contains? (get-in % [:data :pages-index
                                                             (uuid/custom 1 1)
                                                             :objects])
                                                  (uuid/custom 2 1))
                                   %)
                                files)]

      (t/is (= 2 (count result)))
      (t/is (= 2 (count (distinct result))))
      (t/is (some? svg-imported))
      (t/is (some? simple-imported))

      ;; the svg-raw file keeps its subtree on its own page (plus the
      ;; default page created by create-file)
      (t/is (= [svg-raw-child-id]
               (get-in svg-imported [:data :pages-index svg-raw-page-id
                                     :objects svg-raw-root-id :shapes])))
      (t/is (= 2 (count (get-in svg-imported [:data :pages-index]))))

      ;; the simple file keeps its default page plus its two pages and
      ;; its shape
      (t/is (= 3 (count (get-in simple-imported [:data :pages-index]))))
      (t/is (contains? (get-in simple-imported
                               [:data :pages-index (uuid/custom 1 1) :objects])
                       (uuid/custom 2 1)))

      ;; library content is restored on its present path: the
      ;; consumers' get-in keys must match the classifier buckets
      (t/is (some? (get-in simple-imported [:data :colors color-id])))
      (t/is (some? (get-in simple-imported [:data :typographies typo-id])))
      (t/is (some? (get-in simple-imported [:data :components comp-id])))
      (t/is (= ["ImportSet"]
               (vec (ctob/get-set-names
                     (get-in simple-imported [:data :tokens-lib]))))))))

(t/deftest import-binfile-v3-restores-media-objects
  ;; storage objects, per-file media entries and object thumbnails
  ;; are classified through the entries index and restored end to end
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-media profile)
        thumb-page-id  (uuid/custom 1 1)
        thumb-frame-id (uuid/custom 6 1)
        thumb-tag      "medium"
        output  (tmp/tempfile :suffix ".zip")]

    ;; a thumbnail row backed by the same storage object as the media
    ;; object, so the export produces a thumbnails/ zip entry
    (let [mobj (th/db-get :file-media-object {:file-id (:id file)})]
      (db/insert! th/*system* :file-tagged-object-thumbnail
                  {:file-id (:id file)
                   :tag thumb-tag
                   :object-id (thc/fmt-object-id {:file-id (:id file)
                                                  :page-id thumb-page-id
                                                  :frame-id thumb-frame-id
                                                  :tag thumb-tag})
                   :media-id (:media-id mobj)}))

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/file-ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false)
         (assoc ::bfc/output (io/output-stream output))))

    ;; import returns the imported file ids plus the library link
    ;; resolution; the test only needs the ids
    (let [result  (:file-ids (-> th/*system*
                                 (assoc ::bfc/project-id (:default-project-id profile))
                                 (assoc ::bfc/profile-id (:id profile))
                                 (assoc ::bfc/team-id (:default-team-id profile))
                                 (assoc ::bfc/input output)
                                 (v3/import-files!)))
          mobjs   (db/query th/*system* :file-media-object
                            {:file-id (first result)})
          thumbs  (db/query th/*system* :file-tagged-object-thumbnail
                            {:file-id (first result)})]
      (t/is (= 1 (count result)))
      (t/is (pos? (count mobjs)))
      (t/is (every? some? (map :media-id mobjs)))

      ;; the thumbnail is restored with its object-id rebuilt around
      ;; the new file id and a media-id that resolves to storage
      (t/is (= 1 (count thumbs)))
      (let [thumb (first thumbs)]
        (t/is (= thumb-tag (:tag thumb)))
        (t/is (= (str (first result) "/" thumb-page-id "/" thumb-frame-id "/" thumb-tag)
                 (:object-id thumb)))
        (t/is (some? (:media-id thumb)))))))

(t/deftest get-file-without-data-can-lock
  ;; Locking is legal without loading the data: the row-only query selects
  ;; directly from `file`, so `FOR UPDATE`/`FOR SHARE` apply to the row and
  ;; the lock lives until the enclosing transaction ends. `SKIP LOCKED` only
  ;; modifies a locking read, so on its own it is ignored. The SQL is pinned
  ;; because a plain `SELECT` also returns the row: the lock clause is what
  ;; serializes with the other writers.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    (doseq [[opt sql] {:lock-for-share?  (str bfc/sql:get-file-without-data " FOR SHARE of f")
                       :lock-for-update? (str bfc/sql:get-file-without-data " FOR UPDATE of f")
                       :skip-locked?     bfc/sql:get-file-without-data}]
      (let [[row sqls] (th/read-recording-sql
                        #(bfc/get-file th/*system* (:id file) :load-data? false opt true))]
        (t/is (= (:id file) (:id row)))
        (t/is (nil? (:data row)))
        (t/is (= [sql] sqls))))))

(t/deftest get-file-rejects-realize-without-data
  ;; `:realize?` walks the decoded data; asking for it without loading or
  ;; decoding the data is a caller bug. The guard fires before any query, so
  ;; no file row is needed.
  (let [id (uuid/random)]
    (t/is (thrown? IllegalArgumentException
                   (bfc/get-file th/*system* id :realize? true :decode? false)))
    (t/is (thrown? IllegalArgumentException
                   (bfc/get-file th/*system* id :realize? true :load-data? false)))))

(t/deftest get-file-rejects-a-shared-lock-with-data
  ;; The probe inherits the caller's lock and the migration branch re-reads
  ;; with FOR UPDATE, so a shared lock on a data read is a lock upgrade. The
  ;; guard fires before any query, so no row is needed.
  (t/is (thrown? IllegalArgumentException
                 (bfc/get-file th/*system* (uuid/random) :lock-for-share? true))))

(t/deftest get-file-rejects-a-bare-pool-or-connection
  ;; The read opens a transaction (unless it is read-only) and resolves the
  ;; cfg, so it takes a cfg map only: a bare pool or connection is a caller
  ;; bug. The guard is an `IllegalArgumentException`, not an `assert`, which
  ;; is elided when the backend runs without `:backend-asserts`.
  (let [id (uuid/random)]
    (t/is (thrown? IllegalArgumentException (bfc/get-file th/*pool* id)))
    (with-open [conn (db/open th/*system*)]
      (t/is (thrown? IllegalArgumentException (bfc/get-file conn id))))))

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
    (t/is (empty? (th/db-query :file-library-rel {:file-id file-id})))
    (t/is (empty? (th/db-query :file-library-sync {:file-id file-id})))))

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

(t/deftest import-skips-circular-library-relations
  ;; https://github.com/penpot/penpot/issues/12052: two libraries that use
  ;; each other (possible before the 2.17.1 guard) export both relations in
  ;; the manifest; the import must skip the one closing the cycle instead
  ;; of recreating it. The cycle is built with direct inserts because the
  ;; UI command `link-file-to-library` already rejects it. Relations are
  ;; processed in sorted manifest order and the first edge wins.
  (let [profile (th/create-profile* 1)
        lib-a   (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Library A"})
        lib-b   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Library B"})]
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id lib-a)
                 :library-file-id (:id lib-b)})
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id lib-b)
                 :library-file-id (:id lib-a)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id lib-a) (:id lib-b)})
           (assoc ::bfc/export-type :include-libraries)
           (assoc ::bfc/output (io/output-stream output))))

      (let [result (import-bundle profile output)
            rels   (mapcat #(db/query th/*system* :file-library-rel {:file-id %})
                           result)
            syncs  (mapcat #(db/query th/*system* :file-library-sync {:file-id %})
                           result)]
        (t/is (= 2 (count result)))
        ;; only one of the two cycle edges survives the import
        (t/is (= 1 (count rels)))
        ;; the skipped edge leaves no sync row behind
        (t/is (= 1 (count syncs)))
        (t/is (= (set (map (juxt :file-id :library-file-id) syncs))
                 (set (map (juxt :file-id :library-file-id) rels))))
        ;; no imported file lists itself among its transitive libraries
        (doseq [file-id result]
          (t/is (not (contains? (bfc/get-libraries th/*system* [file-id])
                                file-id))))))))

(t/deftest import-skips-transitive-cycle
  ;; A three-node cycle (A -> B -> C -> A) only breaks when the check uses
  ;; the transitive closure: a direct-edge-only check would keep all three
  ;; edges. Built with direct inserts because the UI command rejects it.
  (let [profile (th/create-profile* 1)
        lib-a   (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Library A"})
        lib-b   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Library B"})
        lib-c   (th/create-file* 3 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Library C"})]
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id lib-a)
                 :library-file-id (:id lib-b)})
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id lib-b)
                 :library-file-id (:id lib-c)})
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id lib-c)
                 :library-file-id (:id lib-a)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id lib-a) (:id lib-b) (:id lib-c)})
           (assoc ::bfc/export-type :include-libraries)
           (assoc ::bfc/output (io/output-stream output))))

      (let [result (import-bundle profile output)
            rels   (mapcat #(db/query th/*system* :file-library-rel {:file-id %})
                           result)
            syncs  (mapcat #(db/query th/*system* :file-library-sync {:file-id %})
                           result)]
        (t/is (= 3 (count result)))
        ;; only two of the three cycle edges survive the import
        (t/is (= 2 (count rels)))
        ;; the skipped edge leaves no sync row behind
        (t/is (= 2 (count syncs)))
        (t/is (= (set (map (juxt :file-id :library-file-id) syncs))
                 (set (map (juxt :file-id :library-file-id) rels))))
        ;; no imported file lists itself among its transitive libraries
        (doseq [file-id result]
          (t/is (not (contains? (bfc/get-libraries th/*system* [file-id])
                                file-id))))))))

(t/deftest import-keeps-acyclic-library-relations
  ;; Control test: a chain without cycles must be imported untouched, so
  ;; the new guard only skips edges that would close a cycle.
  (let [profile (th/create-profile* 1)
        lib-a   (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Library A"})
        lib-b   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Library B"})
        lib-c   (th/create-file* 3 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Library C"})]
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id lib-a)
                 :library-file-id (:id lib-b)})
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id lib-b)
                 :library-file-id (:id lib-c)})

    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id lib-a) (:id lib-b) (:id lib-c)})
           (assoc ::bfc/export-type :include-libraries)
           (assoc ::bfc/output (io/output-stream output))))

      (let [result (import-bundle profile output)
            rels   (mapcat #(db/query th/*system* :file-library-rel {:file-id %})
                           result)
            syncs  (mapcat #(db/query th/*system* :file-library-sync {:file-id %})
                           result)]
        (t/is (= 3 (count result)))
        (t/is (= 2 (count rels)))
        ;; every kept relation has its sync row
        (t/is (= 2 (count syncs)))
        (t/is (= (set (map (juxt :file-id :library-file-id) syncs))
                 (set (map (juxt :file-id :library-file-id) rels))))))))

(t/deftest import-v1-skips-circular-library-relations
  ;; Issue #12052 also names the version 1 importer: importing a package
  ;; whose libraries use each other must not recreate the cycle. The cycle
  ;; is built with direct inserts because `link-file-to-library` already
  ;; rejects it. The exported file only *uses* one of the libraries, so the
  ;; package carries the cycle the same way a real export does.
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        lib-a   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Library A"})
        lib-b   (th/create-file* 3 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Library B"})]
    (th/link-file-to-library* {:file-id (:id file)
                               :library-id (:id lib-a)})
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id lib-a)
                 :library-file-id (:id lib-b)})
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id lib-b)
                 :library-file-id (:id lib-a)})

    (let [output (tmp/tempfile :suffix ".penpot")]
      (v1/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id file)})
           (assoc ::bfc/include-libraries true))
       (io/output-stream output))

      (let [result (:file-ids (-> th/*system*
                                  (assoc ::bfc/project-id (:default-project-id profile))
                                  (assoc ::bfc/profile-id (:id profile))
                                  (assoc ::bfc/input output)
                                  (v1/import-files!)))
            rels   (mapcat #(db/query th/*system* :file-library-rel {:file-id %})
                           result)
            syncs  (mapcat #(db/query th/*system* :file-library-sync {:file-id %})
                           result)]
        (t/is (= 3 (count result)))
        ;; the relation of the file to its library, plus one of the two
        ;; cycle edges; the edge that would close the cycle is skipped
        (t/is (= 2 (count rels)))
        ;; the skipped edge leaves no sync row behind
        (t/is (= 2 (count syncs)))
        (t/is (= (set (map (juxt :file-id :library-file-id) syncs))
                 (set (map (juxt :file-id :library-file-id) rels))))
        ;; no imported file lists itself among its transitive libraries
        (doseq [file-id result]
          (t/is (not (contains? (bfc/get-libraries th/*system* [file-id])
                                file-id))))))))

(t/deftest import-v1-tolerates-repeated-file-entries
  ;; Old v1 exports could list the same file twice in the package metadata:
  ;; the exporter unioned the requested files with their libraries without
  ;; deduplicating, and a file inside a library cycle is one of its own
  ;; libraries. The import must read every entry and import each file once.
  (let [profile (th/create-profile* 1)
        lib-a   (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Library A"})
        lib-b   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true
                                    :name "Library B"})]
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id lib-a)
                 :library-file-id (:id lib-b)})
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id lib-b)
                 :library-file-id (:id lib-a)})

    (let [output (tmp/tempfile :suffix ".penpot")]
      ;; exporting only lib-a repeats its id in the metadata, because it is
      ;; among its own transitive libraries
      (v1/export-files!
       (-> th/*system*
           (assoc ::bfc/file-ids #{(:id lib-a)})
           (assoc ::bfc/include-libraries true))
       (io/output-stream output))

      (let [result (:file-ids (-> th/*system*
                                  (assoc ::bfc/project-id (:default-project-id profile))
                                  (assoc ::bfc/profile-id (:id profile))
                                  (assoc ::bfc/input output)
                                  (v1/import-files!)))
            rels   (mapcat #(db/query th/*system* :file-library-rel {:file-id %})
                           result)
            syncs  (mapcat #(db/query th/*system* :file-library-sync {:file-id %})
                           result)]
        ;; two files: the repeated entry is imported once
        (t/is (= 2 (count result)))
        ;; one of the two cycle edges survives the import
        (t/is (= 1 (count rels)))
        (t/is (= 1 (count syncs)))
        (t/is (= (set (map (juxt :file-id :library-file-id) syncs))
                 (set (map (juxt :file-id :library-file-id) rels))))
        ;; no imported file lists itself among its transitive libraries
        (doseq [file-id result]
          (t/is (not (contains? (bfc/get-libraries th/*system* [file-id])
                                file-id))))))))

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
         (assoc ::bfc/file-ids #{(:id file) (:id other-file)})
         (assoc ::bfc/export-type :include-libraries)
         (assoc ::bfc/output (io/output-stream output))))
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

(t/deftest file-permissions-ignore-soft-deleted
  ;; sql:file-permissions must not grant anything through a file,
  ;; project or team that is soft-deleted (GHSA-m53j-2766-6jqw: the
  ;; websocket re-check relies on this query, and the subscribe handler
  ;; additionally refuses deleted files via check-file-exists).
  (let [owner   (th/create-profile* 1 {:is-active true})
        editor  (th/create-profile* 2 {:is-active true})
        team    (th/create-team* 1 {:profile-id (:id owner)})]
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id editor)
                           :role :editor})
    (let [project (th/create-project* 1 {:profile-id (:id editor)
                                         :team-id (:id team)})
          file    (th/create-file* 1 {:profile-id (:id editor)
                                      :project-id (:id project)})
          perms?  #(some? (bfc/get-file-permissions th/*system* (:id editor) (:id file)))
          undelete (fn [table id]
                     (db/update! th/*system* table {:deleted-at nil} {:id id}))
          soft-delete (fn [table id]
                        (db/update! th/*system* table {:deleted-at (ct/now)} {:id id}))]

      (t/testing "a live member can read the live file"
        (t/is (perms?)))

      (t/testing "a soft-deleted file grants nothing"
        (soft-delete :file (:id file))
        (t/is (nil? (bfc/get-file-permissions th/*system* (:id editor) (:id file))))
        (undelete :file (:id file))
        (t/is (perms?)))

      (t/testing "a soft-deleted project grants nothing"
        (soft-delete :project (:id project))
        (t/is (nil? (bfc/get-file-permissions th/*system* (:id editor) (:id file))))
        (undelete :project (:id project))
        (t/is (perms?)))

      (t/testing "a soft-deleted team grants nothing"
        (soft-delete :team (:id team))
        (t/is (nil? (bfc/get-file-permissions th/*system* (:id editor) (:id file))))
        (undelete :team (:id team))
        (t/is (perms?))))))

(t/deftest export-binfile-v3-compact
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id-1 shape-id]} (prepare-simple-file-with-ids profile)
        output  (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (bfc/export-cfg th/*system*
                     {:file-ids    #{file-id}
                      :export-type :detach-libraries
                      :variant     :compact
                      :output      (io/output-stream output)}))

    ;; Verify manifest version
    (let [manifest (v3/get-manifest th/*system* output)]
      (t/is (= 2 (:version manifest))))

    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id (:default-team-id profile))
                     (assoc ::bfc/input output)
                     (v3/import-files!))]
      (t/is (= 1 (count (:file-ids result))))
      (t/is (every? uuid? (:file-ids result)))

      ;; Verify imported data has correct shapes with preserved geometry
      (let [imported-id  (first (:file-ids result))
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

    (v3/export-files!
     (bfc/export-cfg th/*system*
                     {:file-ids    #{file-id}
                      :export-type :detach-libraries
                      :variant     :compact
                      :output      (io/output-stream output1)}))

    ;; Verify compact manifest
    (let [manifest (v3/get-manifest th/*system* output1)]
      (t/is (= 2 (:version manifest))))

    (let [result      (-> th/*system*
                          (assoc ::bfc/project-id (:default-project-id profile))
                          (assoc ::bfc/profile-id (:id profile))
                          (assoc ::bfc/team-id (:default-team-id profile))
                          (assoc ::bfc/input output1)
                          (v3/import-files!))
          imported-id (first (:file-ids result))]

      ;; Re-export as normal (no variant) and verify
      (v3/export-files!
       (bfc/export-cfg th/*system*
                       {:file-ids    #{imported-id}
                        :export-type :detach-libraries
                        :output      (io/output-stream output2)}))

      (let [manifest2 (v3/get-manifest th/*system* output2)
            result2   (-> th/*system*
                          (assoc ::bfc/project-id (:default-project-id profile))
                          (assoc ::bfc/profile-id (:id profile))
                          (assoc ::bfc/team-id (:default-team-id profile))
                          (assoc ::bfc/input output2)
                          (v3/import-files!))]
        (t/is (= 1 (:version manifest2)) "a re-export without variant is normal")
        (t/is (= 1 (count (:file-ids result2))))
        (t/is (every? uuid? (:file-ids result2)))

        ;; Verify shapes survive the compact -> import -> legacy re-export round-trip
        (let [reimported-id   (first (:file-ids result2))
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
          (t/is (mth/close? (:height shape) 0.01)))))))

(t/deftest export-binfile-v3-compact-page-structure
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id-1 shape-id]} (prepare-simple-file-with-ids profile)
        output  (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (bfc/export-cfg th/*system*
                     {:file-ids    #{file-id}
                      :export-type :detach-libraries
                      :variant     :compact
                      :output      (io/output-stream output)}))

    (with-open [^ZipFile zip (ZipFile. (fs/file output))]
      (let [entries     (iterator-seq (.entries zip))
            page-path   (str "files/" file-id "/pages/" page-id-1 ".json")
            chunk-path  (str "files/" file-id "/pages/" page-id-1 "/chunks/0.json")
            page-entry  (.getEntry zip page-path)
            chunk-entry (.getEntry zip chunk-path)]

        (t/is page-entry "compact page entry should exist")
        (t/is chunk-entry "the page should have its chunk")
        (t/is (= ZipEntry/DEFLATED (.getMethod chunk-entry))
              "the compact layout keeps writing deflated json chunks")

        ;; Per-shape entries should NOT exist in compact format
        (doseq [^java.util.zip.ZipEntry entry entries]
          (let [name (.getName entry)]
            (t/is (or (= name chunk-path)
                      (not (.startsWith name (str "files/" file-id "/pages/" page-id-1 "/"))))
                  (str "should not have per-shape entries: " name))))

        (t/testing "the page entry names its chunks and carries no shapes"
          (with-open [reader (io/reader (.getInputStream zip page-entry))]
            (let [page-data (cjson/read reader :key-fn cjson/read-kebab-key)]
              (t/is (= 1 (:chunks page-data)))
              (t/is (not (contains? page-data :objects))))))

        (t/testing "the chunk carries the shapes as JSONL"
          (with-open [in (.getInputStream zip chunk-entry)]
            (let [chunk-data (into {} (read-lines (io/reader in)))]
              (t/is (> (count chunk-data) 0)
                    "the chunk should not be empty")
              (t/is (contains? chunk-data (keyword (str shape-id)))
                    "the chunk should contain the shape"))))))))

(t/deftest export-binfile-v3-compact-round-trip-tree
  (let [profile  (th/create-profile* 1)
        file     (th/create-file* 2 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)
                                     :is-shared false})
        page-id  (uuid/next)
        frame-id (uuid/next)
        group-id (uuid/next)
        rect-id  (uuid/next)
        output   (tmp/tempfile :suffix ".zip")]

    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :changes
     [{:type :add-page :name "tree" :id page-id}])

    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :changes
     [{:type :add-obj
       :page-id page-id
       :id frame-id
       :parent-id uuid/zero
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id frame-id :name "Board" :type :frame
              :x 0 :y 0 :width 400 :height 300
              :parent-id uuid/zero :frame-id uuid/zero})}
      {:type :add-obj
       :page-id page-id
       :id group-id
       :parent-id frame-id
       :frame-id frame-id
       :components-v2 true
       :obj (-> (cts/setup-shape
                 {:id group-id :name "Group" :type :group
                  :x 10 :y 10 :width 100 :height 80
                  :parent-id frame-id :frame-id frame-id})
                (assoc :opacity 1
                       :blend-mode :normal
                       :layout-item-h-sizing :fix
                       :layout-item-v-sizing :fix
                       :layout-item-absolute false
                       :touched #{}
                       :constraints-h :left
                       :constraints-v :top))}
      {:type :add-obj
       :page-id page-id
       :id rect-id
       :parent-id group-id
       :frame-id frame-id
       :components-v2 true
       :obj (cts/setup-shape
             {:id rect-id :name "Rect" :type :rect
              :x 20 :y 20 :width 40 :height 30
              :parent-id group-id :frame-id frame-id})}])

    (let [source (-> (bfc/get-file th/*system* (:id file) {:realize? true})
                     :data :pages-index (get page-id))]
      (v3/export-files!
       (bfc/export-cfg th/*system*
                       {:file-ids    #{(:id file)}
                        :export-type :detach-libraries
                        :variant     :compact
                        :output      (io/output-stream output)}))

      ;; The stored shapes carry no id that the key or the tree rebuilds
      (with-open [^ZipFile zip (ZipFile. (fs/file output))]
        (let [page-path  (str "files/" (:id file) "/pages/" page-id ".json")
              chunk-path (str "files/" (:id file) "/pages/" page-id "/chunks/0.json")
              page-entry (.getEntry zip page-path)
              chunk      (.getEntry zip chunk-path)]
          (t/is (some? page-entry) "compact page entry should exist")

          (with-open [reader (io/reader (.getInputStream zip page-entry))]
            (let [page-data (cjson/read reader :key-fn cjson/read-kebab-key)]
              (t/is (= 1 (:chunks page-data)) "the page entry names its chunk")
              (t/is (not (contains? page-data :objects))
                    "the shapes do not travel in the page entry")))

          (t/is (some? chunk) "the page should have its chunk")
          (with-open [in (.getInputStream zip chunk)]
            (let [stored-objects (into {} (read-lines (io/reader in)))]
              (doseq [id [frame-id group-id rect-id]]
                (let [stored (get stored-objects (keyword (str id)))]
                  (t/is (some? stored) (str "stored shape " id))
                  (t/is (not (contains? stored :id)) (str "no :id on " id))
                  (t/is (contains? stored :parent-id)
                        (str "the parent stays as data on " id))
                  (t/is (contains? stored :frame-id)
                        (str "the frame stays as data on " id))))

              ;; The group carried only default values, an empty set and the
              ;; two sizing keys, which are kept on purpose
              (let [stored (get stored-objects (keyword (str group-id)))]
                (doseq [k [:opacity :blend-mode :layout-item-absolute :touched
                           :constraints-h :constraints-v]]
                  (t/is (not (contains? stored k)) (str "no " k " on the group")))
                (doseq [k [:layout-item-h-sizing :layout-item-v-sizing]]
                  (t/is (contains? stored k) (str k " stays on the group"))))))))

      ;; And the import rebuilds them
      (let [result      (-> th/*system*
                            (assoc ::bfc/project-id (:default-project-id profile))
                            (assoc ::bfc/profile-id (:id profile))
                            (assoc ::bfc/team-id (:default-team-id profile))
                            (assoc ::bfc/input output)
                            (v3/import-files!))
            imported-id (first (:file-ids result))
            imported    (-> (bfc/get-file th/*system* imported-id {:realize? true})
                            :data :pages-index (get page-id))]

        (t/is (some? imported) "page should exist after import")

        (doseq [id [frame-id group-id rect-id]]
          (doseq [k [:id :parent-id :frame-id :x :y :width :height]]
            (t/is (= (get-in source [:objects id k])
                     (get-in imported [:objects id k]))
                  (str k " of " id " reads back unchanged"))))

        (let [group (get-in imported [:objects group-id])
              rect  (get-in imported [:objects rect-id])]
          (t/is (= 1 (:opacity group)) "a default value comes back")
          (t/is (= :normal (:blend-mode group)))
          (t/is (false? (:layout-item-absolute group)))
          (t/is (= :fix (:layout-item-h-sizing group))
                "the sizing survives verbatim")
          (t/is (= :left (:constraints-h group)))
          (t/is (= :top (:constraints-v group)))
          (t/is (not (contains? group :touched))
                "an empty set reads back as absent")
          (t/is (= :scale (:constraints-h rect)) "the derived constraint comes back")
          (t/is (= :scale (:constraints-v rect))))

        (t/is (= uuid/zero (:frame-id (get-in imported [:objects frame-id])))
              "a top-level frame keeps the zero frame")
        (t/is (= frame-id (:parent-id (get-in imported [:objects group-id])))
              "a group inside a frame keeps its parent")
        (t/is (= frame-id (:frame-id (get-in imported [:objects rect-id])))
              "a shape inside a group keeps the frame of the group")))))

(t/deftest import-binfile-v3-compact-keeps-the-stored-ids
  ;; A version 2 package written before the pruning carries `:id` inside every
  ;; object (any writer that still does). The reader fills only what is absent,
  ;; so a stored id remains untouched.
  (let [profile    (th/create-profile* 1)
        {:keys [file-id page-id-1 shape-id]} (prepare-simple-file-with-ids profile)
        output     (tmp/tempfile :suffix ".zip")
        output2    (tmp/tempfile :suffix ".zip")
        injected   (uuid/next)
        chunk-path (str "files/" file-id "/pages/" page-id-1 "/chunks/0.json")]

    (v3/export-files!
     (bfc/export-cfg th/*system*
                     {:file-ids    #{file-id}
                      :export-type :detach-libraries
                      :variant     :compact
                      :output      (io/output-stream output)}))

    ;; Rewrite only the chunk entry, injecting an id that differs from the key
    (with-open [in  (ZipInputStream. (io/input-stream output))
                out (ZipOutputStream. (io/output-stream output2))]
      (loop []
        (when-let [^ZipEntry entry (.getNextEntry in)]
          (let [path (.getName entry)]
            (.putNextEntry out (ZipEntry. path))
            (if (= path chunk-path)
              (let [lines  (read-lines
                            (java.io.InputStreamReader.
                             (java.io.ByteArrayInputStream. (.readAllBytes in))
                             java.nio.charset.StandardCharsets/UTF_8))
                    writer (io/writer out)]
                (doseq [[k value] lines]
                  (cjson/write writer
                               [(if (keyword? k) (name k) (str k))
                                (cond-> value
                                  (= (keyword (str shape-id)) k)
                                  (assoc :id (str injected)))]
                               :key-fn cjson/write-camel-key)
                  (.write writer "\n"))
                (.flush writer))
              (.transferTo ^java.io.InputStream in out))
            (.closeEntry out)
            (recur)))))

    (let [result   (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
                       (assoc ::bfc/team-id (:default-team-id profile))
                       (assoc ::bfc/input output2)
                       (v3/import-files!))
          imported (-> (bfc/get-file th/*system* (first (:file-ids result)) {:realize? true})
                       :data :pages-index (get page-id-1))]
      (t/is (some? imported) "page should exist after import")
      (t/is (= injected (get-in imported [:objects shape-id :id]))
            "the stored id is not overwritten by the key"))))

;; --- CHUNKED PAGES

(defn- add-page-with-rects
  "A file with one page that holds `shapes` rects and nothing else, for the
  tests that need a page with several shapes."
  [profile shapes]
  (let [file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)
                                     :is-shared false})
        page-id  (uuid/next)
        rect-ids (vec (repeatedly shapes uuid/next))]
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :changes
     (into [{:type :add-page :name "chunks" :id page-id}]
           (map (fn [id]
                  {:type :add-obj
                   :page-id page-id
                   :id id
                   :parent-id uuid/zero
                   :frame-id uuid/zero
                   :components-v2 true
                   :obj (cts/setup-shape
                         {:id id
                          :name (str "rect " id)
                          :type :rect
                          :x 10 :y 20 :width 30 :height 40
                          :parent-id uuid/zero
                          :frame-id uuid/zero})}))
           rect-ids))
    {:file-id (:id file) :page-id page-id :rect-ids rect-ids}))

(defn- export-compact!
  "Exports `file-id` with the compact layout. A `budget` overrides the chunk
  size, so a test can force several chunks without a page of megabytes."
  [file-id output budget]
  (with-redefs-fn (cond-> {} (some? budget) (assoc #'pc/default-chunk-size budget))
    (fn []
      (v3/export-files!
       (bfc/export-cfg th/*system*
                       {:file-ids    #{file-id}
                        :export-type :detach-libraries
                        :variant     :compact
                        :output      (io/output-stream output)})))))

(t/deftest export-binfile-v3-compact-page-compacts-each-shape-in-the-chunk
  ;; The page writer compacts one shape at a time inside the chunk serializer,
  ;; with the same helper the page pass uses, so it never builds a compacted
  ;; copy of the page. The emitted line is exactly what that helper produces.
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id rect-ids]} (add-page-with-rects profile 2)
        output  (tmp/tempfile :suffix ".zip")
        source  (-> (bfc/get-file th/*system* file-id {:realize? true})
                    :data :pages-index (get page-id))]

    (export-compact! file-id output nil)

    (with-open [zip (ZipFile. (fs/file output))]
      (let [path     (pc/chunk-path file-id page-id 0)
            lines    (-> (.getInputStream zip (.getEntry zip path))
                         (io/reader)
                         (slurp)
                         (.split "\n")
                         (vec))
            expected (set (map (fn [id]
                                 (#'v3/serialize-shape
                                  id (fsc/compact-object id (get-in source [:objects id]))))
                               (keys (:objects source))))]
        (t/is (= (count (:objects source)) (count lines))
              "one line per shape of the page, the root frame included")
        (t/is (= expected (set lines))
              "every line is the compact-object of its shape")))))

(defn- export-compact-zstd!
  "Exports `file-id` with the zstd compact layout. A `budget` overrides the
  chunk size, so a test can force several chunks without a page of megabytes."
  [file-id output budget]
  (with-redefs-fn (cond-> {} (some? budget) (assoc #'pc/compressed-chunk-size budget))
    (fn []
      (v3/export-files!
       (bfc/export-cfg th/*system*
                       {:file-ids    #{file-id}
                        :export-type :detach-libraries
                        :variant     :compact-zstd
                        :output      (io/output-stream output)})))))

(defn- read-zip-json
  [^ZipFile zip path]
  (with-open [reader (io/reader (.getInputStream zip (.getEntry zip path)))]
    (cjson/read reader :key-fn cjson/read-kebab-key)))

(defn- read-zstd-zip-json
  [^ZipFile zip path]
  (with-open [in     (ZstdInputStream. (.getInputStream zip (.getEntry zip path)))
              reader (java.io.InputStreamReader. in java.nio.charset.StandardCharsets/UTF_8)]
    (cjson/read reader :key-fn cjson/read-kebab-key)))

(defn- import-package
  ([profile input] (import-package profile input nil))
  ([profile input extra]
   (-> th/*system*
       (merge extra)
       (assoc ::bfc/project-id (:default-project-id profile))
       (assoc ::bfc/profile-id (:id profile))
       (assoc ::bfc/team-id (:default-team-id profile))
       (assoc ::bfc/input input)
       (v3/import-files!))))

(t/deftest export-binfile-v3-compact-round-trip-align-self
  ;; Absence means automatic alignment and the sidebar clears the override with
  ;; nil, so the value is data: the compact layout writes an explicit
  ;; `:stretch`/`:center` and never invents the key on a shape that has none.
  (let [profile    (th/create-profile* 1)
        file       (th/create-file* 2 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)
                                       :is-shared false})
        page-id    (uuid/next)
        frame-id   (uuid/next)
        auto-id    (uuid/next)
        stretch-id (uuid/next)
        center-id  (uuid/next)
        output     (tmp/tempfile :suffix ".zip")
        child      (fn [id name x align-self]
                     {:type        :add-obj
                      :page-id     page-id
                      :id          id
                      :parent-id   frame-id
                      :frame-id    frame-id
                      :components-v2 true
                      :obj (cond-> (cts/setup-shape
                                    {:id id :name name :type :rect
                                     :x x :y 0 :width 40 :height 20
                                     :parent-id frame-id :frame-id frame-id})
                             (some? align-self)
                             (assoc :layout-item-align-self align-self))})]

    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :changes
     [{:type :add-page :name "flex" :id page-id}])

    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :changes
     [{:type        :add-obj
       :page-id     page-id
       :id          frame-id
       :parent-id   uuid/zero
       :frame-id    uuid/zero
       :components-v2 true
       :obj (-> (cts/setup-shape
                 {:id frame-id :name "Flex" :type :frame
                  :x 0 :y 0 :width 400 :height 200
                  :parent-id uuid/zero :frame-id uuid/zero})
                (assoc :layout-flex-dir :row))}
      (child auto-id "Auto" 0 nil)
      (child stretch-id "Stretch" 40 :stretch)
      (child center-id "Center" 80 :center)])

    (export-compact! (:id file) output nil)

    (t/testing "the chunk stores the value and never invents it"
      (with-open [^ZipFile zip (ZipFile. (fs/file output))]
        (let [chunk-path (str "files/" (:id file) "/pages/" page-id "/chunks/0.json")
              stored     (into {} (read-lines
                                   (io/reader (.getInputStream zip (.getEntry zip chunk-path)))))]
          (t/is (not (contains? (get stored (keyword (str auto-id)))
                                :layout-item-align-self))
                "an absent alignment stays absent")
          (t/is (= "stretch" (:layout-item-align-self
                              (get stored (keyword (str stretch-id)))))
                "an explicit stretch is written")
          (t/is (= "center" (:layout-item-align-self
                             (get stored (keyword (str center-id)))))
                "an explicit center is written"))))

    (t/testing "and the import reads the three states back"
      (let [result   (import-package profile output)
            imported (-> (bfc/get-file th/*system* (first (:file-ids result))
                                       {:realize? true})
                         :data :pages-index (get page-id))]
        (t/is (not (contains? (get-in imported [:objects auto-id])
                              :layout-item-align-self))
              "the key is not materialized on the absent child")
        (t/is (= :stretch (get-in imported [:objects stretch-id
                                            :layout-item-align-self])))
        (t/is (= :center (get-in imported [:objects center-id
                                           :layout-item-align-self])))))))

(t/deftest export-binfile-stores-the-already-compressed-media
  (let [profile (th/create-profile* 1)
        {:keys [file media-id]} (prepare-file-with-png-media profile)
        output  (tmp/tempfile :suffix ".zip")]

    (export-compact! (:id file) output nil)

    (with-open [zip (ZipFile. (fs/file output))]
      (t/is (= ZipEntry/STORED
               (.getMethod (.getEntry zip (str "objects/" media-id ".png"))))
            "an already-compressed binary does not go through deflate"))

    (let [result (import-package profile output)]
      (t/is (= 1 (count (db/query th/*system* :file-media-object
                                  {:file-id (first (:file-ids result))})))
            "and the import restores it from the STORED entry"))))

(t/deftest export-binfile-deflates-a-binary-that-is-not-compressed
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-media profile)
        media-id (:media-id (th/db-get :file-media-object {:file-id (:id file)}))
        output  (tmp/tempfile :suffix ".zip")]

    (export-compact! (:id file) output nil)

    (with-open [zip (ZipFile. (fs/file output))]
      (t/is (= ZipEntry/DEFLATED
               (.getMethod (.getEntry zip (str "objects/" media-id ".svg"))))
            "svg is text and deflate still shrinks it"))))

(t/deftest export-binfile-streams-a-binary-over-the-buffer-threshold
  (let [profile (th/create-profile* 1)
        {:keys [file media-id]} (prepare-file-with-png-media profile)
        output  (tmp/tempfile :suffix ".zip")]

    (with-redefs-fn {#'v3/max-buffered-binary-size 0}
      #(export-compact! (:id file) output nil))

    (with-open [zip (ZipFile. (fs/file output))]
      (t/is (= ZipEntry/DEFLATED
               (.getMethod (.getEntry zip (str "objects/" media-id ".png"))))
            "a binary over the threshold falls back to the streaming path"))))


(t/deftest the-chunk-budget-stays-a-quarter-of-the-entry-cap
  (t/is (< pc/default-chunk-size bfc/default-max-text-entry-size))
  (t/is (>= (quot bfc/default-max-text-entry-size pc/default-chunk-size) 4)))

(t/deftest export-binfile-v3-compact-splits-a-page-in-chunks
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 3)
        output  (tmp/tempfile :suffix ".zip")
        shapes  (-> (bfc/get-file th/*system* file-id {:realize? true})
                    :data :pages-index (get page-id) :objects
                    count)]

    ;; A budget smaller than any shape puts one shape per chunk
    (export-compact! file-id output 1)

    (let [names (zip-entry-names output)
          page  (with-open [zip (ZipFile. (fs/file output))]
                  (read-zip-json zip (str "files/" file-id "/pages/" page-id ".json")))]

      (t/testing "the page entry names its chunks and carries no shapes"
        (t/is (= shapes (:chunks page)))
        (t/is (not (contains? page :objects))))

      (t/testing "one entry per chunk, each one with a single shape"
        (doseq [index (range shapes)]
          (let [path (pc/chunk-path file-id page-id index)]
            (t/is (contains? names path))
            (with-open [zip (ZipFile. (fs/file output))]
              (t/is (= 1 (count (read-zip-lines zip path)))))))
        (t/is (not (contains? names (pc/chunk-path file-id page-id shapes)))
              "no chunk past the count of the page")))))

(t/deftest export-binfile-v3-compact-round-trip-chunks
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id rect-ids]} (add-page-with-rects profile 3)
        output  (tmp/tempfile :suffix ".zip")
        source  (-> (bfc/get-file th/*system* file-id {:realize? true})
                    :data :pages-index (get page-id) :objects)]

    (export-compact! file-id output 1)

    (let [result   (import-package profile output)
          imported (-> (bfc/get-file th/*system* (first (:file-ids result)) {:realize? true})
                       :data :pages-index (get page-id))]

      (t/testing "every shape comes back from the chunk that carried it"
        (doseq [id rect-ids]
          (doseq [k [:id :x :y :width :height :parent-id :frame-id]]
            (t/is (= (get-in source [id k])
                     (get-in imported [:objects id k]))
                  (str k " of " id " reads back unchanged"))))))))

(t/deftest import-binfile-v3-compact-reads-a-page-written-with-objects
  ;; A version 2 package written before the chunks carries the shapes in the
  ;; entry of the page. The reader still accepts that layout.
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id rect-ids]} (add-page-with-rects profile 2)
        output  (tmp/tempfile :suffix ".zip")
        output2 (tmp/tempfile :suffix ".zip")
        path    (str "files/" file-id "/pages/" page-id ".json")
        shapes  (-> (bfc/get-file th/*system* file-id {:realize? true})
                    :data :pages-index (get page-id) :objects
                    count)]

    (export-compact! file-id output 1)

    (let [page   (with-open [zip (ZipFile. (fs/file output))]
                   (read-zip-json zip path))
          chunks (with-open [zip (ZipFile. (fs/file output))]
                   (mapv #(read-zip-lines zip (pc/chunk-path file-id page-id %))
                         (range (:chunks page))))
          page   (-> page
                     (assoc :objects (into {} cat chunks))
                     (dissoc :chunks))]

      (with-open [in  (ZipInputStream. (io/input-stream output))
                  out (ZipOutputStream. (io/output-stream output2))]
        (loop []
          (when-let [^ZipEntry entry (.getNextEntry in)]
            (let [name (.getName entry)]
              (cond
                (str/includes? name (str "/pages/" page-id "/chunks/"))
                nil

                (= name path)
                (do (.putNextEntry out (ZipEntry. name))
                    (let [writer (io/writer out)]
                      (cjson/write writer page)
                      (.flush writer))
                    (.closeEntry out))

                :else
                (do (.putNextEntry out (ZipEntry. name))
                    (.transferTo ^java.io.InputStream in out)
                    (.closeEntry out))))
            (recur)))))

    (let [result   (import-package profile output2)
          imported (-> (bfc/get-file th/*system* (first (:file-ids result)) {:realize? true})
                       :data :pages-index (get page-id))]

      (t/is (= shapes (count (:objects imported)))
            "the page keeps every shape it carried in its objects")
      (doseq [id rect-ids]
        (t/is (= (str id) (str (:id (get-in imported [:objects id])))))))))

(t/deftest import-binfile-v3-compact-rejects-a-missing-chunk
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 3)
        output  (tmp/tempfile :suffix ".zip")
        output2 (tmp/tempfile :suffix ".zip")
        missing (pc/chunk-path file-id page-id 1)
        cause   (atom nil)]

    (export-compact! file-id output 1)

    ;; Copy the package without the chunk that the page names
    (with-open [in  (ZipInputStream. (io/input-stream output))
                out (ZipOutputStream. (io/output-stream output2))]
      (loop []
        (when-let [^ZipEntry entry (.getNextEntry in)]
          (when-not (= missing (.getName entry))
            (.putNextEntry out (ZipEntry. (.getName entry)))
            (.transferTo ^java.io.InputStream in out)
            (.closeEntry out))
          (recur))))

    (try
      (import-package profile output2)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "the import refuses a page without its chunks")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
    (t/is (= (str "files/" file-id "/pages/" page-id ".json")
             (:path (ex-data @cause)))
          "and names the page whose count does not match")))

(t/deftest import-binfile-v3-compact-rejects-an-invalid-chunk-count
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 2)
        output  (tmp/tempfile :suffix ".zip")
        output2 (tmp/tempfile :suffix ".zip")
        path    (str "files/" file-id "/pages/" page-id ".json")
        cause   (atom nil)]

    (export-compact! file-id output 1)

    ;; Rewrite the page entry with a count no package can carry
    (with-open [in  (ZipInputStream. (io/input-stream output))
                out (ZipOutputStream. (io/output-stream output2))]
      (loop []
        (when-let [^ZipEntry entry (.getNextEntry in)]
          (.putNextEntry out (ZipEntry. (.getName entry)))
          (if (= path (.getName entry))
            (let [page   (cjson/read (io/reader in) :key-fn cjson/read-kebab-key)
                  writer (io/writer out)]
              (cjson/write writer (assoc page :chunks -1))
              (.flush writer))
            (.transferTo ^java.io.InputStream in out))
          (.closeEntry out)
          (recur))))

    (try
      (import-package profile output2)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "the import refuses an absurd chunk count")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
    (t/is (= -1 (:chunks (ex-data @cause))))))

(defn- rewrite-json-entry
  "Copies `input` to `output` replacing the json entry at `path` with the
  result of applying `f` to the decoded entry."
  [input output path f]
  (with-open [in  (ZipInputStream. (io/input-stream input))
              out (ZipOutputStream. (io/output-stream output))]
    (loop []
      (when-let [^ZipEntry entry (.getNextEntry in)]
        (let [name (.getName entry)]
          (.putNextEntry out (ZipEntry. name))
          (if (= name path)
            (let [writer (io/writer out)]
              (cjson/write writer (f (cjson/read (io/reader in) :key-fn cjson/read-kebab-key)))
              (.flush writer))
            (.transferTo ^java.io.InputStream in out))
          (.closeEntry out))
        (recur)))))

(defn- rewrite-zip
  "Copies `input` to `output` dropping `drop-paths` and adding `entries`, a map
  of path to json data. `add-json-entry` only appends, and rebuilding the
  per-entry layout of a family needs both halves."
  [input output drop-paths entries]
  (with-open [in  (ZipInputStream. (io/input-stream input))
              out (ZipOutputStream. (io/output-stream output))]
    (loop []
      (when-let [^ZipEntry entry (.getNextEntry in)]
        (let [name (.getName entry)]
          (when-not (contains? drop-paths name)
            (.putNextEntry out (ZipEntry. name))
            (.transferTo ^java.io.InputStream in out)
            (.closeEntry out)))
        (recur)))
    (doseq [[path data] entries]
      (.putNextEntry out (ZipEntry. path))
      (let [writer (io/writer out)]
        (cjson/write writer data :key-fn cjson/write-camel-key)
        (.flush writer))
      (.closeEntry out))))

(defn- add-json-entry
  "Copies `input` to `output` and appends a json entry at `path`."
  [input output path data]
  (with-open [in  (ZipInputStream. (io/input-stream input))
              out (ZipOutputStream. (io/output-stream output))]
    (loop []
      (when-let [^ZipEntry entry (.getNextEntry in)]
        (.putNextEntry out (ZipEntry. (.getName entry)))
        (.transferTo ^java.io.InputStream in out)
        (.closeEntry out)
        (recur)))
    (.putNextEntry out (ZipEntry. path))
    (let [writer (io/writer out)]
      (cjson/write writer data)
      (.flush writer))
    (.closeEntry out)))

(defn- zip-entry-bytes
  [path entry-name]
  (with-open [zip (ZipFile. (fs/file path))
              in  (.getInputStream zip (.getEntry zip entry-name))]
    (.readAllBytes in)))

(defn- lines-bytes
  "Serializes `pairs` as JSONL bytes, one `[key value]` per line."
  [pairs]
  (let [sw (java.io.StringWriter.)]
    (doseq [[k v] pairs]
      (cjson/write sw [k v] :key-fn cjson/write-camel-key)
      (.write sw "\n"))
    (.getBytes (str sw) java.nio.charset.StandardCharsets/UTF_8)))

(defn- rewrite-entry
  "Copies `input` to `output`, replacing the entry at `path` with `new-path`
  and `bytes`. Unlike `rewrite-json-entry` the payload is raw, so a chunk can
  change its codec suffix."
  [input output path new-path bytes]
  (with-open [in  (ZipInputStream. (io/input-stream input))
              out (ZipOutputStream. (io/output-stream output))]
    (loop []
      (when-let [^ZipEntry entry (.getNextEntry in)]
        (if (= path (.getName entry))
          (do
            (.putNextEntry out (ZipEntry. new-path))
            (.write out ^bytes bytes 0 (alength ^bytes bytes))
            (.closeEntry out))
          (do
            (.putNextEntry out (ZipEntry. (.getName entry)))
            (.transferTo ^java.io.InputStream in out)
            (.closeEntry out)))
        (recur)))))

(defn- unzstd
  [^bytes bytes]
  (with-open [in (ZstdInputStream. (java.io.ByteArrayInputStream. bytes))]
    (.readAllBytes in)))

(defn- chunk-sizes
  [^ZipFile zip file-id page-id chunks]
  (mapv (fn [index]
          (.getSize ^ZipEntry (.getEntry zip (pc/chunk-path file-id page-id index))))
        (range chunks)))

(t/deftest read-page-chunks-rejects-a-duplicated-chunk
  ;; A zip cannot carry two entries with the same name (`ZipOutputStream`
  ;; refuses them), so the case goes straight to the function: the duplicate
  ;; check runs before any entry is read.
  (let [file-id (uuid/next)
        page-id (uuid/next)
        cfg     {::bfc/input nil
                 ::v3/max-zip-entries 10
                 ::v3/entries-index
                 {:page-chunks
                  {(str file-id) {(str page-id) [{:entry nil :index 0}
                                                 {:entry nil :index 0}]}}}}
        cause   (try
                  (@#'v3/read-page-chunks cfg file-id page-id 1)
                  nil
                  (catch Throwable cause cause))]

    (t/is (some? cause) "a duplicated chunk is refused")
    (t/is (= :inconsistent-penpot-file (:code (ex-data cause))))
    (t/is (= (str "files/" file-id "/pages/" page-id ".json")
             (:path (ex-data cause))))
    (t/is (= 0 (:index (ex-data cause))))))

(t/deftest import-binfile-v3-compact-rejects-a-non-integer-chunk-count
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 2)
        output  (tmp/tempfile :suffix ".zip")
        output2 (tmp/tempfile :suffix ".zip")
        path    (str "files/" file-id "/pages/" page-id ".json")]

    (export-compact! file-id output 1)

    (doseq [chunks ["2" 2.0]]
      (let [cause (atom nil)]
        (rewrite-json-entry output output2 path #(assoc % :chunks chunks))
        (try
          (import-package profile output2)
          (catch Throwable error
            (reset! cause error)))

        (t/is (some? @cause) (str "a chunk count of " (pr-str chunks) " is refused"))
        (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
        (t/is (= chunks (:chunks (ex-data @cause))))))))

(t/deftest import-binfile-v3-compact-rejects-a-chunk-count-above-the-cap
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 2)
        output  (tmp/tempfile :suffix ".zip")
        output2 (tmp/tempfile :suffix ".zip")
        path    (str "files/" file-id "/pages/" page-id ".json")
        chunks  (inc bfc/default-max-zip-entries)
        cause   (atom nil)]

    (export-compact! file-id output 1)
    (rewrite-json-entry output output2 path #(assoc % :chunks chunks))

    (try
      (import-package profile output2)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "a chunk count above the zip entry cap is refused")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
    (t/is (= chunks (:chunks (ex-data @cause))))))

(t/deftest import-binfile-v3-compact-rejects-a-chunk-that-is-not-a-map-of-shapes
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 2)
        output  (tmp/tempfile :suffix ".zip")
        output2 (tmp/tempfile :suffix ".zip")
        path    (pc/chunk-path file-id page-id 0)]

    (export-compact! file-id output 1)

    (doseq [replacement [{} nil []]]
      (let [cause (atom nil)]
        (rewrite-json-entry output output2 path (constantly replacement))
        (try
          (import-package profile output2)
          (catch Throwable error
            (reset! cause error)))

        (t/is (some? @cause)
              (str "a chunk with " (pr-str replacement) " is refused"))
        (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
        (t/is (= path (:path (ex-data @cause))))))))

(t/deftest import-binfile-v3-compact-ignores-a-non-numeric-chunk
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id rect-ids]} (add-page-with-rects profile 2)
        output  (tmp/tempfile :suffix ".zip")
        output2 (tmp/tempfile :suffix ".zip")
        stray   (str "files/" file-id "/pages/" page-id "/chunks/abc.json")]

    (export-compact! file-id output 1)
    (add-json-entry output output2 stray {:objects {}})

    (let [result   (import-package profile output2)
          imported (-> (bfc/get-file th/*system* (first (:file-ids result)) {:realize? true})
                       :data :pages-index (get page-id))]
      (t/is (every? #(contains? (:objects imported) %) rect-ids)
            "the stray entry is ignored and the page keeps its shapes"))))

(t/deftest import-binfile-v3-compact-reads-a-page-with-no-chunks
  ;; A page that names no chunks carries no shapes either; the reader takes it
  ;; as an empty page instead of failing.
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 2)
        output  (tmp/tempfile :suffix ".zip")
        output2 (tmp/tempfile :suffix ".zip")
        output3 (tmp/tempfile :suffix ".zip")
        path    (str "files/" file-id "/pages/" page-id ".json")]

    (export-compact! file-id output 1)

    ;; Forge a page that declares no chunks: rewrite the count and drop the
    ;; chunk entries, so the package is coherent.
    (let [n (with-open [zip (ZipFile. (fs/file output))]
              (:chunks (read-zip-json zip path)))]
      (rewrite-json-entry output output2 path #(assoc % :chunks 0))
      (rewrite-zip output2 output3
                   (into #{} (map #(pc/chunk-path file-id page-id %)) (range n))
                   {}))

    (let [result   (import-package profile output3)
          imported (-> (bfc/get-file th/*system* (first (:file-ids result)) {:realize? true})
                       :data :pages-index (get page-id))]
      (t/is (= {} (:objects imported))
            "a page that names no chunks reads as an empty page"))))

(t/deftest export-binfile-v3-compact-bounds-every-chunk
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 3)
        page-path (str "files/" file-id "/pages/" page-id ".json")
        single    (tmp/tempfile :suffix ".zip")]

    ;; One entry per chunk, to know what each entry weighs on its own
    (export-compact! file-id single 1)

    (let [entries (with-open [zip (ZipFile. (fs/file single))]
                    (chunk-sizes zip file-id page-id
                                 (:chunks (read-zip-json zip page-path))))
          biggest (apply max entries)
          ;; Room for any two entries, never for three
          budget  (* 2 biggest)
          output  (tmp/tempfile :suffix ".zip")]

      (export-compact! file-id output budget)

      (with-open [zip (ZipFile. (fs/file output))]
        (let [page  (read-zip-json zip page-path)
              sizes (chunk-sizes zip file-id page-id (:chunks page))]

          (t/is (< (:chunks page) (count entries))
                "some chunk carries more than one shape")
          (t/is (every? #(<= % budget) sizes)
                "every chunk stays within the budget when no shape alone passes it")
          (t/is (every? pos? sizes) "no chunk is empty"))))))

(t/deftest export-binfile-takes-an-optional-variant
  (let [schema @#'binfile/schema:export-binfile
        job-schema @#'export-job/schema:params]
    (t/is (sm/validate schema {:file-id (uuid/next) :variant :verbose})
          "the synchronous command accepts the verbose layout")
    (t/is (sm/validate schema {:file-id (uuid/next) :variant :compact})
          "the synchronous command accepts the compact layout")
    (t/is (sm/validate schema {:file-id (uuid/next) :variant :compact-zstd})
          "the synchronous command accepts the zstd compact layout")
    (t/is (sm/validate job-schema {:file-ids #{}
                                   :export-type :detach-libraries
                                   :variant :compact-zstd})
          "the export job accepts the zstd compact layout")
    (t/is (sm/validate schema {:file-id (uuid/next)})
          "and keeps working without it")
    (t/is (not (sm/validate schema {:file-id (uuid/next) :variant :normal}))
          "the old normal name is not accepted")
    (t/is (not (sm/validate schema {:file-id (uuid/next) :variant :bogus}))
          "an unknown variant is refused before the export runs"))

  (t/is (= :compact (::bfc/variant (bfc/export-cfg th/*system* {:variant :compact}))))
  (t/is (= :compact-zstd (::bfc/variant (bfc/export-cfg th/*system* {:variant :compact-zstd}))))
  (t/is (= :verbose (::bfc/variant (bfc/export-cfg th/*system* {})))
        "the default is the verbose layout"))

;; --- COMPRESSED CHUNKS

(defn- rewrite-entry-bytes
  "Copies `input` to `output` replacing the entry at `path` with raw bytes."
  [input output path bytes]
  (with-open [in  (ZipInputStream. (io/input-stream input))
              out (ZipOutputStream. (io/output-stream output))]
    (loop []
      (when-let [^ZipEntry entry (.getNextEntry in)]
        (.putNextEntry out (ZipEntry. (.getName entry)))
        (if (= path (.getName entry))
          (.write out ^bytes bytes 0 (alength ^bytes bytes))
          (.transferTo ^java.io.InputStream in out))
        (.closeEntry out)
        (recur)))))

(t/deftest the-compressed-chunk-budget-leaves-room-under-the-entry-cap
  (t/is (< pc/compressed-chunk-size bfc/default-max-text-entry-size))
  (t/is (>= (- bfc/default-max-text-entry-size pc/compressed-chunk-size)
            (* 2 1024 1024))
        "the budget leaves 2 MiB of margin under the per-entry cap"))

(t/deftest export-binfile-v3-compact-zstd-writes-stored-zstd-chunks
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id rect-ids]} (add-page-with-rects profile 3)
        output    (tmp/tempfile :suffix ".zip")
        page-path (str "files/" file-id "/pages/" page-id ".json")]

    (export-compact-zstd! file-id output 1)

    (with-open [zip (ZipFile. (fs/file output))]
      (let [manifest (read-zip-json zip "manifest.json")
            page     (read-zip-json zip page-path)]

        (t/is (= 3 (:version manifest))
              "the compressed layout marks the manifest with version 3")
        (t/is (> (:chunks page) 1)
              "a budget of one byte puts one shape per chunk")
        (t/is (not (contains? page :objects)))
        (t/is (some? (.getEntry zip (pc/compressed-chunk-path file-id page-id 0))))
        (t/is (nil? (.getEntry zip (pc/chunk-path file-id page-id 0)))
              "the compressed chunk does not carry the plain json name")

        (doseq [index (range (:chunks page))]
          (let [^ZipEntry entry (.getEntry zip (pc/compressed-chunk-path file-id page-id index))]
            (t/is (= ZipEntry/STORED (.getMethod entry))
                  "the chunk is stored, not deflated")
            (with-open [in (.getInputStream zip entry)]
              (let [magic (byte-array 4)]
                (.read in magic)
                (t/is (= [0x28 0xB5 0x2F 0xFD]
                         (mapv #(bit-and (long %) 0xff) magic))
                      "the payload starts with the zstd magic")))))

        (let [chunks  (mapv #(read-zstd-zip-lines zip (pc/compressed-chunk-path file-id page-id %))
                            (range (:chunks page)))
              objects (into {} cat chunks)]
          (t/is (every? #(contains? objects (keyword (str %))) rect-ids)
                "every shape is inside a decompressed chunk"))))))

(t/deftest export-binfile-v3-compact-zstd-round-trip
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id rect-ids]} (add-page-with-rects profile 3)
        output  (tmp/tempfile :suffix ".zip")
        source  (-> (bfc/get-file th/*system* file-id {:realize? true})
                    :data :pages-index (get page-id) :objects)]

    (export-compact-zstd! file-id output 1)

    (let [result   (import-package profile output)
          imported (-> (bfc/get-file th/*system* (first (:file-ids result)) {:realize? true})
                       :data :pages-index (get page-id))]

      (doseq [id rect-ids
              k  [:id :x :y :width :height :parent-id :frame-id]]
        (t/is (= (get-in source [id k])
                 (get-in imported [:objects id k]))
              (str k " of " id " reads back unchanged"))))))

(t/deftest import-binfile-v3-compact-zstd-rejects-a-corrupt-chunk
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 2)
        output  (tmp/tempfile :suffix ".zip")
        output2 (tmp/tempfile :suffix ".zip")
        path    (pc/compressed-chunk-path file-id page-id 0)
        cause   (atom nil)]

    (export-compact-zstd! file-id output 1)
    (rewrite-entry-bytes output output2 path
                         (.getBytes "not a zstd frame" "UTF-8"))

    (try
      (import-package profile output2)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "the import refuses a chunk it cannot decompress")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
    (t/is (= path (:path (ex-data @cause))))))

(t/deftest import-binfile-v3-compact-zstd-caps-the-decompressed-chunk
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 20)
        output  (tmp/tempfile :suffix ".zip")
        path    (pc/compressed-chunk-path file-id page-id 0)]

    ;; One chunk with every shape: the json is much larger than its frame.
    (export-compact-zstd! file-id output (* 1024 1024))

    (with-open [zip (ZipFile. (fs/file output))]
      (let [^ZipEntry entry (.getEntry zip path)
            declared        (.getSize entry)
            cfg             {::v3/max-text-entry-size (inc declared)
                             ::v3/max-text-total-size (* 64 1024 1024)
                             ::v3/accumulated-total-text-size (atom 0)}
            cause           (try
                              (with-open [reader (@#'v3/zip-entry-reader cfg zip entry)]
                                (slurp reader))
                              nil
                              (catch Throwable cause cause))]
        (t/is (some? cause)
              "a chunk that decompresses past the cap is refused")
        (t/is (= :max-file-size-reached (:code (ex-data cause))))))))

(t/deftest import-binfile-v3-rejects-an-unknown-manifest-version
  (let [profile (th/create-profile* 1)
        {:keys [file-id]} (add-page-with-rects profile 1)
        output  (tmp/tempfile :suffix ".zip")
        output2 (tmp/tempfile :suffix ".zip")
        cause   (atom nil)]

    (export-compact! file-id output 1024)
    (rewrite-json-entry output output2 "manifest.json" #(assoc % :version 4))

    (try
      (import-package profile output2)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "an unknown manifest version is refused")
    (t/is (= :invalid-binfile-v3-manifest (:code (ex-data @cause))))
    (t/is (= 4 (:version (ex-data @cause))))))


;; --- PACKED COLLECTIONS AND CODEC DETECTION

(defn- add-library-content!
  "Adds a color, a typography and two components to `file`, which must be a
  `prepare-simple-file` one: the components point to its page and shapes."
  [profile file]
  (let [color-id      (uuid/custom 5 1)
        typography-id (uuid/custom 5 2)
        component-id  (uuid/custom 5 3)
        other-id      (uuid/custom 5 4)]
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-obj
       :page-id (uuid/custom 1 1)
       :id (uuid/custom 2 2)
       :parent-id uuid/zero
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id (uuid/custom 2 2)
              :name "other"
              :frame-id uuid/zero
              :parent-id uuid/zero
              :type :rect})}
      {:type :add-color
       :color {:id color-id :name "packed-color" :color "#00FF00"}}
      {:type :add-typography
       :typography {:id typography-id
                    :name "packed-typography"
                    :font-id "sourcesanspro"
                    :font-family "Source Sans Pro"
                    :font-variant-id "regular"
                    :font-size "14"
                    :font-weight "400"
                    :font-style "normal"
                    :line-height "1.2"
                    :letter-spacing "0"
                    :text-transform "none"}}
      {:type :add-component
       :id component-id
       :name "packed-component"
       :path ""
       :main-instance-id (uuid/custom 2 1)
       :main-instance-page (uuid/custom 1 1)}
      {:type :add-component
       :id other-id
       :name "other-component"
       :path ""
       :main-instance-id (uuid/custom 2 2)
       :main-instance-page (uuid/custom 1 1)}])
    {:color-id color-id
     :typography-id typography-id
     :component-id component-id
     :other-component-id other-id}))

(t/deftest export-binfile-v3-compact-packs-the-object-families
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        ids     (add-library-content! profile file)
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))]

    (export-compact! (:id file) output nil)

    (let [names (zip-entry-names output)]
      (t/is (contains? names (str "files/" f "/components.json"))
            "the components are one packed entry")
      (t/is (contains? names (str "files/" f "/colors.json"))
            "the colors are one packed entry")
      (t/is (contains? names (str "files/" f "/typographies.json"))
            "the typographies are one packed entry")
      (t/is (contains? names (str "files/" f "/components/chunks/0.json"))
            "the family travels in chunks")
      (t/is (contains? names (str "files/" f "/colors/chunks/0.json")))
      (t/is (contains? names (str "files/" f "/typographies/chunks/0.json")))
      (doseq [category ["components" "colors" "typographies"]]
        (t/is (not-any? #(and (str/starts-with? % (str "files/" f "/" category "/"))
                              (not (str/starts-with? % (str "files/" f "/" category "/chunks/"))))
                        names)
              (str "the compact layout writes no per-" category " entry"))))

    (let [result   (import-package profile output)
          imported (bfc/get-file th/*system* (first (:file-ids result)) {:realize? true})]
      (t/is (contains? (get-in imported [:data :components]) (:component-id ids)))
      (t/is (contains? (get-in imported [:data :components]) (:other-component-id ids)))
      (t/is (contains? (get-in imported [:data :colors]) (:color-id ids)))
      (t/is (contains? (get-in imported [:data :typographies]) (:typography-id ids))))))

(t/deftest export-binfile-v3-compact-zstd-writes-the-families-as-zstd-entries
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        ids     (add-library-content! profile file)
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))]

    (export-compact-zstd! (:id file) output nil)

    (with-open [zip (ZipFile. (fs/file output))]
      (let [components-header (read-zip-json zip (str "files/" f "/components.json"))
            colors-header     (read-zip-json zip (str "files/" f "/colors.json"))
            components        (read-zstd-zip-lines zip (str "files/" f "/components/chunks/0.json.zstd"))
            colors            (read-zstd-zip-lines zip (str "files/" f "/colors/chunks/0.json.zstd"))]
        (t/is (= 1 (:chunks components-header)))
        (t/is (= 1 (:chunks colors-header)))
        (t/is (contains? (into {} components) (keyword (str (:component-id ids)))))
        (t/is (contains? (into {} colors) (keyword (str (:color-id ids)))))
        (t/is (nil? (.getEntry zip (str "files/" f "/components.json.zstd")))
              "the header stays plain json")))

    (let [result   (import-package profile output)
          imported (bfc/get-file th/*system* (first (:file-ids result)) {:realize? true})]
      (t/is (contains? (get-in imported [:data :components]) (:component-id ids)))
      (t/is (contains? (get-in imported [:data :colors]) (:color-id ids)))
      (t/is (contains? (get-in imported [:data :typographies]) (:typography-id ids))))))

(t/deftest export-binfile-v3-compact-chunks-a-family-that-does-not-fit
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        ids     (add-library-content! profile file)
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))]

    ;; A one-byte budget puts one component per chunk.
    (export-compact! (:id file) output 1)

    (with-open [zip (ZipFile. (fs/file output))]
      (let [header (read-zip-json zip (str "files/" f "/components.json"))]
        (t/is (= 2 (:chunks header))
              "the header names the number of chunks")
        (t/is (some? (.getEntry zip (str "files/" f "/components/chunks/0.json"))))
        (t/is (some? (.getEntry zip (str "files/" f "/components/chunks/1.json"))))))

    (let [result   (import-package profile output)
          imported (bfc/get-file th/*system* (first (:file-ids result)) {:realize? true})]
      (t/is (contains? (get-in imported [:data :components]) (:component-id ids)))
      (t/is (contains? (get-in imported [:data :components]) (:other-component-id ids))))))

(t/deftest export-binfile-v3-compact-packs-the-objects-and-media
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-media profile)
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))]

    (export-compact! (:id file) output nil)

    (let [names (zip-entry-names output)]
      (t/is (contains? names "objects.json")
            "the storage object metadata is one packed entry")
      (t/is (contains? names "objects/chunks/0.json"))
      (t/is (contains? names (str "files/" f "/media.json"))
            "the media descriptors are one packed entry")
      (t/is (contains? names (str "files/" f "/media/chunks/0.json")))
      (t/is (not-any? #(re-matches #"objects/[0-9a-f-]+\.json" %) names)
            "no per-object metadata entry is written")
      (t/is (not-any? #(and (str/starts-with? % (str "files/" f "/media/"))
                            (not (str/starts-with? % (str "files/" f "/media/chunks/"))))
                      names)
            "no per-media descriptor entry is written"))

    (let [result (import-package profile output)
          mobjs  (db/query th/*system* :file-media-object
                           {:file-id (first (:file-ids result))})]
      (t/is (= 1 (count mobjs)))
      (t/is (every? some? (map :media-id mobjs))))))

(t/deftest import-binfile-v3-reads-a-version-3-package-with-plain-chunks
  ;; The codec comes from the entry name, not from the manifest: a version 3
  ;; package whose chunks are plain json still imports.
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id rect-ids]} (add-page-with-rects profile 3)
        output  (tmp/tempfile :suffix ".zip")
        output2 (tmp/tempfile :suffix ".zip")]

    (export-compact! file-id output 1)
    (rewrite-json-entry output output2 "manifest.json" #(assoc % :version 3))

    (let [result   (import-package profile output2)
          imported (-> (bfc/get-file th/*system* (first (:file-ids result)) {:realize? true})
                       :data :pages-index (get page-id))]
      (t/is (every? #(contains? (:objects imported) %) rect-ids)
            "every shape comes back from a plain chunk of a version 3 package"))))

(t/deftest import-binfile-v3-reads-a-page-with-mixed-chunk-codecs
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id rect-ids]} (add-page-with-rects profile 3)
        output    (tmp/tempfile :suffix ".zip")
        output2   (tmp/tempfile :suffix ".zip")
        zstd-path (pc/compressed-chunk-path file-id page-id 0)
        json-path (pc/chunk-path file-id page-id 0)]

    ;; One shape per chunk, so chunk 0 is a whole frame of its own.
    (export-compact-zstd! file-id output 1)
    (rewrite-entry output output2 zstd-path json-path
                   (unzstd (zip-entry-bytes output zstd-path)))

    (let [result   (import-package profile output2)
          imported (-> (bfc/get-file th/*system* (first (:file-ids result)) {:realize? true})
                       :data :pages-index (get page-id))]
      (t/is (every? #(contains? (:objects imported) %) rect-ids)
            "a plain chunk and a zstd chunk of the same page both read"))))

(t/deftest import-binfile-v3-refuses-a-family-packed-and-per-entry
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        ids     (add-library-content! profile file)
        compact (tmp/tempfile :suffix ".zip")
        verbose (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        path    (str "files/" f "/components/" (:component-id ids) ".json")
        cause   (atom nil)]

    (export-compact! (:id file) compact nil)
    (v3/export-files!
     (bfc/export-cfg th/*system*
                     {:file-ids    #{(:id file)}
                      :export-type :detach-libraries
                      :output      (io/output-stream verbose)}))

    (let [data (with-open [zip (ZipFile. (fs/file verbose))]
                 (read-zip-json zip path))]
      (add-json-entry compact output path data))

    (try
      (import-package profile output)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "the two layouts of the same family are refused")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))))

;; --- PACKED FAMILIES: GUARDS, COVERAGE AND DOCUMENTED LIMITS

(defn- key-text
  "The text of a packed collection key. The json reader answers a keyword when
  the key has no slash and the string itself otherwise."
  [k]
  (if (keyword? k) (name k) (str k)))


(defn- prepare-file-with-all-families
  "A file with two objects of every packed family: two components, two colors
  and two typographies (the second of each added here), two media descriptors
  backed by real storage objects, and two thumbnail rows pointing at them, so
  a one-byte chunk budget leaves every family in chunks."
  [profile]
  (let [file    (prepare-simple-file profile)
        _       (add-library-content! profile file)
        storage (-> (:app.storage/storage th/*system*)
                    (stt/configure-storage-backend))
        media!  (fn []
                  (let [sobject (sto/put-object! storage
                                                 {::sto/content (sto/content "<svg xmlns=\"http://www.w3.org/2000/svg\"/>")
                                                  :content-type "image/svg+xml"
                                                  :bucket "file-media-object"})
                        mobj    (th/create-file-media-object* {:file-id (:id file)
                                                               :is-local true
                                                               :media-id (:id sobject)})]
                    (update-file!
                     :file-id (:id file)
                     :profile-id (:id profile)
                     :revn 0
                     :vern 0
                     :changes [{:type :add-media :object mobj}])
                    (:media-id mobj)))
        media-ids [(media!) (media!)]
        tag       "medium"
        pages     [(uuid/custom 1 1) (uuid/custom 1 2)]
        frames    [(uuid/custom 6 1) (uuid/custom 6 2)]]

    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-color
       :color {:id (uuid/custom 5 5) :name "second-color" :color "#0000FF"}}
      {:type :add-typography
       :typography {:id (uuid/custom 5 6)
                    :name "second-typography"
                    :font-id "sourcesanspro"
                    :font-family "Source Sans Pro"
                    :font-variant-id "regular"
                    :font-size "16"
                    :font-weight "400"
                    :font-style "normal"
                    :line-height "1.4"
                    :letter-spacing "0"
                    :text-transform "none"}}])

    (doseq [[page-id frame-id media-id] (map vector pages frames media-ids)]
      (db/insert! th/*system* :file-tagged-object-thumbnail
                  {:file-id (:id file)
                   :tag tag
                   :object-id (thc/fmt-object-id {:file-id (:id file)
                                                  :page-id page-id
                                                  :frame-id frame-id
                                                  :tag tag})
                   :media-id media-id}))

    file))

(t/deftest export-binfile-v3-compact-taps-the-storage-binaries
  ;; The binaries are the units of the stage. The packed metadata reported
  ;; every chunk as a new unit without a total, which restarted the counter of
  ;; the job at the end of the export; it writes no tap now.
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        output  (tmp/tempfile :suffix ".zip")
        taps    (atom [])]

    (binding [events/*sink* (fn [[_type data]] (swap! taps conj data))]
      (export-compact! (:id file) output nil))

    (let [storage-taps (filterv #(= :storage-object (:section %)) @taps)]
      (t/is (= 2 (count storage-taps)) "one tap per binary and none for the metadata")
      (t/is (= [1 2] (mapv :current storage-taps)) "the counter only grows")
      (t/is (= [2 2] (mapv :total storage-taps)) "and keeps its denominator"))))

(t/deftest export-binfile-skips-a-thumbnail-row-with-an-unparseable-object-id
  ;; A file can carry a thumbnail row written before the creation command
  ;; validated the object id. Its object id does not split into the four
  ;; segments the exporter needs, and the entry it used to produce looked like
  ;; the packed family on import, which made the whole package unreadable,
  ;; version 1 included. The export drops the row and the derived cache, and
  ;; the package reads back.
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        f       (str (:id file))
        storage (-> (:app.storage/storage th/*system*)
                    (stt/configure-storage-backend))
        sobject (sto/put-object! storage
                                 {::sto/content (sto/content "<svg xmlns=\"http://www.w3.org/2000/svg\"/>")
                                  :content-type "image/svg+xml"
                                  :bucket "file-media-object"})]

    ;; A media nobody else references: a package that carried the broken row
    ;; would also carry an orphan binary for it.
    (db/insert! th/*system* :file-tagged-object-thumbnail
                {:file-id (:id file)
                 :tag "frame"
                 :object-id "test-key-1"
                 :media-id (:id sobject)})

    (doseq [variant [:verbose :compact]]
      (let [output (tmp/tempfile :suffix ".zip")]
        (v3/export-files!
         (bfc/export-cfg th/*system*
                         {:file-ids    #{(:id file)}
                          :export-type :detach-libraries
                          :variant     variant
                          :output      (io/output-stream output)}))
        (let [names (zip-entry-names output)]
          (t/is (not (contains? names (str "files/" f "/thumbnails///.json")))
                (str variant ": the row produces no entry"))
          (t/is (not-any? #(str/starts-with? % (str "objects/" (:id sobject))) names)
                (str variant ": and no orphan binary")))

        (let [result   (import-package profile output)
              imported (first (:file-ids result))
              thumbs   (db/query th/*system* :file-tagged-object-thumbnail
                                 {:file-id imported})]
          (t/is (some? imported) (str variant ": the package imports"))
          (t/is (= 2 (count thumbs))
                (str variant ": the two valid rows read back and the broken one is gone")))))))

(t/deftest import-binfile-v3-validates-a-component-when-it-reads-it
  ;; Every family validates on read, and components were the only one the
  ;; reader trusted until the end of the import, where `check-file` repeats
  ;; the work with a path relative to the whole file. The failure points at
  ;; the component itself (`[:name]`), which is what the family check sees.
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        cause   (atom nil)]

    (v3/export-files!
     (bfc/export-cfg th/*system*
                     {:file-ids    #{(:id file)}
                      :export-type :detach-libraries
                      :output      (io/output-stream packed)}))

    (let [path (->> (zip-entry-names packed)
                    (filter #(str/starts-with? % (str "files/" f "/components/")))
                    sort
                    first)]
      (t/is (some? path) "the verbose export writes one entry per component")

      (rewrite-json-entry packed output path #(assoc % :name 42))

      (try
        (import-package profile output)
        (catch Throwable error
          (reset! cause error)))

      (t/is (some? @cause) "a component that does not satisfy its schema is refused")
      (t/is (= :data-validation (:code (ex-data @cause))))
      (t/is (some #(= [:name] (:in %))
                  (-> @cause ex-data ::sm/explain :errors))
            "the failure names the component, not the whole file"))))

(t/deftest import-binfile-v3-refuses-a-packed-object-with-a-foreign-id
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        path    (str "files/" f "/components/chunks/0.json")
        cause   (atom nil)]

    (export-compact! (:id file) packed nil)

    (let [pairs (with-open [zip (ZipFile. (fs/file packed))]
                  (read-zip-lines zip path))]
      (t/is (seq pairs) "the family travels in chunks")
      (rewrite-entry-bytes
       packed output path
       (lines-bytes (map (fn [[k value]]
                           [(key-text k) (assoc value :id (str (uuid/next)))])
                         pairs))))

    (try
      (import-package profile output)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "a component whose id contradicts its key is refused")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
    (t/is (some? (:key (ex-data @cause))))
    (t/is (not= (:key (ex-data @cause)) (:id (ex-data @cause))))))

(t/deftest import-binfile-v3-refuses-a-packed-thumbnail-with-a-bad-key
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        path    (str "files/" f "/thumbnails/chunks/0.json")
        segs    (fn [k] (str/split k "/"))]

    (export-compact! (:id file) packed nil)

    (doseq [[label bad-key]
            [["a key without the frame"
              (fn [k] (let [[tag page _] (segs k)] (str tag "/" page)))]
             ["a key pointing at another frame"
              (fn [k] (let [[tag page _] (segs k)] (str tag "/" page "/" (uuid/next))))]]]
      (let [output (tmp/tempfile :suffix ".zip")
            cause  (atom nil)
            pairs  (with-open [zip (ZipFile. (fs/file packed))]
                     (read-zip-lines zip path))]
        (t/is (seq pairs) "the thumbnails travel in chunks")
        (rewrite-entry-bytes
         packed output path
         (lines-bytes (map (fn [[k value]] [(bad-key k) value]) pairs)))
        (try
          (import-package profile output)
          (catch Throwable error
            (reset! cause error)))

        (t/is (some? @cause) label)
        (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))) label)
        (t/is (contains? (ex-data @cause) :key) label)))))

(t/deftest import-binfile-v3-ignores-a-family-chunk-without-its-entry
  ;; The entry of the family is what declares it. Its chunks on their own are
  ;; as meaningless as any other path the importer does not manage, so they are
  ;; ignored instead of failing the import.
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))]

    (export-compact! (:id file) packed 1)

    (t/is (contains? (zip-entry-names packed)
                     (str "files/" f "/components/chunks/0.json")))

    (rewrite-zip packed output #{(str "files/" f "/components.json")} {})

    (let [result   (import-package profile output)
          imported (bfc/get-file th/*system* (first (:file-ids result)) {:realize? true})]
      (t/is (empty? (get-in imported [:data :components]))
            "the family is absent, and its chunks do not become objects"))))

(t/deftest import-binfile-v3-refuses-a-page-with-chunks-and-objects
  ;; The two representations of a page can carry different shapes, and the
  ;; reader can only keep one: refusing is the only way not to import fewer
  ;; objects than the package has.
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 2)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        path    (str "files/" file-id "/pages/" page-id ".json")
        cause   (atom nil)]

    (export-compact! file-id packed 1)

    (rewrite-json-entry packed output path
                        #(assoc % :objects {(keyword (str (uuid/next)))
                                            {:type "rect"}}))

    (try
      (import-package profile output)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "the import refuses a page with two representations")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
    (t/is (= path (:path (ex-data @cause))) "and names the page")))

(t/deftest import-binfile-v3-refuses-page-chunks-without-a-count
  ;; The page entry is what declares its chunks. Chunk entries that its page
  ;; does not count would be ignored, so the package would import fewer shapes
  ;; than it carries; the reader refuses it instead.
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 2)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        path    (str "files/" file-id "/pages/" page-id ".json")
        cause   (atom nil)]

    (export-compact! file-id packed 1)

    (t/is (< 1 (:chunks (with-open [zip (ZipFile. (fs/file packed))]
                          (read-zip-json zip path))))
          "the page travels in several chunks")

    (rewrite-json-entry packed output path #(dissoc % :chunks))

    (try
      (import-package profile output)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "the import refuses chunks the page does not count")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
    (t/is (= path (:path (ex-data @cause))) "and names the page")
    (t/is (< 1 (:chunks (ex-data @cause))) "and how many it would drop")))

(t/deftest import-binfile-v3-refuses-page-chunks-in-a-verbose-package
  ;; A package in the verbose layout declares no page chunks at all, so any
  ;; chunk entry is data the reader would not visit.
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 2)
        output  (tmp/tempfile :suffix ".zip")
        output2 (tmp/tempfile :suffix ".zip")
        cause   (atom nil)]

    (v3/export-files!
     (bfc/export-cfg th/*system*
                     {:file-ids    #{file-id}
                      :export-type :detach-libraries
                      :output      (io/output-stream output)}))

    (add-json-entry output output2 (pc/chunk-path file-id page-id 0)
                    {(keyword (str (uuid/next))) {:type "rect"}})

    (try
      (import-package profile output2)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "the import refuses a page chunk of a verbose package")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
    (t/is (= (str "files/" file-id "/pages/" page-id ".json")
             (:path (ex-data @cause)))
          "and names the page")))

(t/deftest import-binfile-v3-refuses-a-family-header-with-members
  ;; A chunked family keeps its objects in the chunks; a member next to the
  ;; count is data the reader cannot keep, so the package is refused instead
  ;; of importing the chunks and dropping it.
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        path    (str "files/" f "/components.json")
        cause   (atom nil)]

    (export-compact! (:id file) packed nil)

    (with-open [zip (ZipFile. (fs/file packed))]
      (t/is (= 1 (:chunks (read-zip-json zip path)))
            "the family travels as a header plus chunks"))

    (rewrite-json-entry packed output path
                        #(assoc % :components {:inline {:id (str (uuid/next))}}))

    (try
      (import-package profile output)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "the import refuses a header with members")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
    (t/is (= path (:path (ex-data @cause))) "and names the family")
    (t/is (= [:components] (:keys (ex-data @cause)))
          "and the keys it would have dropped")))

(t/deftest import-binfile-v3-names-the-entry-of-an-unknown-codec
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        path    (str "files/" f "/colors.json")
        renamed (str path ".lz4")
        cause   (atom nil)]

    (export-compact! (:id file) packed nil)
    (rewrite-entry packed output path renamed (zip-entry-bytes packed path))

    (try
      (import-package profile output)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause))
    (t/is (= :unsupported-binfile-codec (:code (ex-data @cause))))
    (t/is (= renamed (:path (ex-data @cause)))
          "the error names the entry, not only the codec")))

(t/deftest index-entries-refuses-a-duplicated-base-path
  (let [f      index-test-file-id
        colors (str "files/" f "/colors.json")
        zstd   (str "files/" f "/colors.json.zstd")]

    (doseq [[label names] [["the same name twice" [colors colors]]
                           ["the same path in two codecs" [colors zstd]]]]
      (let [cause (try
                    (index-entries-of names)
                    nil
                    (catch Throwable cause cause))]
        (t/is (some? cause) label)
        (t/is (= :inconsistent-penpot-file (:code (ex-data cause))) label)
        (t/is (= (vec (sort names)) (:paths (ex-data cause))) label)))))

(t/deftest index-entries-splits-every-name-once
  (let [f      index-test-file-id
        page   index-test-page-id
        names  ["manifest.json"
                (str "files/" f ".json")
                (str "files/" f "/pages/" page ".json")
                (str "files/" f "/pages/" page "/chunks/0.json.zstd")
                (str "files/" f "/colors.json")
                (str "files/" f "/components/chunks/1.json")
                "objects.json"
                "objects/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.png"]
        calls  (atom 0)
        split  @#'v3/split-json-entry-name]

    (with-redefs-fn {#'v3/split-json-entry-name
                     (fn [name] (swap! calls inc) (split name))}
      #(index-entries-of names))

    (t/is (= (count names) @calls)
          "every entry name is split once, duplicates included")))

(t/deftest export-binfile-v3-writes-chunks-as-jsonl
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 2)
        output  (tmp/tempfile :suffix ".zip")
        path    (pc/chunk-path file-id page-id 0)]

    (export-compact! file-id output 1)

    (with-open [zip (ZipFile. (fs/file output))]
      (let [lines (read-zip-lines zip path)]
        (t/is (= 1 (count lines))
              "one line per shape under a one-byte budget")
        (t/is (vector? (first lines)) "a line is a [key value] pair")
        (t/is (= 2 (count (first lines))))
        (t/is (map? (second (first lines))))))

    (t/is (= "[" (subs (String. (zip-entry-bytes output path)
                                java.nio.charset.StandardCharsets/UTF_8)
                       0 1))
          "the chunk starts with a pair, not with a json object")))

(t/deftest export-binfile-v3-chunks-every-packed-family
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        f       (str (:id file))]

    (doseq [[variant export! suffix]
            [[:compact export-compact! ".json"]
             [:compact-zstd export-compact-zstd! ".json.zstd"]]]
      (let [output (tmp/tempfile :suffix ".zip")]
        (export! (:id file) output 1)

        (with-open [zip (ZipFile. (fs/file output))]
          (let [names    (zip-entry-names output)
                families [["components" (str "files/" f "/components.json")
                           (str "files/" f "/components/chunks/")]
                          ["colors" (str "files/" f "/colors.json")
                           (str "files/" f "/colors/chunks/")]
                          ["typographies" (str "files/" f "/typographies.json")
                           (str "files/" f "/typographies/chunks/")]
                          ["media" (str "files/" f "/media.json")
                           (str "files/" f "/media/chunks/")]
                          ["thumbnails" (str "files/" f "/thumbnails.json")
                           (str "files/" f "/thumbnails/chunks/")]
                          ["objects" "objects.json" "objects/chunks/"]]]

            (doseq [[family header-path chunk-prefix] families]
              (let [header (read-zip-json zip header-path)
                    chunks (->> names
                                (filter #(str/starts-with? % chunk-prefix))
                                sort
                                vec)]

                (t/is (map? header) (str variant " " family " header"))
                (t/is (= 2 (:chunks header))
                      (str variant " " family " names its chunks"))
                (t/is (= 2 (count chunks))
                      (str variant " " family " writes one chunk per object"))
                (t/is (every? #(str/ends-with? % suffix) chunks)
                      (str variant " " family " names its chunks with the codec"))
                (t/is (nil? (.getEntry zip (str header-path ".zstd")))
                      (str variant " " family " keeps its header plain"))))))

        (let [result   (import-package profile output)
              imported (bfc/get-file th/*system* (first (:file-ids result)) {:realize? true})
              file-id  (first (:file-ids result))]

          (t/is (= 2 (count (get-in imported [:data :components])))
                (str variant " imports every component"))
          (t/is (= 2 (count (get-in imported [:data :colors])))
                (str variant " imports every color"))
          (t/is (= 2 (count (get-in imported [:data :typographies])))
                (str variant " imports every typography"))
          (t/is (= 2 (count (db/query th/*system* :file-media-object
                                      {:file-id file-id})))
                (str variant " imports every media descriptor"))
          (t/is (= 2 (count (db/query th/*system* :file-tagged-object-thumbnail
                                      {:file-id file-id})))
                (str variant " imports every thumbnail")))))))

(t/deftest import-binfile-v3-taps-once-per-packed-family
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        output  (tmp/tempfile :suffix ".zip")
        taps    (atom [])]

    (export-compact! (:id file) output nil)

    (binding [events/*sink* (fn [[_type data]] (swap! taps conj data))]
      (import-package profile output))

    ;; The read tap of a packed family reports the family, so it carries no id;
    ;; the insert loop of the import taps once per object, and those carry one.
    (let [read-taps (remove :id @taps)
          counts    (frequencies (map :section read-taps))]
      (t/is (= 1 (get counts :component))
            (str "one read tap per packed family: " (pr-str counts)))
      (t/is (= 1 (get counts :color)))
      (t/is (= 1 (get counts :typography)))
      (t/is (= 1 (get counts :media)))
      (t/is (= 1 (get counts :thumbnails))))))

(t/deftest import-binfile-v3-reads-a-library-style-version-2-package
  ;; `@penpot/library` writes version 2 with one entry per object, while the
  ;; backend packs the same families; both layouts must import.
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))]

    (export-compact! (:id file) packed nil)

    (with-open [zip (ZipFile. (fs/file packed))]
      (let [manifest (read-zip-json zip "manifest.json")
            families [["components" (str "files/" f "/components")]
                      ["colors" (str "files/" f "/colors")]
                      ["typographies" (str "files/" f "/typographies")]
                      ["media" (str "files/" f "/media")]
                      ["thumbnails" (str "files/" f "/thumbnails")]
                      ["objects" "objects"]]
            lines    (fn [prefix]
                       (read-zip-lines zip (str prefix "/chunks/0.json")))
            entries  (into {}
                           (mapcat (fn [[_ prefix]]
                                     (map (fn [[k value]]
                                            [(if (= prefix "objects")
                                               (str "objects/" (key-text k) ".json")
                                               (str prefix "/" (key-text k) ".json"))
                                             value])
                                          (lines prefix)))
                                   families))
            names    (zip-entry-names packed)
            drop     (into #{}
                           (mapcat (fn [[_ prefix]]
                                     (cons (str prefix ".json")
                                           (filter #(str/starts-with? % (str prefix "/chunks/"))
                                                   names)))
                                   families))]

        (t/is (= 2 (:version manifest))
              "the package keeps the version of the compact layout")
        (rewrite-zip packed output drop entries)))

    (let [result   (import-package profile output)
          file-id  (first (:file-ids result))
          imported (bfc/get-file th/*system* file-id {:realize? true})]
      (t/is (= 2 (count (get-in imported [:data :components])))
            "the per-entry components import")
      (t/is (= 2 (count (get-in imported [:data :colors]))))
      (t/is (= 2 (count (get-in imported [:data :typographies]))))
      (t/is (= 2 (count (db/query th/*system* :file-media-object
                                  {:file-id file-id}))))
      (t/is (= 2 (count (db/query th/*system* :file-tagged-object-thumbnail
                                  {:file-id file-id})))))))

(t/deftest binfile-variants-map-to-a-version-and-a-codec
  (t/is (= 1 (@#'v3/variant->version nil)))
  (t/is (= 1 (@#'v3/variant->version :verbose)))
  (t/is (= 2 (@#'v3/variant->version :compact)))
  (t/is (= 3 (@#'v3/variant->version :compact-zstd)))

  (t/is (nil? (@#'v3/variant->codec nil)))
  (t/is (nil? (@#'v3/variant->codec :verbose)))
  (t/is (nil? (@#'v3/variant->codec :compact)))
  (t/is (= :zstd (@#'v3/variant->codec :compact-zstd)))

  (t/testing "the layout carries codec, compactness and budget together"
    (t/is (= {:codec nil :compact? false :budget pc/default-chunk-size}
             (@#'v3/variant->layout :verbose)))
    (t/is (= {:codec nil :compact? true :budget pc/default-chunk-size}
             (@#'v3/variant->layout :compact)))
    (t/is (= {:codec :zstd :compact? true :budget pc/compressed-chunk-size}
             (@#'v3/variant->layout :compact-zstd)))))

(t/deftest export-binfile-refuses-an-unregistered-compact-variant
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        output  (tmp/tempfile :suffix ".zip")
        cause   (atom nil)]

    (try
      (v3/export-files!
       (bfc/export-cfg th/*system*
                       {:file-ids    #{(:id file)}
                        :export-type :detach-libraries
                        :variant     :compact-lz4
                        :output      (io/output-stream output)}))
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause))
    (t/is (= :unsupported-binfile-codec (:code (ex-data @cause))))
    (t/is (= :compact-lz4 (:variant (ex-data @cause)))
          "the writer names the variant")))

(t/deftest import-binfile-v3-caps-the-decompressed-family-chunk
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        path    (str "files/" f "/components/chunks/0.json.zstd")]

    (export-compact-zstd! (:id file) output 1)

    (with-open [zip (ZipFile. (fs/file output))]
      (let [^ZipEntry entry (.getEntry zip path)
            declared        (.getSize entry)
            cfg             {::v3/max-text-entry-size (inc declared)
                             ::v3/max-text-total-size (* 64 1024 1024)
                             ::v3/accumulated-total-text-size (atom 0)}
            cause           (try
                              (with-open [reader (@#'v3/zip-entry-reader cfg zip entry)]
                                (slurp reader))
                              nil
                              (catch Throwable cause cause))]

        (t/is (some? entry) "the family writes its chunk with the codec suffix")
        (t/is (some? cause)
              "a family chunk that decompresses past the cap is refused")
        (t/is (= :max-file-size-reached (:code (ex-data cause))))))))

;; --- PACKED COLLECTION READER: ONE TEST PER GUARD

(t/deftest import-binfile-v3-refuses-an-invalid-family-chunk-count
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        path    (str "files/" f "/components.json")]

    ;; A one-byte budget leaves the components in chunks, so the entry names
    ;; a count.
    (export-compact! (:id file) packed 1)

    (doseq [chunks ["2" 2.0 -1 (inc bfc/default-max-zip-entries)]]
      (let [output (tmp/tempfile :suffix ".zip")
            cause  (atom nil)]
        (rewrite-json-entry packed output path #(assoc % :chunks chunks))
        (try
          (import-package profile output)
          (catch Throwable error
            (reset! cause error)))

        (t/is (some? @cause) (str "a chunk count of " (pr-str chunks) " is refused"))
        (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))) (pr-str chunks))
        (t/is (= chunks (:chunks (ex-data @cause))) (pr-str chunks))))))

(t/deftest read-packed-collection-rejects-a-duplicated-chunk
  ;; A zip cannot carry two entries with the same name, and the index refuses
  ;; a duplicated base path before this runs, so the case goes straight to the
  ;; function with the real entries of a package.
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        hpath   (str "files/" f "/components.json")
        cpath   (str "files/" f "/components/chunks/0.json")]

    (export-compact! (:id file) packed 1)

    (with-open [zip (ZipFile. (fs/file packed))]
      (let [cfg   {::bfc/input zip
                   ::v3/max-text-entry-size (* 20 1024 1024)
                   ::v3/max-text-total-size (* 64 1024 1024)
                   ::v3/accumulated-total-text-size (atom 0)
                   ::v3/max-zip-entries bfc/default-max-zip-entries}
            cause (try
                    (@#'v3/read-packed-collection
                     cfg
                     {:entry (.getEntry zip hpath)}
                     [{:entry (.getEntry zip cpath) :index 0}
                      {:entry (.getEntry zip cpath) :index 0}])
                    nil
                    (catch Throwable cause cause))]

        (t/is (some? cause) "a duplicated chunk is refused")
        (t/is (= :inconsistent-penpot-file (:code (ex-data cause))))
        (t/is (= cpath (:path (ex-data cause))))))))

(t/deftest import-binfile-v3-refuses-a-family-header-with-a-missing-chunk
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        path    (str "files/" f "/components.json")
        cause   (atom nil)]

    (export-compact! (:id file) packed 1)

    ;; The package carries two chunks; the header claims three.
    (rewrite-json-entry packed output path #(assoc % :chunks 3))

    (try
      (import-package profile output)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "a header naming a chunk the package lacks is refused")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
    (t/is (= path (:path (ex-data @cause))))))

(t/deftest import-binfile-v3-refuses-a-family-chunk-that-is-not-a-map
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        path    (str "files/" f "/components/chunks/0.json")
        cause   (atom nil)]

    (export-compact! (:id file) packed 1)
    (rewrite-json-entry packed output path (constantly []))

    (try
      (import-package profile output)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "a chunk that is not a map is refused")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
    (t/is (= path (:path (ex-data @cause))))))

(t/deftest import-binfile-v3-refuses-a-family-that-declares-chunks-and-carries-none
  ;; The inline branch must not read a `{:chunks n}` header as a family of
  ;; objects keyed by `chunks`.
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        path    (str "files/" f "/components.json")
        chunk   (str "files/" f "/components/chunks/0.json")
        cause   (atom nil)]

    (export-compact! (:id file) packed nil)
    ;; Drop the only chunk and the header, and write the header back alone.
    (rewrite-zip packed output #{chunk path} {path {:chunks 1}})

    (try
      (import-package profile output)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "a header without its chunks is refused")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
    (t/is (= 1 (:chunks (ex-data @cause))))))

(t/deftest import-binfile-v3-refuses-a-family-entry-that-is-not-a-map
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        path    (str "files/" f "/components.json")
        cause   (atom nil)]

    (export-compact! (:id file) packed nil)
    (rewrite-json-entry packed output path (constantly []))

    (try
      (import-package profile output)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "a packed collection that is not a map is refused")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
    (t/is (= path (:path (ex-data @cause))))))

;; --- PER-ENTRY AND CROSS-CHUNK CONTRADICTIONS

(t/deftest import-binfile-v3-refuses-a-per-entry-object-with-a-foreign-id
  ;; The per-entry layout, which the library writes, must refuse the same
  ;; contradiction the packed one refuses instead of dropping the object.
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        cause   (atom nil)]

    (v3/export-files!
     (bfc/export-cfg th/*system*
                     {:file-ids    #{(:id file)}
                      :export-type :detach-libraries
                      :output      (io/output-stream packed)}))

    (let [path (->> (zip-entry-names packed)
                    (filter #(str/starts-with? % (str "files/" f "/components/")))
                    sort
                    first)]
      (t/is (some? path) "the verbose export writes one entry per component")

      (rewrite-json-entry packed output path #(assoc % :id (str (uuid/next))))

      (try
        (import-package profile output)
        (catch Throwable error
          (reset! cause error)))

      (t/is (some? @cause) "the object is not silently dropped")
      (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
      (t/is (= path (:path (ex-data @cause)))))))

(t/deftest import-binfile-v3-refuses-a-page-with-a-foreign-id
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        page-id (uuid/custom 1 1)
        path    (str "files/" f "/pages/" page-id ".json")
        cause   (atom nil)]

    (v3/export-files!
     (bfc/export-cfg th/*system*
                     {:file-ids    #{(:id file)}
                      :export-type :detach-libraries
                      :output      (io/output-stream packed)}))

    (rewrite-json-entry packed output path #(assoc % :id (str (uuid/next))))

    (try
      (import-package profile output)
      (catch Throwable error
        (reset! cause error)))

    (t/is (some? @cause) "a page whose id contradicts its path is refused")
    (t/is (= :inconsistent-penpot-file (:code (ex-data @cause))))
    (t/is (= path (:path (ex-data @cause))))))

(t/deftest import-binfile-v3-refuses-a-shape-repeated-across-page-chunks
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 3)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        c0      (pc/chunk-path file-id page-id 0)
        c1      (pc/chunk-path file-id page-id 1)]

    (export-compact! file-id packed 1)

    (let [[dup value] (with-open [zip (ZipFile. (fs/file packed))]
                        (first (read-zip-lines zip c0)))]
      (rewrite-entry-bytes packed output c1 (lines-bytes [[(name dup) value]]))

      (let [cause (try
                    (import-package profile output)
                    nil
                    (catch Throwable cause cause))]
        (t/is (some? cause) "a key repeated in two chunks is refused")
        (t/is (= :inconsistent-penpot-file (:code (ex-data cause))))
        (t/is (= (str "files/" file-id "/pages/" page-id ".json")
                 (:path (ex-data cause))))
        (t/is (= (name dup) (:key (ex-data cause))))))))

(t/deftest import-binfile-v3-refuses-an-object-repeated-across-collection-chunks
  (let [profile (th/create-profile* 1)
        file    (prepare-file-with-all-families profile)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        f       (str (:id file))
        c0      (str "files/" f "/components/chunks/0.json")
        c1      (str "files/" f "/components/chunks/1.json")]

    (export-compact! (:id file) packed 1)

    (let [[dup value] (with-open [zip (ZipFile. (fs/file packed))]
                        (first (read-zip-lines zip c0)))]
      (rewrite-entry-bytes packed output c1 (lines-bytes [[(name dup) value]]))

      (let [cause (try
                    (import-package profile output)
                    nil
                    (catch Throwable cause cause))]
        (t/is (some? cause) "a key repeated in two family chunks is refused")
        (t/is (= :inconsistent-penpot-file (:code (ex-data cause))))
        (t/is (= (str "files/" f "/components.json") (:path (ex-data cause))))
        (t/is (= (name dup) (:key (ex-data cause))))))))

(t/deftest import-binfile-v3-refuses-a-key-repeated-inside-a-chunk
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 2)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        c0      (pc/chunk-path file-id page-id 0)]

    (export-compact! file-id packed 1)

    (let [[dup value] (with-open [zip (ZipFile. (fs/file packed))]
                        (first (read-zip-lines zip c0)))]
      (rewrite-entry-bytes packed output c0
                           (lines-bytes [[(name dup) value]
                                         [(name dup) value]]))

      (let [cause (try
                    (import-package profile output)
                    nil
                    (catch Throwable cause cause))]
        (t/is (some? cause) "a key repeated inside one chunk is refused")
        (t/is (= :inconsistent-penpot-file (:code (ex-data cause))))
        (t/is (= c0 (:path (ex-data cause))))
        (t/is (= (name dup) (:key (ex-data cause))))))))

(t/deftest import-binfile-v3-refuses-a-chunk-index-outside-the-count
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 1)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        extra   (pc/chunk-path file-id page-id 5)]

    (export-compact! file-id packed 1)
    (add-json-entry packed output extra {})

    (let [page  (with-open [zip (ZipFile. (fs/file packed))]
                  (read-zip-json zip (str "files/" file-id "/pages/" page-id ".json")))
          cause (try
                  (import-package profile output)
                  nil
                  (catch Throwable cause cause))]
      (t/is (some? cause) "a chunk above the count of the page is refused")
      (t/is (= :inconsistent-penpot-file (:code (ex-data cause))))
      (t/is (= extra (:path (ex-data cause))))
      (t/is (= 5 (:index (ex-data cause))))
      (t/is (= (:chunks page) (:chunks (ex-data cause)))))))

(t/deftest import-binfile-v3-refuses-an-empty-chunk
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 2)
        packed  (tmp/tempfile :suffix ".zip")
        output  (tmp/tempfile :suffix ".zip")
        c0      (pc/chunk-path file-id page-id 0)]

    (export-compact! file-id packed 1)
    (rewrite-entry-bytes packed output c0 (byte-array 0))

    (let [cause (try
                  (import-package profile output)
                  nil
                  (catch Throwable cause cause))]
      (t/is (some? cause) "a chunk with no lines is refused")
      (t/is (= :inconsistent-penpot-file (:code (ex-data cause))))
      (t/is (= c0 (:path (ex-data cause)))))))

(t/deftest import-binfile-v3-imports-a-package-produced-by-the-library
  ;; The compatibility the guide promises is inferred when both writers and the
  ;; reader are tested apart. This reads the fixture that `@penpot/library`
  ;; really writes: regenerate it with
  ;; `cd library && pnpm run build && node scripts/generate-binfile-fixture.mjs`.
  (let [profile  (th/create-profile* 1)
        input    (-> (jio/resource "backend_tests/test_files/library-v2.penpot")
                     jio/file)
        result   (import-package profile input)
        file-id  (first (:file-ids result))
        imported (bfc/get-file th/*system* file-id {:realize? true})
        data     (:data imported)
        page-id  (first (:pages data))
        page     (get-in data [:pages-index page-id])]

    (t/is (= 1 (count (:file-ids result))))
    (t/is (= 1 (count (:pages data))))
    (let [rects (filter #(= :rect (:type %)) (vals (:objects page)))]
      (t/is (= 2 (count rects))
            (pr-str (map (juxt :id :type) (vals (:objects page))))))
    (t/is (contains? (:objects page) uuid/zero)
          "and the root frame the library writes")
    (t/is (= 1 (count (:components data)))
          "the packed components import")
    (t/is (= 1 (count (:colors data))))
    (t/is (= 1 (count (:typographies data))))
    (t/is (= 1 (count (db/query th/*system* :file-media-object {:file-id file-id})))
          "the packed media descriptor imports")
    (t/is (some? (:media-id (first (db/query th/*system* :file-media-object
                                             {:file-id file-id}))))
          "and resolves to the restored storage object")))

(t/deftest import-binfile-v3-keeps-no-transport-key-on-the-page
  ;; `:chunks` (and `:index`, `:options`) are how the package travels, not page
  ;; data: an imported page must not carry them into the stored file.
  (let [profile (th/create-profile* 1)
        {:keys [file-id page-id]} (add-page-with-rects profile 2)
        output  (tmp/tempfile :suffix ".zip")]

    (export-compact! file-id output 1)

    (let [result   (import-package profile output)
          imported (-> (bfc/get-file th/*system* (first (:file-ids result)) {:realize? true})
                       :data :pages-index (get page-id))]

      (t/is (some? imported))
      (t/is (not (contains? imported :chunks)) "no chunk count in the stored page")
      (t/is (not (contains? imported :index)) "no position in the stored page")
      (t/is (not (contains? imported :options)) "no options in the stored page"))))
