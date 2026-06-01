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
   [app.common.files.validate :as cfv]
   [app.common.pprint :as pp]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
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
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io])
  (:import
   java.io.ByteArrayInputStream
   java.io.DataInputStream))

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
      (t/is (map? result))
      (t/is (= 1 (count (:file-ids result))))
      (t/is (every? uuid? (:file-ids result)))
      ;; No external libraries in simple case - resolution should be empty
      (t/is (= {} (:resolution result)))))

(t/deftest export-binfile-preserves-public-uri-subpath
  (let [profile (th/create-profile* 1)
        file    (prepare-simple-file profile)
        config  (assoc cf/config :public-uri "https://example.com/penpot")
        params  {:file-id (:id file)
                 :include-libraries false
                 :embed-assets false}
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
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
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
           (assoc ::bfc/embed-assets false)
           (assoc ::bfc/include-libraries false))
       (io/output-stream output))

      ;; Read the manifest and check external-libraries
      (let [manifest (v3/get-manifest output)]
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
           (assoc ::bfc/embed-assets false)
           (assoc ::bfc/include-libraries false))
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
           (assoc ::bfc/embed-assets false)
           (assoc ::bfc/include-libraries false))
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
           (assoc ::bfc/embed-assets false)
           (assoc ::bfc/include-libraries false))
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

    ;; Export without including libraries
    (let [output (tmp/tempfile :suffix ".zip")]
      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/embed-assets false)
           (assoc ::bfc/include-libraries false))
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
           (assoc ::bfc/embed-assets false)
           (assoc ::bfc/include-libraries false))
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
          (t/is (= (:id library2) (:linked-to (first (:done file-with-done))))))

        ;; But only one file-library-rel should exist (for file1)
        (let [rels (db/query th/*system* :file-library-rel
                             {:library-file-id (:id library2)})]
           (t/is (= 1 (count rels))))))))
