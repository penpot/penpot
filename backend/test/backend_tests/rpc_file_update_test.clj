;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-file-update-test
  (:require
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.files.validate :as val]
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(defn- with-validation
  [next]
  (with-redefs [cf/flags (conj cf/flags :file-validation :file-schema-validation)]
    (next)))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset with-validation)

(defn- create-file
  []
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})]
    {:profile-id (:id profile)
     :file-id    (:id file)
     :page-id    (-> file :data :pages first)}))

(defn- break-parent
  "A change that leaves the shape pointing to a parent that does not exist."
  [page-id id]
  {:type :mod-obj
   :page-id page-id
   :id id
   :operations [{:type :set :attr :parent-id :val (uuid/next)}]})

(defn- conflicting-page-change
  "A change that fails while applied: `:add-page` refuses an id and name
  together with a page."
  []
  {:type :add-page
   :id (uuid/next)
   :name "page"
   :page {:id (uuid/next) :name "page" :objects {}}})

(defn- update-file
  [{:keys [profile-id file-id]} params]
  (th/command! (merge {::th/type :update-file
                       ::rpc/profile-id profile-id
                       :id file-id
                       :session-id (uuid/next)
                       :revn 0
                       :vern 0
                       :features cfeat/supported-features}
                      params)))

(defn- commit
  [& changes]
  {:commit-id (uuid/next) :changes (vec changes)})

(defn- get-file-data
  [{:keys [profile-id file-id]}]
  (-> (th/command! {::th/type :get-file
                    ::rpc/profile-id profile-id
                    :id file-id})
      :result
      :data))

(defn- get-file-changes
  [{:keys [file-id]}]
  (->> (th/db-query :file-change {:file-id file-id} {:order-by [:revn]})
       (filter :changes)
       (mapv #(update % :changes blob/decode))))

(defn- capture-messages
  [next]
  (let [messages (atom [])]
    (with-redefs [mbus/pub! (fn [_ & {:keys [message]}]
                              (swap! messages conj message))]
      (let [out (next)]
        {:out out :messages @messages}))))

(t/deftest saves-the-commits-before-the-first-invalid-one
  (let [{:keys [page-id] :as ctx} (create-file)
        shape-a (uuid/next)
        shape-c (uuid/next)
        commit1 (commit (th/add-rect-change page-id shape-a))
        commit2 (commit (break-parent page-id shape-a))
        commit3 (commit (th/add-rect-change page-id shape-c))

        {:keys [out messages]}
        (capture-messages
         #(update-file ctx {:changes-with-metadata [commit1 commit2 commit3]}))]

    (t/is (nil? (:error out)))

    (t/testing "the response names the rejected commits and the failure"
      (let [rejected (-> out :result :rejected)]
        (t/is (= [(:commit-id commit2) (:commit-id commit3)] (:commit-ids rejected)))
        (t/is (= (:commit-id commit2) (:failed-commit-id rejected)))
        (t/is (= :validation (-> rejected :error :type)))
        (t/is (= :referential-integrity (-> rejected :error :code)))))

    (t/testing "the file holds the accepted commit only"
      (let [objects (-> (get-file-data ctx) :pages-index (get page-id) :objects)]
        (t/is (= uuid/zero (get-in objects [shape-a :parent-id])))
        (t/is (not (contains? objects shape-c)))))

    (t/testing "the change log holds the accepted commit only"
      (let [rows (get-file-changes ctx)]
        (t/is (= 1 (count rows)))
        (t/is (= 1 (:revn (first rows))))
        (t/is (= (:changes commit1) (:changes (first rows))))))

    (t/testing "peers get the accepted commit only"
      (let [file-changes (filter #(= :file-change (:type %)) messages)]
        (t/is (= 1 (count file-changes)))
        (t/is (= (:changes commit1) (:changes (first file-changes))))))))

(t/deftest saves-nothing-when-the-first-commit-is-invalid
  (let [{:keys [page-id] :as ctx} (create-file)
        shape-a (uuid/next)
        _       (update-file ctx {:changes [(th/add-rect-change page-id shape-a)]})
        commit1 (commit (break-parent page-id shape-a))
        commit2 (commit (th/add-rect-change page-id (uuid/next)))

        {:keys [out messages]}
        (capture-messages
         #(update-file ctx {:revn 1 :changes-with-metadata [commit1 commit2]}))]

    (t/is (nil? (:error out)))
    (t/is (= [(:commit-id commit1) (:commit-id commit2)]
             (-> out :result :rejected :commit-ids)))
    (t/is (= 1 (:revn (th/db-get :file {:id (:file-id ctx)}))))
    (t/is (= 1 (count (get-file-changes ctx))))
    (t/is (empty? messages))))

(t/deftest rejects-a-commit-that-throws-while-applied
  (let [{:keys [page-id] :as ctx} (create-file)
        shape-a (uuid/next)
        commit1 (commit (th/add-rect-change page-id shape-a))
        commit2 (commit (conflicting-page-change))
        out     (update-file ctx {:changes-with-metadata [commit1 commit2]})]

    (t/is (nil? (:error out)))
    (t/is (= [(:commit-id commit2)] (-> out :result :rejected :commit-ids)))
    (t/is (= :conflict (-> out :result :rejected :error :type)))
    (t/is (= 1 (:revn (th/db-get :file {:id (:file-id ctx)}))))))

(t/deftest rejects-a-commit-that-only-fails-the-schema
  (let [{:keys [page-id] :as ctx} (create-file)
        shape-a  (uuid/next)
        shape-c  (uuid/next)
        commit1  (commit (th/add-rect-change page-id shape-a))
        commit2  (commit {:type :mod-obj
                          :page-id page-id
                          :id shape-a
                          :operations [{:type :set :attr :name :val "schema-breaker"}]})
        commit3  (commit (th/add-rect-change page-id shape-c))
        validate val/validate-file-schema-affected!
        ;; Changes and shapes are schema checked while applied, so no real
        ;; change reaches the file schema check invalid: one is simulated
        out      (with-redefs [val/validate-file-schema-affected!
                               (fn [file changes]
                                 (when (= "schema-breaker"
                                          (get-in file [:data :pages-index page-id :objects shape-a :name]))
                                   (ex/raise :type :validation :code :schema-validation :hint "schema"))
                                 (validate file changes))]
                   (update-file ctx {:changes-with-metadata [commit1 commit2 commit3]}))
        objects  (-> (get-file-data ctx) :pages-index (get page-id) :objects)]

    (t/is (nil? (:error out)))
    (t/is (= [(:commit-id commit2) (:commit-id commit3)]
             (-> out :result :rejected :commit-ids)))
    (t/is (= :schema-validation (-> out :result :rejected :error :code)))
    (t/is (= "rect" (get-in objects [shape-a :name])))
    (t/is (not (contains? objects shape-c)))))

(t/deftest saves-every-commit-when-all-are-valid
  (let [{:keys [page-id] :as ctx} (create-file)
        out (update-file ctx {:changes-with-metadata [(commit (th/add-rect-change page-id (uuid/next)))
                                                      (commit (th/add-rect-change page-id (uuid/next)))]})]
    (t/is (nil? (:error out)))
    (t/is (nil? (-> out :result :rejected)))
    (t/is (= 1 (:revn (th/db-get :file {:id (:file-id ctx)}))))))

(t/deftest flat-changes-stay-all-or-nothing
  (let [{:keys [page-id] :as ctx} (create-file)
        shape-a (uuid/next)
        out     (update-file ctx {:changes [(th/add-rect-change page-id shape-a)
                                            (break-parent page-id shape-a)]})]
    (t/is (= :validation (th/ex-type (:error out))))
    (t/is (= 0 (:revn (th/db-get :file {:id (:file-id ctx)}))))))

(t/deftest groups-without-commit-ids-stay-all-or-nothing
  (let [{:keys [page-id] :as ctx} (create-file)
        shape-a (uuid/next)
        out     (update-file ctx {:changes-with-metadata
                                  [{:changes [(th/add-rect-change page-id shape-a)]}
                                   {:changes [(break-parent page-id shape-a)]}]})]
    (t/is (= :validation (th/ex-type (:error out))))
    (t/is (= 0 (:revn (th/db-get :file {:id (:file-id ctx)}))))))

(t/deftest database-failures-fail-the-whole-request
  (let [{:keys [page-id] :as ctx} (create-file)
        conflict (conflicting-page-change)
        process  cpc/process-changes
        out      (with-redefs [cpc/process-changes
                               (fn [data changes & args]
                                 ;; The database fails on every attempt that
                                 ;; leaves the conflicting commit out: the
                                 ;; retries, never the pass over every commit
                                 (when (and (seq changes)
                                            (not-any? #{conflict} changes))
                                   (throw (java.sql.SQLException. "connection lost")))
                                 (apply process data changes args))]
                   (update-file ctx {:changes-with-metadata
                                     [(commit (th/add-rect-change page-id (uuid/next)))
                                      (commit conflict)]}))]
    (t/is (instance? java.sql.SQLException (:error out)))
    (t/is (= 0 (:revn (th/db-get :file {:id (:file-id ctx)}))))))

(defn- move-rect
  [page-id id x]
  {:type :mod-obj
   :page-id page-id
   :id id
   :operations [{:type :set :attr :x :val x}]})

(defn- wait-for-record
  "The first log record whose message hint is `hint`, waiting up to two
  seconds for the asynchronous logger to write it."
  [records hint]
  (loop [tries 0]
    (if-let [record (->> @records
                         (filter #(= hint (get-in % [::l/props :hint])))
                         (first))]
      record
      (when (< tries 200)
        (Thread/sleep 10)
        (recur (inc tries))))))

(defn- update-file-logged
  "Updates the file and returns the response in `:out` and, in `:rejected`,
  the props of the log record of the rejection, if any."
  [ctx params]
  (let [records (atom [])
        out     (try
                  (add-watch l/log-record ::capture
                             (fn [_ _ _ record] (swap! records conj record)))
                  (update-file ctx params)
                  (finally
                    (remove-watch l/log-record ::capture)))]
    {:out      out
     :rejected (some-> (wait-for-record records "update-file commit rejected")
                       ::l/props)}))

(t/deftest bisects-the-commits-to-find-the-invalid-one
  (let [{:keys [page-id] :as ctx} (create-file)
        shape-a (uuid/next)
        _       (update-file ctx {:changes [(th/add-rect-change page-id shape-a)]})
        commits (mapv (fn [i]
                        (if (= i 5)
                          (commit (break-parent page-id shape-a))
                          (commit (move-rect page-id shape-a i))))
                      (range 16))

        {:keys [out rejected]}
        (update-file-logged ctx {:revn 1 :changes-with-metadata commits})]

    (t/is (nil? (:error out)))
    (t/is (= (mapv :commit-id (subvec commits 5))
             (-> out :result :rejected :commit-ids)))
    (t/is (= (:commit-id (nth commits 5))
             (-> out :result :rejected :failed-commit-id)))
    (t/is (= :referential-integrity (-> out :result :rejected :error :code)))

    (t/testing "the search takes log2 of the commits attempts"
      (t/is (= 4 (:attempts rejected))))

    (t/testing "the file holds the commits before the invalid one"
      (let [objects (-> (get-file-data ctx) :pages-index (get page-id) :objects)]
        (t/is (= uuid/zero (get-in objects [shape-a :parent-id])))
        (t/is (= 4 (get-in objects [shape-a :x])))))))

(t/deftest rejects-a-single-commit-without-a-retry
  (let [{:keys [page-id] :as ctx} (create-file)
        shape-a (uuid/next)
        _       (update-file ctx {:changes [(th/add-rect-change page-id shape-a)]})
        commit1 (commit (break-parent page-id shape-a))

        {:keys [out rejected]}
        (update-file-logged ctx {:revn 1 :changes-with-metadata [commit1]})]

    (t/is (nil? (:error out)))
    (t/is (= [(:commit-id commit1)] (-> out :result :rejected :commit-ids)))
    (t/is (= :referential-integrity (-> out :result :rejected :error :code)))
    (t/is (= 0 (:attempts rejected)) "no retry")
    (t/is (= 1 (:revn (th/db-get :file {:id (:file-id ctx)}))))))

(defn- rename-rect
  [page-id id name]
  {:type :mod-obj
   :page-id page-id
   :id id
   :operations [{:type :set :attr :name :val name}]})

(t/deftest keeps-fifty-commits-before-the-invalid-one
  (let [{:keys [page-id] :as ctx} (create-file)
        shape-a (uuid/next)
        _       (update-file ctx {:changes [(th/add-rect-change page-id shape-a)]})
        renames (mapv #(commit (rename-rect page-id shape-a (str "name " %))) (range 50))
        invalid (commit (break-parent page-id shape-a))

        {:keys [out rejected]}
        (update-file-logged ctx {:revn 1 :changes-with-metadata (conj renames invalid)})]

    (t/is (nil? (:error out)))
    (t/is (= [(:commit-id invalid)] (-> out :result :rejected :commit-ids)))

    (t/testing "the file holds the fifty renames"
      (let [objects (-> (get-file-data ctx) :pages-index (get page-id) :objects)]
        (t/is (= "name 49" (get-in objects [shape-a :name])))
        (t/is (= uuid/zero (get-in objects [shape-a :parent-id])))))

    (t/testing "the change log holds the renames in one revision"
      (let [rows (get-file-changes ctx)]
        (t/is (= 2 (count rows)))
        (t/is (= (into [] (mapcat :changes) renames)
                 (:changes (last rows))))))

    (t/testing "the search takes log2 of the commits attempts"
      (t/is (= 51 (:commits rejected)))
      (t/is (= 6 (:attempts rejected))))))

(t/deftest keeps-a-commit-that-repairs-an-earlier-one
  (let [{:keys [page-id] :as ctx} (create-file)
        shape-a (uuid/next)
        shape-c (uuid/next)
        _       (update-file ctx {:changes [(th/add-rect-change page-id shape-a)]})
        commit1 (commit (break-parent page-id shape-a))
        commit2 (commit {:type :mod-obj
                         :page-id page-id
                         :id shape-a
                         :operations [{:type :set :attr :parent-id :val uuid/zero}]})
        commit3 (commit (th/add-rect-change page-id shape-c))
        commit4 (commit (break-parent page-id shape-c))
        out     (update-file ctx {:revn 1 :changes-with-metadata [commit1 commit2 commit3 commit4]})
        objects (-> (get-file-data ctx) :pages-index (get page-id) :objects)]

    (t/is (nil? (:error out)))
    (t/is (= [(:commit-id commit4)] (-> out :result :rejected :commit-ids)))
    (t/is (= uuid/zero (get-in objects [shape-a :parent-id])))
    (t/is (= uuid/zero (get-in objects [shape-c :parent-id])))))

(defn- debug-fault
  "A change that asks the backend to fail with `fault` while applied."
  [fault]
  {:type :set-plugin-data
   :object-type :file
   :namespace :penpot-debug
   :key (str "save-fault:" fault)
   :value nil})

(defn- with-debug-faults
  [f]
  (with-redefs [cf/flags (conj cf/flags :debug-save-faults)]
    (f)))

(t/deftest a-debug-fault-rejects-its-commit
  (let [{:keys [page-id] :as ctx} (create-file)
        shape-a (uuid/next)
        commit1 (commit (th/add-rect-change page-id shape-a))
        commit2 (commit (debug-fault "runtime"))
        commit3 (commit (th/add-rect-change page-id (uuid/next)))
        out     (with-debug-faults
                  #(update-file ctx {:changes-with-metadata [commit1 commit2 commit3]}))
        objects (-> (get-file-data ctx) :pages-index (get page-id) :objects)]

    (t/is (nil? (:error out)))
    (t/is (= [(:commit-id commit2) (:commit-id commit3)]
             (-> out :result :rejected :commit-ids)))
    (t/is (= :internal (-> out :result :rejected :error :type)))
    (t/is (contains? objects shape-a))))

(t/deftest a-debug-validation-fault-rejects-like-a-validation-error
  (let [{:keys [page-id] :as ctx} (create-file)
        out (with-debug-faults
              #(update-file ctx {:changes-with-metadata
                                 [(commit (th/add-rect-change page-id (uuid/next)))
                                  (commit (debug-fault "validation"))]}))]
    (t/is (= :validation (-> out :result :rejected :error :type)))))

(t/deftest a-debug-infrastructure-fault-fails-the-whole-request
  (let [{:keys [page-id] :as ctx} (create-file)
        out (with-debug-faults
              #(update-file ctx {:changes-with-metadata
                                 [(commit (th/add-rect-change page-id (uuid/next)))
                                  (commit (debug-fault "sql"))]}))]
    (t/is (instance? java.sql.SQLException (:error out)))
    (t/is (= 0 (:revn (th/db-get :file {:id (:file-id ctx)}))))))

(t/deftest debug-faults-need-the-flag
  (let [{:keys [page-id] :as ctx} (create-file)
        out (update-file ctx {:changes-with-metadata
                              [(commit (th/add-rect-change page-id (uuid/next)))
                               (commit (debug-fault "runtime"))]})]
    (t/is (nil? (:error out)))
    (t/is (nil? (-> out :result :rejected)))
    (t/is (= 1 (:revn (th/db-get :file {:id (:file-id ctx)}))))
    (t/is (nil? (:plugin-data (get-file-data ctx))))))

(t/deftest a-rejection-without-an-error-type-reports-an-internal-error
  (let [{:keys [page-id] :as ctx} (create-file)
        out (with-debug-faults
              #(update-file ctx {:changes-with-metadata
                                 [(commit (th/add-rect-change page-id (uuid/next)))
                                  (commit (debug-fault "ex-info"))]}))
        error (-> out :result :rejected :error)]
    (t/is (= :internal (:type error)))
    (t/is (= :unexpected-error (:code error)))
    (t/is (= "debug save fault" (:hint error)))))

(defn- upload-image
  "Uploads an image to the file `file-id` and returns its media object."
  [profile-id file-id]
  (let [out (th/multipart-command!
             {::th/type :upload-file-media-object
              ::rpc/profile-id profile-id
              :file-id file-id
              :is-local true
              :name "image"
              :content {:filename "sample.jpg"
                        :path (th/tempfile "backend_tests/test_files/sample.jpg")
                        :mtype "image/jpeg"
                        :size 312043}})]
    (t/is (nil? (:error out)))
    (:result out)))

(defn- add-image-rect
  "A rect filled with the image of `mobj`."
  [page-id id mobj]
  (assoc-in (th/add-rect-change page-id id)
            [:obj :fills]
            [{:fill-opacity 1
              :fill-image {:id (:id mobj) :width 100 :height 100 :mtype "image/jpeg"}}]))

(t/deftest a-rejected-commit-adds-no-media-references
  (let [{:keys [profile-id page-id file-id] :as ctx} (create-file)
        other   (th/create-file* 2 {:profile-id profile-id
                                    :project-id (:default-project-id (th/db-get :profile {:id profile-id}))
                                    :is-shared false})
        ;; An image of another file: referencing it copies the media
        ;; object into this file
        mobj    (upload-image profile-id (:id other))
        shape-a (uuid/next)
        shape-b (uuid/next)
        commit1 (commit (th/add-rect-change page-id shape-a))
        commit2 (commit (add-image-rect page-id shape-b mobj)
                        (break-parent page-id shape-a))
        out     (update-file ctx {:changes-with-metadata [commit1 commit2]})]

    (t/is (nil? (:error out)))
    (t/is (= [(:commit-id commit2)] (-> out :result :rejected :commit-ids)))

    (t/testing "the rejected image adds no media object to the file"
      (t/is (empty? (th/db-query :file-media-object {:file-id file-id}))))

    (t/testing "the same image in an accepted commit does"
      (let [out (update-file ctx {:revn 1
                                  :changes-with-metadata [(commit (add-image-rect page-id shape-b mobj))]})]
        (t/is (nil? (:error out)))
        (t/is (nil? (-> out :result :rejected)))
        (t/is (= 1 (count (th/db-query :file-media-object {:file-id file-id}))))))))
