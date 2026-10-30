;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.imports-test
  "Reading a penpot package and importing it as a job."
  (:require
   [app.common.json :as json]
   [app.common.uuid :as uuid]
   [app.main.data.imports :as imp]
   [app.main.data.jobs :as dj]
   [app.main.data.uploads :as uploads]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.http :as http]
   [app.util.i18n :refer [tr]]
   [app.util.websocket :as ws]
   [app.util.zip :as uz]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as hva]
   [frontend-tests.helpers.mock :as mock]
   [promesa.core :as p]))

(def ^:private project-id
  (uuid/next))

(def ^:private uri
  "blob:package")

(def ^:private zip-magic
  (js/Uint8Array. #js [0x50 0x4b 0x03 0x04]))

(defn- entry
  "An entry of the wizard, as the analysis leaves it."
  [& {:keys [id name type]
      :or   {type :binfile-v3}}]
  {:file-id (or id (uuid/next))
   :uri     uri
   :name    (or name "My file")
   :type    type})

(defn- job
  [job-id status & {:as extra}]
  (merge {:id job-id :status status :name "import-binfile"} extra))

(defn- event
  [job-id kind payload]
  {:type   :job-event
   :job-id job-id
   :kind   kind
   :payload payload})

(defn- message
  [payload]
  {:type :message :payload payload})

(defn- slurp-stub
  "The transport of a request that answers with `body`."
  [body]
  (mock/stub (fn [_] (rx/of {:body body}))))

(defn- upload-stub
  "The chunked upload of a package. It is variadic, so it is a plain fn."
  [session-id]
  (fn [_ & _] (rx/of {:session-id session-id})))

(defn- upload-with-progress-stub
  "The chunked upload of a package that reports two chunks before
  emitting the session, so the test sees the upload phase. It is
  variadic, so it is a plain fn."
  [session-id]
  (fn [_ & {:keys [on-progress]}]
    (when (fn? on-progress)
      (on-progress {:current 1 :total 2})
      (on-progress {:current 2 :total 2}))
    (rx/of {:session-id session-id})))

(defn- ws-stub
  [stream]
  (mock/stub (fn [_] stream)))

(defn- fake-server
  "The backend of the import: the creation answers with a fresh job the
  test drives, and the read answers the row of that job."
  [calls rows create-row]
  (mock/stub
   (fn [id params]
     (case id
       :create-import-binfile-job
       (let [job-id (uuid/next)]
         (swap! calls conj {:cmd id :params params :job-id job-id})
         (swap! rows assoc job-id (create-row job-id))
         (rx/of {:id job-id}))

       :get-job
       (do
         (swap! calls conj {:cmd id :params params})
         (rx/of (get @rows (:id params))))

       (rx/of nil)))))

(defn- manifest
  [& files]
  (json/encode {:type  "penpot/export-files"
                :files (mapv (fn [[id name]] {:id id :name name}) files)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; READING A PACKAGE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest ^:async a-package-without-a-manifest-is-rejected
  (let [seen (atom [])]

    (await
     (mock/with-mocks*
       {http/send! (slurp-stub zip-magic)
        uz/get-entry (mock/stub (fn [_ _] (p/resolved nil)))
        uz/close (mock/stub (fn [_] nil))}

       (await (hva/observe (imp/analyze [{:uri uri :name "broken.penpot"}])
                           {:on-next #(swap! seen conj %)}))

       (t/testing "the entry carries the error of the reader"
         (t/is (= 1 (count @seen)))
         (t/is (= :error (:status (first @seen))))
         (t/is (string? (:error (first @seen)))))))))

(t/deftest ^:async a-version-3-package-answers-one-entry-per-file
  (let [first-id  (uuid/next)
        second-id (uuid/next)
        seen      (atom [])]

    (await
     (mock/with-mocks*
       {http/send! (slurp-stub zip-magic)
        uz/get-entry (mock/stub (fn [_ _] (p/resolved ::entry)))
        uz/get-entries (mock/stub (fn [_] (p/resolved #js [])))
        uz/read-as-text (mock/stub (fn [_] (p/resolved (manifest [first-id "First"]
                                                                 [second-id "Second"]))))
        uz/close (mock/stub (fn [_] nil))}

       (await (hva/observe (imp/analyze [{:uri uri :name "package.penpot"}])
                           {:on-next #(swap! seen conj %)}))

       (t/testing "every file of the manifest is an entry of the wizard"
         (t/is (= [first-id second-id] (mapv :file-id @seen)))
         (t/is (= ["First" "Second"] (mapv :name @seen)))
         (t/is (every? #(= :binfile-v3 (:type %)) @seen))
         (t/is (every? #(= :success (:status %)) @seen)))

       (t/testing "all of them share the upload of the file that was dropped"
         (t/is (every? #(= uri (:uri %)) @seen)))))))

(t/deftest ^:async a-version-3-package-sizes-every-file-with-what-it-occupies
  (let [first-id   (uuid/next)
        second-id  (uuid/next)
        object-id  (str (uuid/next))
        seen       (atom [])
        ;; the entries of a container: every file keeps its own under
        ;; `files/<id>`, the binaries of its media shared under `objects/`,
        ;; and the manifest belongs to the container itself
        entries
        #js [#js {:filename "manifest.json"
                  :compressedSize 100}
             #js {:filename (str "files/" first-id ".json")
                  :compressedSize 200}
             #js {:filename (str "files/" first-id "/pages/one.json")
                  :compressedSize 300}
             #js {:filename (str "files/" first-id "/media/one.json")
                  :compressedSize 10
                  :content (str "{\"mediaId\": \"" object-id "\"}")}
             #js {:filename (str "objects/" object-id ".json")
                  :compressedSize 20}
             #js {:filename (str "objects/" object-id ".png")
                  :compressedSize 1000}
             #js {:filename (str "files/" second-id ".json")
                  :compressedSize 400}]]

    (await
     (mock/with-mocks*
       {http/send! (slurp-stub zip-magic)
        ;; the manifest is read through the same reader as the rest of the
        ;; entries, so the fake carries its text and the mock answers what
        ;; every entry holds
        uz/get-entry (mock/stub (fn [_ _]
                                  (p/resolved #js {:content (manifest [first-id "First"]
                                                                      [second-id "Second"])})))
        uz/get-entries (mock/stub (fn [_] (p/resolved entries)))
        uz/read-as-text (mock/stub (fn [entry] (p/resolved (.-content entry))))
        uz/close (mock/stub (fn [_] nil))}

       (await (hva/observe (imp/analyze [{:uri uri :name "package.penpot" :size 10000}])
                           {:on-next #(swap! seen conj %)}))))

    (t/testing "a file occupies its own entries and the objects it references"
      ;; 200 + 300 + 10 of its own, and 20 + 1000 of the object its media
      ;; points at; the 100 of the manifest belong to the container
      (t/is (= [1530 400] (mapv :size @seen))))

    (t/testing "what the container occupies is kept apart"
      (t/is (= [10000 10000] (mapv :container-size @seen))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPORTING A PACKAGE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest ^:async a-package-is-imported-as-its-own-job
  (let [ws-stream  (rx/subject)
        calls      (atom [])
        rows       (atom {})
        seen       (atom [])
        session-id (uuid/next)
        resolution {:done [{:id (str (uuid/next)) :name "Published"}]}
        one        (entry :name "First")
        other      (entry :name "Second")]

    (await
     (mock/with-mocks*
       {st/state (atom {:ws-conn ws-stream})
        http/send! (slurp-stub ::blob)
        uploads/upload-blob-chunked (upload-stub session-id)
        rp/cmd! (fake-server calls rows (fn [job-id] (job job-id "running")))
        ws/get-rcv-stream (ws-stub ws-stream)}

       (let [done   (hva/observe (imp/import-files {:project-id project-id
                                                    :entries    [one other]})
                                 {:on-next #(swap! seen conj %)})
             job-id (:job-id (first @calls))]

         (t/testing "the package is uploaded and imported as one job"
           (t/is (= :create-import-binfile-job (:cmd (first @calls))))
           (t/is (= {:project-id project-id :name "First" :version 3}
                    (:params (:params (first @calls)))))
           (t/is (= session-id (:upload-id (:params (first @calls))))))

         (t/testing "the milestone of the job reaches every entry of the package"
           (rx/push! ws-stream
                     (message (event job-id :progress
                                     {:stage    :pages
                                      :counters {:files {:current 1 :total 2}
                                                 :pages {:current 1 :total 8}}})))
           (await (hva/wait-for #(= 2 (count (filter (comp #{:progress} :status) @seen)))
                                "the milestone"))

           (t/is (= #{(:file-id one) (:file-id other)}
                    (into #{} (comp (filter (comp #{:progress} :status)) (map :file-id)) @seen)))

           (t/is (every? (fn [message]
                           (= {:stage    :pages
                               :counters {:files {:current 1 :total 2}
                                          :pages {:current 1 :total 8}}}
                              (:progress message)))
                         (filter (comp #{:progress} :status) @seen))))

         (t/testing "the entries finish with the resolution of the package"
           (swap! rows assoc job-id
                  (job job-id "completed"
                       :result {:file-ids   (mapv (comp str :file-id) [one other])
                                :resolution resolution
                                :name       "First"
                                :version    3}))
           (rx/push! ws-stream (message (event job-id :end {:outcome "completed"})))
           (await done)

           (t/is (= #{(:file-id one) (:file-id other)}
                    (into #{} (comp (filter (comp #{:finish} :status)) (map :file-id)) @seen)))

           (t/is (= {:libraries-resolution resolution} (last @seen)))))))))

(t/deftest ^:async the-job-reports-queued-then-started-before-progress
  (let [ws-stream  (rx/subject)
        calls      (atom [])
        rows       (atom {})
        seen       (atom [])
        session-id (uuid/next)
        one        (entry :name "First")
        other      (entry :name "Second")]

    (await
     (mock/with-mocks*
       {st/state (atom {:ws-conn ws-stream})
        http/send! (slurp-stub ::blob)
        uploads/upload-blob-chunked (upload-stub session-id)
        rp/cmd! (fake-server calls rows (fn [job-id] (job job-id "running")))
        ws/get-rcv-stream (ws-stub ws-stream)}

       (let [done   (hva/observe (imp/import-files {:project-id project-id
                                                    :entries    [one other]})
                                 {:on-next #(swap! seen conj %)})
             job-id (:job-id (first @calls))]

         (t/testing "every entry is queued while the job waits for a worker"
           (await (hva/wait-for #(= 2 (count (filter (comp #{:queued} :status) @seen)))
                                "the queued messages"))
           (t/is (= #{(:file-id one) (:file-id other)}
                    (into #{} (comp (filter (comp #{:queued} :status)) (map :file-id)) @seen))))

         (t/testing "a started job moves the entries to progress without a milestone"
           (rx/push! ws-stream (message (event job-id :start {:attempt 1})))
           (await (hva/wait-for #(= 2 (count (filter (comp #{:started} :status) @seen)))
                                "the started messages"))
           (t/is (= #{(:file-id one) (:file-id other)}
                    (into #{} (comp (filter (comp #{:started} :status)) (map :file-id)) @seen))))

         (t/testing "the milestone still arrives on top"
           (rx/push! ws-stream
                     (message (event job-id :progress
                                     {:stage    :pages
                                      :counters {:pages {:current 1 :total 8}}})))
           (await (hva/wait-for #(some :progress (filter (comp #{:progress} :status) @seen))
                                "the milestone"))
           (swap! rows assoc job-id
                  (job job-id "completed"
                       :result {:file-ids   (mapv (comp str :file-id) [one other])
                                :resolution {}
                                :name       "First"
                                :version    3}))
           (rx/push! ws-stream (message (event job-id :end {:outcome "completed"})))
           (await done)))))))

(t/deftest ^:async a-failed-job-fails-its-entries
  (let [ws-stream (rx/subject)
        calls     (atom [])
        rows      (atom {})
        seen      (atom [])
        target    (entry :name "Only")]

    (await
     (mock/with-mocks*
       {st/state (atom {:ws-conn ws-stream})
        http/send! (slurp-stub ::blob)
        uploads/upload-blob-chunked (upload-stub (uuid/next))
        rp/cmd! (fake-server calls rows (fn [job-id] (job job-id "running")))
        ws/get-rcv-stream (ws-stub ws-stream)}

       (let [done   (hva/observe (imp/import-files {:project-id project-id
                                                    :entries    [target]})
                                 {:on-next #(swap! seen conj %)})
             job-id (:job-id (first @calls))]

         (swap! rows assoc job-id
                (job job-id "failed" :error {:type :internal
                                             :code :boom
                                             :hint "The package is broken"}))
         (rx/push! ws-stream (message (event job-id :end {:outcome "failed"})))
         (await done)

         (t/testing "the entry fails with the message of the job"
           (t/is (= [{:status  :queued
                      :file-id (:file-id target)}
                     {:status  :error
                      :file-id (:file-id target)
                      :error   "The package is broken"}
                     {:libraries-resolution {}}]
                    @seen))))))))

(t/deftest ^:async a-package-that-cannot-be-created-fails-its-entries
  (let [ws-stream (rx/subject)
        seen      (atom [])
        target    (entry :name "Only")]

    (await
     (mock/with-mocks*
       {st/state (atom {:ws-conn ws-stream})
        http/send! (slurp-stub ::blob)
        uploads/upload-blob-chunked (upload-stub (uuid/next))
        rp/cmd! (mock/stub (fn [_ _]
                             (rx/throw (ex-info "cannot create the job"
                                                {:type :validation
                                                 :code :boom
                                                 :hint "Not now"}))))
        ws/get-rcv-stream (ws-stub ws-stream)}

       (await (hva/observe (imp/import-files {:project-id project-id
                                              :entries    [target]})
                           {:on-next #(swap! seen conj %)}))

       (t/testing "the failure reaches every entry of the package"
         (t/is (= [{:status  :error
                    :file-id (:file-id target)
                    :error   "Not now"}
                   {:libraries-resolution {}}]
                  @seen)))))))

(t/deftest ^:async a-job-that-already-ended-does-not-leave-the-entries-spinning
  (let [ws-stream (rx/subject)
        calls     (atom [])
        rows      (atom {})
        seen      (atom [])
        target    (entry :name "Only")]

    (await
     (mock/with-mocks*
       {st/state (atom {:ws-conn ws-stream})
        http/send! (slurp-stub ::blob)
        uploads/upload-blob-chunked (upload-stub (uuid/next))
        ;; the job is over before the client reads it for the first time
        rp/cmd! (fake-server calls rows
                             (fn [job-id] (job job-id "completed" :result {:resolution {}})))
        ws/get-rcv-stream (ws-stub ws-stream)}

       (await (hva/observe (imp/import-files {:project-id project-id
                                              :entries    [target]})
                           {:on-next #(swap! seen conj %)}))

       (t/testing "the entry finishes without waiting for an event"
         (t/is (= [{:status  :queued
                    :file-id (:file-id target)}
                   {:status  :finish
                    :file-id (:file-id target)}
                   {:libraries-resolution {}}]
                  @seen)))))))

(t/deftest ^:async the-upload-reports-chunks-before-the-job-runs
  (let [ws-stream  (rx/subject)
        calls      (atom [])
        rows       (atom {})
        seen       (atom [])
        session-id (uuid/next)
        target     (entry :name "Only")]

    (await
     (mock/with-mocks*
       {st/state (atom {:ws-conn ws-stream})
        http/send! (slurp-stub ::blob)
        uploads/upload-blob-chunked (upload-with-progress-stub session-id)
        rp/cmd! (fake-server calls rows (fn [job-id] (job job-id "running")))
        ws/get-rcv-stream (ws-stub ws-stream)}

       (let [done   (hva/observe (imp/import-files {:project-id project-id
                                                    :entries    [target]})
                                 {:on-next #(swap! seen conj %)})
             job-id (:job-id (first @calls))]

         (await (hva/wait-for #(>= (count (filter (comp #{:progress} :status) @seen)) 2)
                              "the upload progress"))

         (t/testing "every uploaded chunk reaches the entry as an upload milestone"
           (t/is (= [{:status   :progress
                      :file-id  (:file-id target)
                      :progress {:stage    :upload
                                 :counters {:upload {:current 1 :total 2}}}}
                     {:status   :progress
                      :file-id  (:file-id target)
                      :progress {:stage    :upload
                                 :counters {:upload {:current 2 :total 2}}}}]
                    (filterv (comp #{:progress} :status) @seen))))

         (t/testing "the entry still finishes with the job"
           (swap! rows assoc job-id (job job-id "completed" :result {:resolution {}}))
           (rx/push! ws-stream (message (event job-id :end {:outcome "completed"})))
           (await done)

           (t/is (= {:libraries-resolution {}} (last @seen)))
           (t/is (= :finish (:status (nth @seen 3))))))))))

(t/deftest ^:async a-cancelled-job-closes-its-entries-as-cancelled
  (let [ws-stream (rx/subject)
        calls     (atom [])
        rows      (atom {})
        seen      (atom [])
        target    (entry :name "Only")]

    (await
     (mock/with-mocks*
       {st/state (atom {:ws-conn ws-stream})
        http/send! (slurp-stub ::blob)
        uploads/upload-blob-chunked (upload-stub (uuid/next))
        rp/cmd! (fake-server calls rows (fn [job-id] (job job-id "running")))
        ws/get-rcv-stream (ws-stub ws-stream)}

       (let [done   (hva/observe (imp/import-files {:project-id project-id
                                                    :entries    [target]})
                                 {:on-next #(swap! seen conj %)})
             job-id (:job-id (first @calls))]

         (t/testing "the cancelled ending terminates the stream"
           (swap! rows assoc job-id (job job-id "cancelled"))
           (rx/push! ws-stream (message (event job-id :end {:outcome "cancelled"})))
           (await done)

           (t/testing "the cancelled ending closes the entries and terminates the stream"
             (t/is (= [{:status  :queued
                        :file-id (:file-id target)}
                       {:status  :error
                        :file-id (:file-id target)
                        :error   (tr "jobs.import-cancelled")}
                       {:libraries-resolution {}}]
                      @seen)))))))))

(t/deftest ^:async a-cancelled-job-closes-in-progress-entries-as-cancelled
  (let [ws-stream (rx/subject)
        calls     (atom [])
        rows      (atom {})
        seen      (atom [])
        target    (entry :name "Only")]

    (await
     (mock/with-mocks*
       {st/state (atom {:ws-conn ws-stream})
        http/send! (slurp-stub ::blob)
        uploads/upload-blob-chunked (upload-stub (uuid/next))
        rp/cmd! (fake-server calls rows (fn [job-id] (job job-id "running")))
        ws/get-rcv-stream (ws-stub ws-stream)}

       (let [done   (hva/observe (imp/import-files {:project-id project-id
                                                    :entries    [target]})
                                 {:on-next #(swap! seen conj %)})
             job-id (:job-id (first @calls))]

         ;; drive the entries to progress first, so the cancel
         ;; interrupts a run in course instead of a queued job
         (rx/push! ws-stream (message (event job-id :progress {:stage :manifest})))
         (await (hva/wait-for #(some (fn [message] (= :progress (:status message))) @seen)
                              "the progress"))

         (t/testing "an external cancel closes every entry as cancelled"
           (swap! rows assoc job-id (job job-id "cancelled"))
           (rx/push! ws-stream (message (event job-id :end {:outcome "cancelled"})))
           (await done)
           (t/is (= [{:status  :queued
                      :file-id (:file-id target)}
                     {:status   :progress
                      :file-id  (:file-id target)
                      :progress {:stage :manifest}}
                     {:status  :error
                      :file-id (:file-id target)
                      :error   (tr "jobs.import-cancelled")}
                     {:libraries-resolution {}}]
                    @seen))))))))

(t/deftest ^:async the-created-job-id-reaches-the-caller
  (let [ws-stream (rx/subject)
        calls     (atom [])
        rows      (atom {})
        seen-jobs (atom [])
        target    (entry :name "Only")]

    (await
     (mock/with-mocks*
       {st/state (atom {:ws-conn ws-stream})
        http/send! (slurp-stub ::blob)
        uploads/upload-blob-chunked (upload-stub (uuid/next))
        rp/cmd! (fake-server calls rows (fn [job-id] (job job-id "running")))
        ws/get-rcv-stream (ws-stub ws-stream)}

       (let [done   (hva/observe (imp/import-files {:project-id project-id
                                                    :entries    [target]
                                                    :on-job     #(swap! seen-jobs conj %)})
                                 {:on-next (constantly nil)})
             job-id (:job-id (first @calls))]

         (t/testing "the caller learns the id of the package job"
           (t/is (= [job-id] @seen-jobs)))

         (swap! rows assoc job-id (job job-id "completed" :result {:resolution {}}))
         (rx/push! ws-stream (message (event job-id :end {:outcome "completed"})))
         (await done))))))

(t/deftest format-file-size-writes-human-sizes
  (t/testing "unknown sizes show nothing"
    (t/is (nil? (imp/format-file-size nil)))
    (t/is (nil? (imp/format-file-size -1))))

  (t/testing "bytes, kilobytes, megabytes and gigabytes"
    (t/is (= "0B" (imp/format-file-size 0)))
    (t/is (= "512B" (imp/format-file-size 512)))
    (t/is (= "1KB" (imp/format-file-size 1024)))
    (t/is (= "1.5KB" (imp/format-file-size 1536)))
    (t/is (= "512KB" (imp/format-file-size (* 512 1024))))
    (t/is (= "234MB" (imp/format-file-size (* 234 1024 1024))))
    (t/is (= "1.2GB" (imp/format-file-size (* 1.2 1024 1024 1024))))))

(defn- uploading
  "A file of the wizard while the container it came in is uploaded: the
  chunks sent out of the chunks the container has."
  [uri size current total]
  {:file-id        (uuid/next)
   :uri            uri
   :size           (* 1024 1024)
   :container-size size
   :type           :binfile-v3
   :status         :import-progress
   :progress       {:stage :upload :counters {:upload {:current current :total total}}}})

(t/deftest upload-pct-weighs-every-container-by-its-size
  (let [small (* 100 1024 1024)
        big   (* 300 1024 1024)]

    (t/testing "the bytes sent of every container over the bytes of all of them"
      ;; 100MB of 100MB and half of 300MB: 250 of 400, not the average of
      ;; 100% and 50%
      (t/is (= 63 (imp/upload-pct [(uploading "a" small 4 4)
                                   (uploading "b" big 1 2)]))))

    (t/testing "a container that has not started counts as nothing sent"
      (t/is (= 25 (imp/upload-pct [(uploading "a" small 4 4)
                                   {:uri "b" :container-size big :type :binfile-v3
                                    :status :import-ready}]))))

    (t/testing "the files of one container are one container"
      (t/is (= 100 (imp/upload-pct [(uploading "a" big 1 1)
                                    (uploading "a" big 1 1)]))))

    (t/testing "a container with no size leaves the bar indeterminate"
      (t/is (nil? (imp/upload-pct [])))
      (t/is (nil? (imp/upload-pct [{:uri "a" :type :binfile-v3 :status :import-ready}]))))))

(t/deftest a-container-past-the-upload-counts-whole
  ;; the job of a container is created with its upload as its input, so the
  ;; moment the entries leave the upload stage the container is sent
  (t/is (= 100 (imp/upload-pct [{:uri "a" :name "a.penpot" :container-size (* 100 1024 1024)
                                 :type :binfile-v1 :status :import-queued}
                                {:uri "b" :name "b.penpot" :container-size (* 300 1024 1024)
                                 :type :binfile-v1 :status :import-success}]))))

(t/deftest upload-done?-tells-when-the-chunks-of-a-container-are-sent
  (t/testing "the last chunk closes the upload"
    (t/is (true? (imp/upload-done? (uploading "a" 100 3 3))))
    (t/is (false? (imp/upload-done? (uploading "a" 100 2 3)))))

  (t/testing "a file that is not uploading yet is not done"
    (t/is (false? (imp/upload-done? {:status :import-ready})))
    (t/is (false? (imp/upload-done? {})))))

(t/deftest imported-count-counts-the-files-the-job-left-behind
  (let [file (fn [uri status progress]
               (cond-> {:uri uri :type :binfile-v3 :status status}
                 (some? progress) (assoc :progress progress)))]

    (t/testing "a container that finished counts all of its files"
      (t/is (= 2 (imp/imported-count [(file "a" :import-success nil)
                                      (file "a" :import-success nil)]))))

    (t/testing "a running container counts the files it left behind"
      ;; its milestone says it is working on the second file of three
      (t/is (= 1 (imp/imported-count [(file "a" :import-progress
                                            {:stage    :pages
                                             :counters {:files {:current 2 :total 3}
                                                        :pages {:current 1 :total 5}}})]))))

    (t/testing "a container that has not started has nothing done"
      (t/is (= 0 (imp/imported-count [(file "a" :import-queued nil)])))
      (t/is (= 0 (imp/imported-count [(file "a" :import-progress
                                            {:stage :pages :counters {}})]))))

    (t/testing "the files of every container add up"
      (t/is (= 3 (imp/imported-count [(file "a" :import-success nil)
                                      (file "a" :import-success nil)
                                      (file "b" :import-progress
                                            {:stage    :pages
                                             :counters {:files {:current 2 :total 4}}})]))))

    (t/testing "no files, nothing done"
      (t/is (= 0 (imp/imported-count []))))))

(t/deftest waiting-slot?-is-true-until-a-worker-picks-the-jobs-up
  (t/testing "every file queued is a wizard waiting for a slot"
    (t/is (true? (imp/waiting-slot? [{:status :import-queued}
                                     {:status :import-queued}]))))

  (t/testing "a job in course leaves the wait behind"
    (t/is (false? (imp/waiting-slot? [{:status :import-queued}
                                      {:status :import-progress}]))))

  (t/testing "a file already imported is not waiting either"
    (t/is (false? (imp/waiting-slot? [{:status :import-success}]))))

  (t/testing "no files, no wait"
    (t/is (false? (imp/waiting-slot? [])))))

(t/deftest awaiting-analysis-hides-the-rows-while-checking
  (t/testing "only a clean analysis shows the placeholder"
    (t/is (true? (imp/awaiting-analysis? :analyze false)))
    (t/is (false? (imp/awaiting-analysis? :analyze true))))
  (t/testing "any later stage shows the rows"
    (t/is (false? (imp/awaiting-analysis? :import-ready false)))
    (t/is (false? (imp/awaiting-analysis? :import-progress false)))
    (t/is (false? (imp/awaiting-analysis? :import-success false)))))

(t/deftest ready-to-import?-waits-for-the-whole-analysis
  (t/testing "every file found and ready, with the analysis over"
    (t/is (true? (imp/ready-to-import? [{:status :import-ready}
                                        {:status :import-ready}]
                                       true))))

  (t/testing "a file that has not arrived keeps the wizard checking"
    ;; the files of a container are only known once its manifest is read, so
    ;; offering the import step here would leave the missing one behind
    (t/is (false? (imp/ready-to-import? [{:status :import-ready}] false))))

  (t/testing "a file that cannot be imported is not ready either"
    (t/is (false? (imp/ready-to-import? [{:status :import-ready}
                                         {:status :analyze-error}]
                                        true))))

  (t/testing "no files at all is not ready"
    (t/is (false? (imp/ready-to-import? [] true)))))

(t/deftest import-step-follows-the-wizard-status
  (t/testing "analysis paints the check step"
    (t/is (= :check (imp/import-step :analyze [])))
    (t/is (= :check (imp/import-step :import-ready []))))

  (t/testing "an upload milestone paints the upload step"
    (let [entries [{:file-id (uuid/next)
                    :status :import-progress
                    :progress {:stage :upload :counters {:upload {:current 1 :total 2}}}}]]
      (t/is (= :upload (imp/import-step :import-progress entries)))))

  (t/testing "a file that has not reported anything yet is still uploading"
    ;; the wizard is uploading from the click, before the first chunk arrives
    (let [entries [{:file-id (uuid/next) :status :import-ready}
                   {:file-id (uuid/next)
                    :status :import-progress
                    :progress {:stage :upload :counters {:upload {:current 1 :total 2}}}}]]
      (t/is (= :upload (imp/import-step :import-progress entries)))))

  (t/testing "a finished upload with its job still to come is uploading"
    (let [entries [{:file-id (uuid/next)
                    :status :import-progress
                    :progress {:stage :upload :counters {:upload {:current 2 :total 2}}}}
                   {:file-id (uuid/next)
                    :status :import-progress
                    :progress {:stage :upload :counters {:upload {:current 1 :total 4}}}}]]
      (t/is (= :upload (imp/import-step :import-progress entries)))))

  (t/testing "queued or job milestones paint the import step"
    (let [queued  [{:file-id (uuid/next) :status :import-queued}]
          started [{:file-id (uuid/next) :status :import-progress}]
          pages   [{:file-id (uuid/next)
                    :status :import-progress
                    :progress {:stage :pages :counters {:pages {:current 1 :total 8}}}}]]
      (t/is (= :import (imp/import-step :import-progress queued)))
      (t/is (= :import (imp/import-step :import-progress started)))
      (t/is (= :import (imp/import-step :import-progress pages)))))

  (t/testing "terminal and resolution stages paint the import step"
    (t/is (= :import (imp/import-step :import-success [])))
    (t/is (= :import (imp/import-step :import-error [])))
    (t/is (= :import (imp/import-step :library-resolution [])))
    (t/is (= :import (imp/import-step :library-summary [])))))

(t/deftest ^:async cancel-job-asks-the-server-and-ignores-failures
  (let [calls  (atom [])
        job-id (uuid/next)]

    (t/testing "the cancel command reaches the server"
      (await
       (mock/with-mocks*
         {rp/cmd! (mock/stub (fn [id params]
                               (swap! calls conj {:cmd id :params params})
                               (rx/of {:id job-id :status "cancelled"})))}
         (dj/cancel-job job-id)
         (await (hva/settle))
         (t/is (= [{:cmd :cancel-job :params {:id job-id}}] @calls)))))

    (t/testing "a failure is swallowed: the job may have just finished"
      (await
       (mock/with-mocks*
         {rp/cmd! (mock/stub (fn [_ _]
                               (rx/throw (ex-info "gone" {}))))}
         (dj/cancel-job job-id)
         (await (hva/settle)))))))
