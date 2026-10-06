;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.exports-files-test
  "Exporting files as jobs: one file, one job and one answer, with the
  progress of the job reported while it runs."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.exports.files :as fexp]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.websocket :as ws]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as hva]
   [frontend-tests.helpers.mock :as mock]))

(defn- file
  [name]
  {:id         (uuid/next)
   :name       name
   :project-id (uuid/next)
   :is-shared  false})

(defn- job
  [job-id status & {:as extra}]
  (merge {:id job-id :status status :name "export-binfile"} extra))

(defn- event
  [job-id kind payload]
  {:type   :job-event
   :job-id job-id
   :kind   kind
   :payload payload})

(defn- message
  [payload]
  {:type :message :payload payload})

(defn- fake-server
  "The backend of the export: the creation answers with a fresh job the
  test drives, and the read answers the row of that job."
  [calls rows]
  (mock/stub
   (fn [id params]
     (case id
       :create-binfile-export-job
       (let [job-id (uuid/next)]
         (swap! calls conj {:cmd id :params params :job-id job-id})
         (swap! rows assoc job-id (job job-id "running"))
         (rx/of {:id job-id}))

       :get-job
       (do
         (swap! calls conj {:cmd id :params params})
         (rx/of (get @rows (:id params))))

       (rx/of nil)))))

(def ^:private created?
  (comp #{:create-binfile-export-job} :cmd))

(defn- answered?
  "True when the file already has its answer: the artifact or an error."
  [results file-id]
  (some (fn [result]
          (and (= file-id (:file-id result))
               (or (:uri result) (:error result))))
        results))

(t/deftest ^:async a-file-is-exported-as-its-own-job
  (let [ws-stream (rx/subject)
        calls     (atom [])
        rows      (atom {})
        seen      (atom [])
        target    (file "My file")]

    (await
     (mock/with-mocks*
       {st/state (atom {:ws-conn ws-stream})
        rp/cmd! (fake-server calls rows)
        ws/get-rcv-stream (mock/stub (fn [_] ws-stream))}

       (let [done   (hva/observe (fexp/export-files :files [target] :type :detach-libraries)
                                 {:on-next #(swap! seen conj %)})
             job-id (:job-id (first @calls))]

         (t/testing "the file is exported by a job of its own"
           (t/is (= :create-binfile-export-job (:cmd (first @calls))))
           (t/is (= {:name   :export-binfile
                     :params {:file-ids    #{(:id target)}
                              :export-type :detach-libraries}}
                    (:params (first @calls)))))

         (t/testing "the milestone of the job is reported while it runs"
           (rx/push! ws-stream
                     (message (event job-id :progress
                                     {:stage    :pages
                                      :counters {:files {:current 1 :total 1}
                                                 :pages {:current 1 :total 2}}})))
           (await (hva/wait-for #(some :progress @seen) "the milestone"))

           (t/is (= {:file-id  (:id target)
                     :progress {:stage    :pages
                                :counters {:files {:current 1 :total 1}
                                           :pages {:current 1 :total 2}}}}
                    (last @seen))))

         (t/testing "the artifact of the job answers when it is over"
           (swap! rows assoc job-id
                  (job job-id "completed"
                       :result {:resource-uri "http://assets/export"
                                :filename     "export.penpot"
                                :mtype        "application/zip"
                                :size         120}))
           (rx/push! ws-stream (message (event job-id :end {:outcome "completed"})))
           (await done)

           (t/is (= {:file-id  (:id target)
                     :uri      "http://assets/export"
                     :filename "My file"}
                    (last @seen)))))))))

(t/deftest ^:async a-failure-fails-only-its-own-file
  (let [ws-stream (rx/subject)
        calls     (atom [])
        rows      (atom {})
        seen      (atom [])
        one       (file "First")
        other     (file "Second")]

    (await
     (mock/with-mocks*
       {st/state (atom {:ws-conn ws-stream})
        rp/cmd! (fake-server calls rows)
        ws/get-rcv-stream (mock/stub (fn [_] ws-stream))}

       (let [done (hva/observe (fexp/export-files :files [one other] :type :detach-libraries)
                               {:on-next #(swap! seen conj %)})]

         (t/testing "the first file answers with its artifact"
           (let [job-id (:job-id (first (filter created? @calls)))]
             (swap! rows assoc job-id
                    (job job-id "completed" :result {:resource-uri "http://assets/first"}))
             (rx/push! ws-stream (message (event job-id :end {:outcome "completed"})))
             (await (hva/wait-for #(answered? @seen (:id one)) "the first answer"))))

         (t/testing "and the second one fails on its own"
           (await (hva/wait-for #(= 2 (count (filter created? @calls))) "the second job"))
           (let [job-id (:job-id (second (filter created? @calls)))]
             (swap! rows assoc job-id
                    (job job-id "failed" :error {:type :internal
                                                 :code :boom
                                                 :hint "boom"}))
             (rx/push! ws-stream (message (event job-id :end {:outcome "failed"})))
             (await done)))

         (t/testing "the failure does not touch the file that worked"
           (t/is (= {:file-id  (:id one)
                     :uri      "http://assets/first"
                     :filename "First"}
                    (first (filter #(and (= (:id one) (:file-id %))
                                         (:uri %))
                                   @seen)))))

         (t/is (= {:file-id (:id other)
                   :error   {:type :internal :code :boom :hint "boom"}}
                  (last @seen))))))))

(t/deftest ^:async the-created-jobs-reach-the-caller-tagged-with-their-file
  (let [ws-stream (rx/subject)
        calls     (atom [])
        rows      (atom {})
        seen-jobs (atom [])
        one       (file "First")
        other     (file "Second")]

    (await
     (mock/with-mocks*
       {st/state (atom {:ws-conn ws-stream})
        rp/cmd! (fake-server calls rows)
        ws/get-rcv-stream (mock/stub (fn [_] ws-stream))}

       (let [done (hva/observe (fexp/export-files :files [one other]
                                                  :type :detach-libraries
                                                  :on-job #(swap! seen-jobs conj %))
                               {:on-next (constantly nil)})]

         (await (hva/wait-for #(= 1 (count (filter created? @calls))) "the first job"))

         (t/testing "the server recorded the creation with its job id"
           (let [creates (filterv created? @calls)]
             (t/is (= 1 (count creates)))
             (t/is (= :create-binfile-export-job (:cmd (first creates))))
             (t/is (uuid? (:job-id (first creates))))))

         (t/testing "the caller learns that same job tagged with its file"
           (t/is (= [{:job-id (:job-id (first (filterv created? @calls)))
                     :file-id (:id one)}]
                    @seen-jobs)))

         ;; files export one after the other: the second job is only
         ;; created once the first one is over
         (let [{job-1 :job-id} (first @seen-jobs)]
           (swap! rows assoc job-1 (job job-1 "completed" :result {:resource-uri "http://assets/first"}))
           (rx/push! ws-stream (message (event job-1 :end {:outcome "completed"}))))

         (await (hva/wait-for #(= 2 (count (filter created? @calls))) "the second job"))

         (t/testing "the caller learns the second job too, tagged and in order"
           (t/is (= 2 (count @seen-jobs)))
           (t/is (= (:job-id (second (filterv created? @calls))) (:job-id (second @seen-jobs))))
            (t/is (= (:id other) (:file-id (second @seen-jobs)))))

         (let [{job-2 :job-id} (second @seen-jobs)]
           (swap! rows assoc job-2 (job job-2 "completed" :result {:resource-uri "http://assets/second"}))
           (rx/push! ws-stream (message (event job-2 :end {:outcome "completed"})))
           (await done)))))))

(t/deftest ^:async the-file-is-queued-until-a-worker-starts-it
  (let [ws-stream (rx/subject)
        calls     (atom [])
        rows      (atom {})
        seen      (atom [])
        target    (file "My file")]

    (await
     (mock/with-mocks*
       {st/state (atom {:ws-conn ws-stream})
        rp/cmd! (fake-server calls rows)
        ws/get-rcv-stream (mock/stub (fn [_] ws-stream))}

       (let [done   (hva/observe (fexp/export-files :files [target] :type :detach-libraries)
                                 {:on-next #(swap! seen conj %)})
             job-id (:job-id (first @calls))]

         (t/testing "the file is queued while the job waits for a worker"
           (await (hva/wait-for #(some :queued @seen) "the queued message"))
           (t/is (= {:file-id (:id target) :queued true} (last @seen))))

         (t/testing "a started job clears the queue without a milestone yet"
           (rx/push! ws-stream (message (event job-id :start {:attempt 1})))
           (await (hva/wait-for #(some :started @seen) "the started message"))
           (t/is (= {:file-id (:id target) :started true} (last @seen)))
           (swap! rows assoc job-id
                  (job job-id "completed"
                       :result {:resource-uri "http://assets/export"}))
           (rx/push! ws-stream (message (event job-id :end {:outcome "completed"})))
           (await done)))))))

(t/deftest ^:async a-cancelled-job-marks-the-file-cancelled-without-artifact
  (let [ws-stream (rx/subject)
        calls     (atom [])
        rows      (atom {})
        seen      (atom [])
        target    (file "My file")]

    (await
     (mock/with-mocks*
       {st/state (atom {:ws-conn ws-stream})
        rp/cmd! (fake-server calls rows)
        ws/get-rcv-stream (mock/stub (fn [_] ws-stream))}

       (let [done   (hva/observe (fexp/export-files :files [target] :type :detach-libraries)
                                 {:on-next #(swap! seen conj %)})
             job-id (:job-id (first @calls))]

         (t/testing "the cancelled outcome is an explicit message, never an artifact"
           (swap! rows assoc job-id (job job-id "cancelled"))
           (rx/push! ws-stream (message (event job-id :end {:outcome "cancelled"})))
           (await done)
           (t/is (= {:file-id (:id target) :cancelled true} (last @seen)))
           (t/is (not-any? :uri @seen))))))))
