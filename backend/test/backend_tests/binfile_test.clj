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
   [app.common.files.tokens :as cfo]
   [app.common.files.validate :as cfv]
   [app.common.pprint :as pp]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.binfile :as binfile]
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

(defn- make-token-lib-with-activation
  [set-id theme-id set-name theme-name]
  (-> (ctob/make-tokens-lib)
      (ctob/add-set (ctob/make-token-set :id set-id :name set-name))
      (ctob/add-token set-id
                      (cto/make-token :name (str "color." set-name)
                                      :type :color
                                      :value "#000000"))
      (ctob/add-theme (ctob/make-token-theme :id theme-id
                                             :name theme-name
                                             :sets #{set-name}))))

(defn- create-token-source-consumer!
  [profile source-index file-index name local?]
  (let [source             (th/create-file* source-index
                                            {:profile-id (:id profile)
                                             :project-id (:default-project-id profile)
                                             :is-shared true
                                             :name name})
        file               (th/create-file* file-index
                                            {:profile-id (:id profile)
                                             :project-id (:default-project-id profile)
                                             :is-shared false
                                             :name (str name " Consumer")})
        source-set-id      (uuid/next)
        source-theme-id    (uuid/next)
        source-tokens      (make-token-lib-with-activation source-set-id source-theme-id
                                                           "shared" "Dark")
        local-set-id       (when local? (uuid/next))
        local-theme-id     (when local? (uuid/next))
        local-tokens       (when local?
                             (make-token-lib-with-activation local-set-id local-theme-id
                                                            "shared" "Dark"))
        changes            (cond-> []
                             local? (conj {:type :set-tokens-lib
                                           :tokens-lib local-tokens})
                             true (conj {:type :set-tokens-source
                                         :file-id (:id file)
                                         :library-id (:id source)})
                             true (conj {:type :set-tokens-status
                                         :theme-ids #{source-theme-id}
                                         :set-ids #{source-set-id}}))]
    (update-file! :file-id (:id source)
                  :profile-id (:id profile)
                  :changes [{:type :set-tokens-lib :tokens-lib source-tokens}])
    (update-file! :file-id (:id file)
                  :profile-id (:id profile)
                  :changes changes)
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file)
                 :library-file-id (:id source)})
    {:source source
     :file file
     :source-set-id source-set-id
     :source-theme-id source-theme-id
     :local-set-id local-set-id
     :local-theme-id local-theme-id}))

(defn- export-link-later!
  [file-ids]
  (let [output (tmp/tempfile :suffix ".zip")]
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids (set file-ids))
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))
    output))

(defn- import-link-later!
  [profile input]
  (-> th/*system*
      (assoc ::bfc/project-id (:default-project-id profile))
      (assoc ::bfc/profile-id (:id profile))
      (assoc ::bfc/team-id (:default-team-id profile))
      (assoc ::bfc/input input)
      (v3/import-files!)))

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
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/export-type :detach-libraries))
     (io/output-stream output))

    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
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

(t/deftest export-binfile-v3
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        output  (tmp/tempfile :suffix ".zip")]

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/export-type :detach-libraries))
     (io/output-stream output))

    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
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
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/export-type :detach-libraries))
     (io/output-stream output))

    (let [result   (-> th/*system*
                       (assoc ::bfc/project-id (:default-project-id profile))
                       (assoc ::bfc/profile-id (:id profile))
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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

      ;; Read the manifest and check external-libraries
      (let [manifest (v3/get-manifest th/*system* output)]
        (t/is (some? (:external-libraries manifest)))
        (t/is (= 1 (count (:external-libraries manifest))))
        (let [ext-lib (first (:external-libraries manifest))]
          (t/is (= (:id library) (:id ext-lib)))
          (t/is (= "Icons Library" (:name ext-lib)))
          (t/is (= "icons-library" (:slug ext-lib)))
          (t/is (= [(:id file)] (:used-by ext-lib))))))))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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

(t/deftest link-later-restores-token-source-activation-by-name
  (let [profile            (th/create-profile* 1)
        library           (th/create-file* 1 {:profile-id (:id profile)
                                               :project-id (:default-project-id profile)
                                               :is-shared true
                                               :name "Token Library"})
        file              (th/create-file* 2 {:profile-id (:id profile)
                                               :project-id (:default-project-id profile)
                                               :is-shared false})
        local-set-id      (uuid/next)
        local-theme-id    (uuid/next)
        source-set-id     (uuid/next)
        source-theme-id   (uuid/next)
        missing-set-id    (uuid/next)
        missing-theme-id  (uuid/next)
        local-tokens      (make-token-lib-with-activation local-set-id local-theme-id
                                                          "local" "Local")
        source-tokens     (-> (make-token-lib-with-activation source-set-id source-theme-id
                                                              "shared" "Dark")
                              (ctob/add-set (ctob/make-token-set :id missing-set-id
                                                                 :name "retired"))
                              (ctob/add-token missing-set-id
                                              (cto/make-token :name "color.retired"
                                                              :type :color
                                                              :value "#000000"))
                              (ctob/add-theme (ctob/make-token-theme :id missing-theme-id
                                                                     :name "Retired"
                                                                     :sets #{"retired"})))
        output            (tmp/tempfile :suffix ".zip")]

    (update-file! :file-id (:id library)
                  :profile-id (:id profile)
                  :changes [{:type :set-tokens-lib :tokens-lib source-tokens}])
    (update-file! :file-id (:id file)
                  :profile-id (:id profile)
                  :changes [{:type :set-tokens-lib :tokens-lib local-tokens}
                            {:type :set-tokens-source
                             :file-id (:id file)
                             :library-id (:id library)}
                            {:type :set-tokens-status
                             :theme-ids #{source-theme-id missing-theme-id}
                             :set-ids #{source-set-id missing-set-id}}])
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id file)
                 :library-file-id (:id library)})

    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))

    (db/update! th/*system* :file
                {:deleted-at (ct/now)}
                {:id (:id library)})

    (let [replacement (th/create-file* 3 {:profile-id (:id profile)
                                          :project-id (:default-project-id profile)
                                          :is-shared true
                                          :name "Token Library"})
          replacement-set-id (uuid/next)
          replacement-theme-id (uuid/next)
          replacement-tokens (make-token-lib-with-activation replacement-set-id
                                                              replacement-theme-id
                                                              "shared" "Dark")
          _ (update-file! :file-id (:id replacement)
                          :profile-id (:id profile)
                          :changes [{:type :set-tokens-lib
                                     :tokens-lib replacement-tokens}])
          result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id (:default-team-id profile))
                     (assoc ::bfc/input output)
                     (v3/import-files!))
          imported (bfc/get-file th/*system* (first (:file-ids result)))
          status (get-in imported [:data :tokens-status])
          rels (db/query th/*system* :file-library-rel
                         {:file-id (:id imported)
                          :library-file-id (:id replacement)})]

      (t/is (= 1 (count rels)))
      (t/is (= (:id replacement) (cfo/get-tokens-source (:data imported))))
      (t/is (ctos/tokens-status? status))
      (t/is (= #{replacement-theme-id} (ctos/get-active-theme-ids status)))
      (t/is (= #{replacement-set-id} (ctos/get-active-set-ids status)))
      (t/is (not (contains? (ctos/get-active-theme-ids status) source-theme-id)))
      (t/is (not (contains? (ctos/get-active-set-ids status) source-set-id)))
      (t/is (not (contains? (ctos/get-active-theme-ids status) missing-theme-id)))
      (t/is (not (contains? (ctos/get-active-set-ids status) missing-set-id)))
      (t/is (nil? (cfv/validate-file imported [replacement]))))))

(t/deftest link-later-token-source-fallbacks
  (let [profile     (th/create-profile* 1)
        cases       [(create-token-source-consumer! profile 1 2 "Local Fallback" true)
                     (create-token-source-consumer! profile 3 4 "Empty Fallback" false)
                     (create-token-source-consumer! profile 5 6 "Local Missing" true)
                     (create-token-source-consumer! profile 7 8 "Empty Missing" false)]
        asset-lib   (th/create-file* 9 {:profile-id (:id profile)
                                        :project-id (:default-project-id profile)
                                        :is-shared true
                                        :name "Unrelated Assets"})
        local-file  (th/create-file* 10 {:profile-id (:id profile)
                                         :project-id (:default-project-id profile)
                                         :is-shared false
                                         :name "Local Token Source"})
        local-set-id (uuid/next)
        local-theme-id (uuid/next)
        local-tokens (make-token-lib-with-activation local-set-id local-theme-id
                                                     "local" "Local")
        _ (update-file! :file-id (:id local-file)
                        :profile-id (:id profile)
                        :changes [{:type :set-tokens-lib :tokens-lib local-tokens}
                                  {:type :set-tokens-status
                                   :theme-ids #{local-theme-id}
                                   :set-ids #{local-set-id}}])
        _ (db/insert! th/*system* :file-library-rel
                      {:file-id (:id local-file)
                       :library-file-id (:id asset-lib)})
        files       (conj (mapv :file cases) local-file)
        output      (export-link-later! (map :id files))
        old-libs    (conj (mapv :source cases) asset-lib)
        _ (doseq [library old-libs]
            (db/update! th/*system* :file
                        {:deleted-at (ct/now)}
                        {:id (:id library)}))
        local-match (th/create-file* 11 {:profile-id (:id profile)
                                         :project-id (:default-project-id profile)
                                         :is-shared true
                                         :name "Local Fallback"})
        empty-match (th/create-file* 12 {:profile-id (:id profile)
                                         :project-id (:default-project-id profile)
                                         :is-shared true
                                         :name "Empty Fallback"})
        asset-match (th/create-file* 13 {:profile-id (:id profile)
                                         :project-id (:default-project-id profile)
                                         :is-shared true
                                         :name "Unrelated Assets"})
        result      (import-link-later! profile output)
        imported    (into {}
                          (map (fn [file-id]
                                 (let [file (bfc/get-file th/*system* file-id)]
                                   [(:name file) file])))
                          (:file-ids result))
        local-match-file (get imported "Local Fallback Consumer")
        empty-match-file (get imported "Empty Fallback Consumer")
        local-missing-file (get imported "Local Missing Consumer")
        empty-missing-file (get imported "Empty Missing Consumer")
        local-file' (get imported "Local Token Source")]

    (doseq [[file replacement] [[local-match-file local-match]
                                [empty-match-file empty-match]]]
      (t/is (= 1 (count (db/query th/*system* :file-library-rel
                                  {:file-id (:id file)
                                   :library-file-id (:id replacement)}))))
      (t/is (nil? (cfo/get-tokens-source (:data file))))
      (t/is (ctos/tokens-status? (get-in file [:data :tokens-status])))
      (t/is (nil? (:pending-tokens-source (:data file))))
      (t/is (nil? (cfv/validate-file file []))))

    (let [status (get-in local-match-file [:data :tokens-status])]
      (t/is (= (:id local-match-file) (cfo/get-effective-tokens-source (:data local-match-file))))
      (t/is (= #{(:local-theme-id (first cases))}
               (ctos/get-active-theme-ids status)))
      (t/is (= #{(:local-set-id (first cases))}
               (ctos/get-active-set-ids status))))

    (doseq [file [empty-match-file empty-missing-file]]
      (let [status (get-in file [:data :tokens-status])]
        (t/is (ctos/tokens-status? status))
        (t/is (empty? (ctos/get-active-theme-ids status)))
        (t/is (empty? (ctos/get-active-set-ids status)))
        (t/is (not (cfo/tokens-provider? (:data file))))))

    (let [status (get-in local-missing-file [:data :tokens-status])]
      (t/is (= (:id local-missing-file) (cfo/get-effective-tokens-source (:data local-missing-file))))
      (t/is (= #{(:local-theme-id (nth cases 2))}
               (ctos/get-active-theme-ids status)))
      (t/is (= #{(:local-set-id (nth cases 2))}
               (ctos/get-active-set-ids status)))
      (t/is (nil? (:pending-tokens-source (:data local-missing-file))))
      (t/is (nil? (cfv/validate-file local-missing-file []))))

    (doseq [[case file outcome] [[(first cases) local-match-file :tokens-source-fallback-local]
                                 [(second cases) empty-match-file :tokens-source-deactivated]
                                 [(nth cases 2) local-missing-file :tokens-source-fallback-local]
                                 [(nth cases 3) empty-missing-file :tokens-source-deactivated]]]
      (t/is (= outcome (get-in result [:tokens-source-outcomes (:id file)])))
      (t/is (nil? (cfo/get-tokens-source (:data file))))
      (t/is (not (contains? (ctos/get-active-theme-ids (get-in file [:data :tokens-status]))
                            (:source-theme-id case))))
      (t/is (not (contains? (ctos/get-active-set-ids (get-in file [:data :tokens-status]))
                            (:source-set-id case)))))

    (t/is (= 1 (count (db/query th/*system* :file-library-rel
                                {:file-id (:id local-file')
                                 :library-file-id (:id asset-match)}))))
    (t/is (= (:id local-file') (cfo/get-effective-tokens-source (:data local-file'))))
    (t/is (= #{local-theme-id}
             (ctos/get-active-theme-ids (get-in local-file' [:data :tokens-status]))))
    (t/is (= #{local-set-id}
             (ctos/get-active-set-ids (get-in local-file' [:data :tokens-status]))))
    (t/is (nil? (get-in result [:tokens-source-outcomes (:id local-file')])))))

(t/deftest link-later-manual-token-source-resolution
  (let [profile          (th/create-profile* 1)
        source           (th/create-file* 1 {:profile-id (:id profile)
                                             :project-id (:default-project-id profile)
                                             :is-shared true
                                             :name "Manual Token Source"})
        source-set-id    (uuid/next)
        source-theme-id  (uuid/next)
        source-tokens    (make-token-lib-with-activation source-set-id source-theme-id
                                                         "shared" "Dark")
        first-file       (th/create-file* 2 {:profile-id (:id profile)
                                             :project-id (:default-project-id profile)
                                             :is-shared false
                                             :name "Manual Chosen Consumer"})
        second-file      (th/create-file* 3 {:profile-id (:id profile)
                                             :project-id (:default-project-id profile)
                                             :is-shared false
                                             :name "Manual Skipped Consumer"})
        ordinary-file    (th/create-file* 6 {:profile-id (:id profile)
                                             :project-id (:default-project-id profile)
                                             :is-shared false
                                             :name "Manual Ordinary Consumer"})
        first-local-set  (uuid/next)
        first-local-theme (uuid/next)
        first-local      (make-token-lib-with-activation first-local-set first-local-theme
                                                          "shared" "Dark")
        second-local-set (uuid/next)
        second-local-theme (uuid/next)
        second-local     (make-token-lib-with-activation second-local-set second-local-theme
                                                          "shared" "Dark")
        ordinary-local-set (uuid/next)
        ordinary-local-theme (uuid/next)
        ordinary-local (make-token-lib-with-activation ordinary-local-set ordinary-local-theme
                                                       "local" "Local")
        output           (tmp/tempfile :suffix ".zip")]
    (update-file! :file-id (:id source)
                  :profile-id (:id profile)
                  :changes [{:type :set-tokens-lib :tokens-lib source-tokens}])
    (doseq [[file tokens-lib] [[first-file first-local]
                               [second-file second-local]]]
      (update-file! :file-id (:id file)
                    :profile-id (:id profile)
                    :changes [{:type :set-tokens-lib :tokens-lib tokens-lib}
                              {:type :set-tokens-source
                               :file-id (:id file)
                               :library-id (:id source)}
                              {:type :set-tokens-status
                               :theme-ids #{source-theme-id}
                               :set-ids #{source-set-id}}])
      (db/insert! th/*system* :file-library-rel
                  {:file-id (:id file)
                   :library-file-id (:id source)}))
    (update-file! :file-id (:id ordinary-file)
                  :profile-id (:id profile)
                  :changes [{:type :set-tokens-lib :tokens-lib ordinary-local}
                            {:type :set-tokens-status
                             :theme-ids #{ordinary-local-theme}
                             :set-ids #{ordinary-local-set}}])
    (db/insert! th/*system* :file-library-rel
                {:file-id (:id ordinary-file)
                 :library-file-id (:id source)})
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id first-file) (:id second-file) (:id ordinary-file)})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))
    (db/update! th/*system* :file
                {:deleted-at (ct/now)}
                {:id (:id source)})
    (let [candidate-a (th/create-file* 4 {:profile-id (:id profile)
                                          :project-id (:default-project-id profile)
                                          :is-shared true
                                          :name "Manual Token Source"})
          candidate-b (th/create-file* 5 {:profile-id (:id profile)
                                          :project-id (:default-project-id profile)
                                          :is-shared true
                                          :name "Manual Token Source"})
          candidate-a-set (uuid/next)
          candidate-a-theme (uuid/next)
          candidate-b-set (uuid/next)
          candidate-b-theme (uuid/next)
          _ (update-file! :file-id (:id candidate-a)
                          :profile-id (:id profile)
                          :changes [{:type :set-tokens-lib
                                     :tokens-lib (make-token-lib-with-activation
                                                  candidate-a-set candidate-a-theme "shared" "Dark")}])
          _ (update-file! :file-id (:id candidate-b)
                          :profile-id (:id profile)
                          :changes [{:type :set-tokens-lib
                                     :tokens-lib (make-token-lib-with-activation
                                                  candidate-b-set candidate-b-theme "shared" "Dark")}])
          result (import-link-later! profile output)
          imported (into {}
                         (map (fn [file-id]
                                (let [file (bfc/get-file th/*system* file-id)]
                                  [(:name file) file])))
                         (:file-ids result))
          first-imported (get imported "Manual Chosen Consumer")
          second-imported (get imported "Manual Skipped Consumer")
          ordinary-imported (get imported "Manual Ordinary Consumer")
          first-before (bfc/get-file th/*system* (:id first-imported))
          second-before (bfc/get-file th/*system* (:id second-imported))
          pending (first (get-in result [:resolution (:id first-imported) :pending]))
          ordinary-pending (first (get-in result [:resolution (:id ordinary-imported) :pending]))]
      (t/is (:tokens-source? pending))
      (t/is (not (:tokens-source? ordinary-pending)))
      (t/is (= #{(:id candidate-a) (:id candidate-b)}
               (get-in first-imported [:data :pending-tokens-source :candidate-ids])))
      (t/is (nil? (cfv/validate-file first-imported [])))
      (let [chosen-out (th/command! {::th/type :resolve-import-token-source
                                     ::rpc/profile-id (:id profile)
                                     :file-id (:id first-imported)
                                     :library-id (:id candidate-a)})
            skipped-out (th/command! {::th/type :resolve-import-token-source
                                      ::rpc/profile-id (:id profile)
                                      :file-id (:id second-imported)})
            ordinary-out (th/command! {::th/type :link-file-to-library
                                       ::rpc/profile-id (:id profile)
                                       :file-id (:id ordinary-imported)
                                       :library-id (:id candidate-b)})]
        (t/is (nil? (:error chosen-out)))
        (t/is (nil? (:error skipped-out)))
        (t/is (nil? (:error ordinary-out)))
        (t/is (= :tokens-source-restored
                 (get-in chosen-out [:result :tokens-source-outcome])))
        (t/is (= :tokens-source-fallback-local
                 (get-in skipped-out [:result :tokens-source-outcome]))))
      (let [first-imported (bfc/get-file th/*system* (:id first-imported))
            second-imported (bfc/get-file th/*system* (:id second-imported))
            ordinary-imported (bfc/get-file th/*system* (:id ordinary-imported))
            first-status (get-in first-imported [:data :tokens-status])
            second-status (get-in second-imported [:data :tokens-status])
            ordinary-status (get-in ordinary-imported [:data :tokens-status])]
        (t/is (= (inc (:revn first-before)) (:revn first-imported)))
        (t/is (not= (:modified-at first-before) (:modified-at first-imported)))
        (t/is (= (inc (:revn second-before)) (:revn second-imported)))
        (t/is (not= (:modified-at second-before) (:modified-at second-imported)))
        (t/is (= (:id candidate-a) (cfo/get-tokens-source (:data first-imported))))
        (t/is (= #{candidate-a-theme} (ctos/get-active-theme-ids first-status)))
        (t/is (= #{candidate-a-set} (ctos/get-active-set-ids first-status)))
        (t/is (nil? (:pending-tokens-source (:data first-imported))))
        (t/is (nil? (cfo/get-tokens-source (:data second-imported))))
        (t/is (= #{second-local-theme} (ctos/get-active-theme-ids second-status)))
        (t/is (= #{second-local-set} (ctos/get-active-set-ids second-status)))
        (t/is (nil? (:pending-tokens-source (:data second-imported))))
        (t/is (nil? (cfo/get-tokens-source (:data ordinary-imported))))
        (t/is (= (:id ordinary-imported)
                 (cfo/get-effective-tokens-source (:data ordinary-imported))))
        (t/is (= #{ordinary-local-theme} (ctos/get-active-theme-ids ordinary-status)))
        (t/is (= #{ordinary-local-set} (ctos/get-active-set-ids ordinary-status)))
        (t/is (nil? (:pending-tokens-source (:data ordinary-imported))))
        (t/is (= 1 (count (db/query th/*system* :file-library-rel
                                    {:file-id (:id first-imported)
                                     :library-file-id (:id candidate-a)}))))
        (t/is (= 1 (count (db/query th/*system* :file-library-rel
                                    {:file-id (:id ordinary-imported)
                                     :library-file-id (:id candidate-b)}))))
        (t/is (empty? (db/query th/*system* :file-library-rel
                                {:file-id (:id second-imported)})))
        (t/is (nil? (cfv/validate-file first-imported [candidate-a])))
        (t/is (nil? (cfv/validate-file second-imported [])))
        (t/is (nil? (cfv/validate-file ordinary-imported [candidate-b])))))))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :detach-libraries))
       (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file1) (:id file2)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
         (assoc ::bfc/ids #{file-id})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))

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
         (assoc ::bfc/ids #{file-id})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))

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

(t/deftest link-later-overwrite-import-no-resolution
  (let [{:keys [profile file-id team-id]} (import-sample-file)
        output (tmp/tempfile :suffix ".zip")]
    ;; Export file with link-later
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{file-id})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))

    ;; Import with overwrite (file-id set)
    (let [result (-> th/*system*
                     (assoc ::bfc/project-id (:default-project-id profile))
                     (assoc ::bfc/profile-id (:id profile))
                     (assoc ::bfc/team-id team-id)
                     (assoc ::bfc/file-id file-id)
                     (assoc ::bfc/input output)
                     (v3/import-files!))]
      ;; Overwrite should have empty resolution
      (t/is (= {} (:resolution result))))))

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
         (assoc ::bfc/ids #{file-id})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))

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
         (assoc ::bfc/ids #{file-id})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))

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
         (assoc ::bfc/ids #{file-id})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))

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
         (assoc ::bfc/ids #{file-id})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))

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
         (assoc ::bfc/ids #{file-id})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file1) (:id file2)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file1) (:id file2)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file1) (:id file2)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
         (assoc ::bfc/ids #{file-id})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))

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
         (assoc ::bfc/ids #{file-id})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :link-later))
       (io/output-stream output))

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
         (assoc ::bfc/ids #{file-id})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))

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
         (assoc ::bfc/ids #{file-id})
         (assoc ::bfc/export-type :link-later))
     (io/output-stream output))

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
         (assoc ::bfc/ids #{file-id})
         (assoc ::bfc/export-type :link-later)
         (assoc ::bfc/include-libraries true))
     (io/output-stream output))

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
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/export-type :detach-libraries))
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
         (assoc ::bfc/export-type :detach-libraries))
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

(t/deftest import-configures-finite-transaction-idle-timeout
  ;; The binfile import transaction must set a finite ceiling for
  ;; idle_in_transaction_session_timeout (20 min) instead of disabling
  ;; it entirely: a stalled import must not retain a pool connection
  ;; without any upper bound. The ceiling must be scoped with SET
  ;; LOCAL: once the transaction ends, the pool session default must
  ;; be restored for subsequent transactions.
  (let [pg-setting-sql ["SELECT setting FROM pg_settings WHERE name = 'idle_in_transaction_session_timeout'"]
        in-tx  (db/tx-run! th/*system*
                           (fn [cfg]
                             (bfc/configure-database-timeouts! cfg)
                             (:setting (db/exec-one! cfg pg-setting-sql))))
        ;; SET LOCAL must not leak past the transaction boundary
        after  (db/tx-run! th/*system*
                           (fn [cfg]
                             (:setting (db/exec-one! cfg pg-setting-sql))))]
    (t/is (= "1200000" in-tx))
    (t/is (= "300000" after))))

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
                "objects/11111111-1111-1111-1111-111111111111.json"
                (str "files/" f "/media/33333333-3333-3333-3333-333333333333.json")
                (str "files/" f "/colors/not-a-uuid.json")
                (str "files/" f "/components/comp.json")
                (str "files/" f "/typographies/t.json")
                (str "files/" f "/pages/" page ".json")
                (str "files/" f "/pages/" page "/shape.json")
                (str "files/" f "/thumbnails/medium/" page "/frame.json")])]

    (t/is (= [(parse-uuid "11111111-1111-1111-1111-111111111111")]
             (mapv :id (:objects index))))

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
         (assoc ::bfc/ids #{(:id simple) (:id svg)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream output))

    ;; import returns the imported file ids plus the library link
    ;; resolution; the test only needs the ids
    (let [result (:file-ids (-> th/*system*
                                (assoc ::bfc/project-id (:default-project-id profile))
                                (assoc ::bfc/profile-id (:id profile))
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
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream output))

    ;; import returns the imported file ids plus the library link
    ;; resolution; the test only needs the ids
    (let [result  (:file-ids (-> th/*system*
                                 (assoc ::bfc/project-id (:default-project-id profile))
                                 (assoc ::bfc/profile-id (:id profile))
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
