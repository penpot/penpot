;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.plugins.file-test
  (:require
   [app.main.data.exports.files :as exports.files]
   [app.main.store :as st]
   [app.main.worker :as mw]
   [app.plugins.file :as file]
   [app.plugins.system-events :as se]
   [app.plugins.utils :as u]
   [app.util.http :as http]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as a]
   [frontend-tests.helpers.mock :as mock]))

(t/deftest file-version-created-at-returns-stored-date
  (let [created-at (js/Date.)
        version    (file/file-version-proxy
                    "00000000-0000-0000-0000-000000000000"
                    (random-uuid)
                    {}
                    {:id (random-uuid)
                     :label "Version"
                     :created-at created-at})]
    (t/is (identical? created-at (.-createdAt version)))))

(def ^:private export-uri "https://example.invalid/export-fixture")
(def ^:private export-plugin-id "file-export-regression-plugin")
(def ^:private exported-bytes [80 75 3 4 5 6])

(defn- make-response
  ([] (make-response 200 exported-bytes))
  ([status bytes]
   {:status status
    :body (.-buffer (js/Uint8Array. (into-array bytes)))}))

(defn- ^:async get-export-result
  "Keeps each fixture's mocks installed until the export promise settles."
  [format type {:keys [messages responses service-error http-error]
                :or {messages [{:uri export-uri}]
                     responses [(make-response)]}}]
  (let [id              (random-uuid)
        file-data       {:id id
                         :name "Export regression fixture"
                         :project-id (random-uuid)
                         :is-shared false}
        export-response (rx/subject)
        http-response   (rx/subject)
        service-calls   (atom [])
        http-calls      (atom [])
        worker-requests (atom [])
        emitted         (atom [])
        outcome         (atom nil)]
    (await
     (mock/with-mocks*
       {u/locate-file (fn [_] file-data)
        exports.files/export-files
        (fn [& {:keys [files type]}]
          (swap! service-calls conj {:files files :type type})
          (->> (if service-error
                 (rx/throw service-error)
                 export-response)
               (rx/observe-on :async)))
        ;; The removed worker handler replies nil, which reproduces #11554.
        mw/ask-many!
        (mock/stub
         (fn [request]
           (swap! worker-requests conj request)
           (->> (rx/of nil)
                (rx/observe-on :async))))
        http/send!
        (fn [request]
          (swap! http-calls conj request)
          (->> (if http-error
                 (rx/throw http-error)
                 http-response)
               (rx/observe-on :async)))
        se/event
        (fn [plugin-id name & {:as props}]
          {:plugin-id plugin-id :name name :props props})
        st/emit! (mock/stub (fn [event] (swap! emitted conj event)))}
       (let [promise (.export ^js (file/file-proxy export-plugin-id id)
                              format type)]
         (.then promise
                (fn [value] (reset! outcome {:status :resolved :value value}))
                (fn [cause] (reset! outcome {:status :rejected :cause cause})))
         (doseq [message messages]
           (rx/push! export-response message))
         (rx/end! export-response)
         (await (a/wait-for #(or (some? @outcome) (seq @http-calls))
                            "file export to request its artifact or reject"
                            {:max-ticks 200}))
         (when (seq @http-calls)
           (doseq [response responses]
             (rx/push! http-response response))
           (rx/end! http-response))
         (await (a/wait-for #(some? @outcome)
                            "file export promise to settle"
                            {:max-ticks 200}))
         {:outcome (or @outcome {:status :pending})
          :file file-data
          :service-calls @service-calls
          :http-calls @http-calls
          :worker-requests @worker-requests
          :events @emitted})))))

(defn- rejected-with?
  [outcome pattern]
  (and (= :rejected (:status outcome))
       (boolean (re-find pattern (str (:cause outcome))))))

(defn- export-event
  [type]
  {:plugin-id export-plugin-id
   :name "export-binary-files"
   :props {:format :binfile-v3 :type type}})

(t/deftest ^:async file-export-returns-bytes-after-queued-and-progress-messages
  (let [{:keys [outcome file service-calls http-calls worker-requests events]}
        (await (get-export-result
                "penpot" "all"
                {:messages [{:queued true}
                            {:progress {:section :file}}
                            {:uri export-uri}]}))]
    (t/is (= :resolved (:status outcome)) (str outcome))
    (when (= :resolved (:status outcome))
      (t/is (instance? js/Uint8Array (:value outcome)))
      (t/is (= exported-bytes (vec (array-seq (:value outcome))))))
    (t/is (= [{:files [file] :type :include-libraries}] service-calls))
    (t/is (= [{:method :get
               :uri export-uri
               :mode :no-cors
               :response-type :buffer}]
             http-calls))
    (t/is (= [(export-event :include-libraries)] events))
    (t/is (empty? worker-requests))))

(t/deftest ^:async file-export-preserves-old-new-and-default-library-modes
  (doseq [[format type expected]
          [["penpot" "all" :include-libraries]
           ["penpot" "merge" :merge-libraries]
           ["penpot" "detach" :detach-libraries]
           ["penpot" "include-libraries" :include-libraries]
           ["penpot" "merge-libraries" :merge-libraries]
           ["penpot" "detach-libraries" :detach-libraries]
           ["penpot" "link-later" :link-later]
           ["penpot" nil :include-libraries]
           [nil nil :include-libraries]]]
    (let [{:keys [outcome service-calls events]}
          (await (get-export-result format type {}))
          context (str "format=" format ", type=" type ", outcome=" outcome)]
      (t/is (= :resolved (:status outcome)) context)
      (when (= :resolved (:status outcome))
        (t/is (= exported-bytes (vec (array-seq (:value outcome)))) context))
      (t/is (= expected (:type (first service-calls))) context)
      (t/is (= [(export-event expected)] events) context))))

(t/deftest ^:async file-export-rejects-failed-cancelled-and-empty-results
  (doseq [[label options pattern]
          [["job error"
            {:messages [{:error {:code :fixture-error}}]}
            #"cannot export file"]
           ["job error without details"
            {:messages [{:error nil}]}
            #"cannot export file"]
           ["cancelled job"
            {:messages [{:cancelled true}]}
            #"file export cancelled"]
           ["nil result"
            {:messages [nil]}
            #"unexpected export result"]
           ["unrecognized result"
            {:messages [{}]}
            #"unexpected export result"]
           ["empty stream"
            {:messages []}
            #"export completed without a file"]
           ["only queue and progress"
            {:messages [{:queued true} {:progress {:section :file}}]}
            #"export completed without a file"]
           ["service failure"
            {:service-error (js/Error. "fixture service failure")}
            #"fixture service failure"]]]
    (let [{:keys [outcome http-calls events]}
          (await (get-export-result "penpot" "all" options))]
      (t/is (rejected-with? outcome pattern) (str label ": " outcome))
      (t/is (empty? http-calls) label)
      (t/is (empty? events) label))))

(t/deftest ^:async file-export-rejects-failed-or-empty-artifact-downloads
  (doseq [[label options pattern]
          [["empty HTTP stream"
            {:responses []}
            #"export completed without a file"]
           ["HTTP failure"
            {:responses [(make-response 503 [1])]}
            #"cannot download exported file"]
           ["empty archive"
            {:responses [(make-response 200 [])]}
            #"empty exported file"]
           ["transport failure"
            {:http-error (js/Error. "fixture fetch failure")}
            #"fixture fetch failure"]]]
    (let [{:keys [outcome http-calls events]}
          (await (get-export-result "penpot" "all" options))]
      (t/is (rejected-with? outcome pattern) (str label ": " outcome))
      (t/is (= [export-uri] (mapv :uri http-calls)) label)
      (t/is (empty? events) label))))

(t/deftest ^:async file-export-rejects-invalid-and-legacy-arguments-before-service
  (doseq [[format type pattern]
          [["zip" "all" #"Legacy zip export is unsupported"]
           ["unknown" "all" #"Invalid format"]
           ["penpot" "unknown" #"Invalid type"]]]
    (let [{:keys [outcome service-calls http-calls worker-requests events]}
          (await (get-export-result format type {}))
          context (str "format=" format ", type=" type ", outcome=" outcome)]
      (t/is (rejected-with? outcome pattern) context)
      (t/is (empty? service-calls) context)
      (t/is (empty? http-calls) context)
      (t/is (empty? worker-requests) context)
      (t/is (empty? events) context))))
