;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.repo-test
  (:require
   [app.common.transit :as transit]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.event :as-alias ev]
   [app.main.data.persistence :as dps]
   [app.main.repo :as repo]
   [app.main.store :as st]
   [app.util.globals :as globals]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [debug :as dbg]
   [frontend-tests.helpers.async :as hva]
   [frontend-tests.helpers.http :as http]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.persistence :as thp]
   [potok.v2.core :as ptk]))

(defn- ^:async capture-request
  [command params]
  (let [request  (volatile! nil)
        original (http/install-fetch-mock!
                  (fn [url ^js options]
                    (vreset! request [url options])
                    (js/Promise.resolve (http/make-transit-response {}))))]
    (try
      (await (hva/->promise (repo/cmd! command params)))
      @request
      (finally
        (http/restore-fetch! original)))))

(t/deftest ^:async penpot-api-requests-send-the-page-origin
  (await (mock/with-mocks*
           {(.-origin ^js globals/location) "https://alias.example"
            cf/public-uri (u/uri "https://canonical.example/penpot/")
            cf/external-session-id (fn [] "external-session")}
           ;; Keep the page origin distinct from the request URI to prove
           ;; which one supplies the header.
           (doseq [[command method path]
                   [[:get-profile "GET" "/penpot/api/main/methods/get-profile"]
                    [:login-with-oidc "POST" "/penpot/api/auth/oidc"]
                    [:upload-chunk "POST" "/penpot/api/main/methods/upload-chunk"]
                    [:create-file-thumbnail "POST" "/penpot/api/main/methods/create-file-thumbnail"]]]
             (t/testing (name command)
               (let [[url ^js options] (await (capture-request command (with-meta {} {::ev/origin "test"})))
                     headers           (js/Headers. (.-headers options))]
                 (t/is (= (str "https://canonical.example" path) url))
                 (t/is (= method (.-method options)))
                 (t/is (= "include" (.-credentials options)))
                 (t/is (= "cors" (.-mode options)))
                 (t/is (= "https://alias.example" (.get headers "x-penpot-origin")))
                 (t/is (= "external-session" (.get headers "x-external-session-id")))
                 (t/is (= "test" (.get headers "x-event-origin")))
                 (when (= :get-profile command)
                   (t/is (= "application/transit+json,text/event-stream,*/*" (.get headers "accept")))
                   (t/is (= (str cf/session-id) (.get headers "x-session-id"))))
                 (when (#{:upload-chunk :create-file-thumbnail} command)
                   (t/is (instance? js/FormData (.-body options)))
                   (t/is (not (.has headers "content-type"))))))))))

(t/deftest ^:async exporter-requests-do-not-send-the-page-origin
  (await (mock/with-mocks*
           {(.-origin ^js globals/location) "https://alias.example"
            cf/public-uri (u/uri "https://alias.example/penpot/")}
           (doseq [[command params path]
                   [[:export {} "/penpot/api/export"]
                    [:create-export-job {} "/penpot/api/export/jobs"]
                    [:cancel-export-job {:job-id "job-id"} "/penpot/api/export/jobs/job-id"]]]
             (t/testing (name command)
               (let [[url ^js options] (await (capture-request command params))
                     headers           (js/Headers. (.-headers options))]
                 (t/is (= path (.-pathname (js/URL. url))))
                 (t/is (not (.has headers "x-penpot-origin")))))))))

(defn- ^:async with-captured-requests
  [run & [respond]]
  (await
   (mock/with-mocks*
     {cf/public-uri (u/uri "https://example.test/")}
     (let [requests (atom [])
           fetch    (fn [url ^js opts]
                      (let [uri         (js/URL. url)
                            ^js query   (.-searchParams uri)
                            body        (.-body opts)]
                        (swap! requests conj
                               {:path (.-pathname uri)
                                :id (.get query "id")
                                :method (.-method opts)
                                :content-type (aget (.-headers opts) "content-type")
                                :body (when (some? body) (transit/decode-str body))})
                        (js/Promise.resolve
                         (if respond
                           (respond (count @requests))
                           (http/make-transit-response {:revn 4 :lagged []})))))
           original (http/install-fetch-mock! fetch)]
       (try
         (await (run requests))
         (finally
           (http/restore-fetch! original)))))))

(defn- make-save-params
  []
  (let [page-id (uuid/next)]
    {:id (uuid/next)
     :session-id (uuid/next)
     :revn 3
     :vern 0
     :features #{}
     :commits [{:commit-id (uuid/next)
                :redo-changes [{:type :mod-page :id page-id :name "First edit"}]
                :undo-changes []}
               {:commit-id (uuid/next)
                :redo-changes [{:type :mod-page :id page-id :name "Second edit"}]
                :undo-changes [{:type :mod-page :id page-id :name "First edit"}]}]}))

(t/deftest ^:async update-file-v2-posts-transit-with-id-in-query-and-body
  (await
   (with-captured-requests
     (^:async fn [requests]
       (let [params (make-save-params)]
         (await (hva/observe (repo/cmd! :update-file-v2 params)))
         (t/is (= [{:path "/api/main/methods/update-file-v2"
                    :id (str (:id params))
                    :method "POST"
                    :content-type "application/transit+json"
                    :body params}]
                  @requests)))))))

(t/deftest ^:async a-save-and-its-retry-post-the-same-opt-in-transit-body
  (await
   (with-captured-requests
     (^:async fn [requests]
       (let [{:keys [id revn session-id commits] :as params} (make-save-params)
             edits    (mapv #(thp/local-commit id %) commits)
             payload  (assoc params :repair-in-response true
                             :commits (mapv (fn [commit edit] (assoc commit :commit-id (:id @edit)))
                                            commits edits))
             request  {:path "/api/main/methods/update-file-v2"
                       :id (str id)
                       :method "POST"
                       :content-type "application/transit+json"
                       :body payload}
             store    (ptk/store {:state {:current-file-id id
                                          :session-id session-id
                                          :permissions {:can-edit true}
                                          :files {id {:id id :revn revn :vern 0}}}
                                  :on-error #(t/is false (str %))})]
         (await
          (mock/with-mocks*
            {rx/timer         (mock/stub (fn [ms] (if (= 2000 ms) (rx/of :tick) (rx/subject))))
             st/ongoing-tasks (atom #{})}
            (try
              (ptk/emit! store (dps/initialize-persistence))
              (apply ptk/emit! store (conj edits ::dps/force-persist))
              (await (hva/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
                                                     (= 2 (count @requests)))
                                         "the opt-in save and its retry settle"))
              (t/is (= [request request] @requests))
              (finally
                (rx/dispose! store)))))))
     (fn [n]
       (if (= 1 n)
         (js/Response. "Gateway timeout" #js {:status 504 :headers #js {"content-type" "text/html"}})
         ;; A response without the new keys still follows the legacy path.
         (http/make-transit-response {:revn 4 :lagged []}))))))

(t/deftest ^:async save-fault-marks-only-the-selected-redo-and-unhooks-after-use
  (await
   (with-captured-requests
     (^:async fn [requests]
       (let [params (make-save-params)
             legacy {:id (:id params) :changes (get-in params [:commits 0 :redo-changes])}]
         (try
           (dbg/failNextSave #js {:fault "validation" :commit 1 :fileId (str (:id params))})
           (await (hva/observe (repo/cmd! :update-file legacy)))
           (t/testing "legacy flat saves keep their query and body even with a fault armed"
             (t/is (= [{:path "/api/main/methods/update-file"
                        :id (str (:id params))
                        :method "POST"
                        :content-type "application/transit+json"
                        :body legacy}]
                      @requests)))

           (await (hva/observe (repo/cmd! :update-file-v2 params)))
           (t/testing "only the second commit's redo changes"
             (t/is (= (update-in params [:commits 1 :redo-changes] conj
                                 {:type :set-plugin-data
                                  :object-type :file
                                  :namespace :penpot-debug
                                  :key "save-fault:validation"
                                  :value nil})
                      (:body (second @requests)))))
           (t/is (not (contains? (methods repo/cmd!) :update-file-v2))
                 "the default method is no longer wrapped")
           (await (hva/observe (repo/cmd! :update-file-v2 params)))
           (t/is (= params (:body (last @requests))) "the next save is untouched")
           (finally
             (dbg/clearSaveFaults))))))))

(t/deftest ^:async hold-saves-delays-only-v2-and-restores-the-explicit-method
  (await
   (with-captured-requests
     (^:async fn [requests]
       (let [params   (make-save-params)
             legacy   {:id (:id params) :changes []}
             original (get-method repo/cmd! :default)
             answers  (atom [])]
         (-add-method repo/cmd! :update-file-v2 original)
         (try
           (dbg/holdSaves 60)
           (let [held (hva/observe (repo/cmd! :update-file-v2 params)
                                   {:on-next #(swap! answers conj %)})]
             (try
               (await (hva/observe (repo/cmd! :update-file legacy)))
               (await (hva/observe (repo/cmd! :get-comment-threads {:file-id (:id params)})))
               (t/is (= ["/api/main/methods/update-file-v2"
                         "/api/main/methods/update-file"
                         "/api/main/methods/get-comment-threads"]
                        (mapv :path @requests))
                     "the held save reached the transport and other calls completed")
               (t/is (empty? @answers) "the save answer is still held")
               (finally
                 (dbg/releaseSaves)
                 (await held))))
           (t/is (= [{:revn 4 :lagged []}] @answers))
           (t/is (identical? original (get (methods repo/cmd!) :update-file-v2))
                 "release restores the exact method table entry")
           (finally
             (remove-method repo/cmd! :update-file-v2))))))))

;; ---------------------------------------------------------------------------
;; retryable-error? tests (synchronous)
;; ---------------------------------------------------------------------------

(t/deftest retryable-error-network
  (t/testing "network error (js/fetch failure) is retryable"
    (let [err (ex-info "network" {:type :network})]
      (t/is (true? (repo/retryable-error? err))))))

(t/deftest retryable-error-bad-gateway
  (t/testing "502 bad-gateway is retryable"
    (let [err (ex-info "bad gateway" {:type :bad-gateway})]
      (t/is (true? (repo/retryable-error? err))))))

(t/deftest retryable-error-service-unavailable
  (t/testing "503 service-unavailable is retryable"
    (let [err (ex-info "service unavailable" {:type :service-unavailable})]
      (t/is (true? (repo/retryable-error? err))))))

(t/deftest retryable-error-gateway-error
  (t/testing "504 and the Cloudflare 52x family are retryable"
    (let [err (ex-info "http error" {:type :gateway-error})]
      (t/is (true? (repo/retryable-error? err))))))

(t/deftest retryable-error-offline
  (t/testing "offline (status 0) is retryable"
    (let [err (ex-info "offline" {:type :offline})]
      (t/is (true? (repo/retryable-error? err))))))

(t/deftest retryable-error-internal
  (t/testing "internal error (genuine bug) is NOT retryable"
    (let [err (ex-info "internal" {:type :internal :code :something})]
      (t/is (not (repo/retryable-error? err))))))

(t/deftest retryable-error-validation
  (t/testing "validation error is NOT retryable"
    (let [err (ex-info "validation" {:type :validation :code :request-body-too-large})]
      (t/is (not (repo/retryable-error? err))))))

(t/deftest retryable-error-authentication
  (t/testing "authentication error is NOT retryable"
    (let [err (ex-info "auth" {:type :authentication})]
      (t/is (not (repo/retryable-error? err))))))

(t/deftest retryable-error-authorization
  (t/testing "authorization/challenge error is NOT retryable"
    (let [err (ex-info "auth" {:type :authorization :code :challenge-required})]
      (t/is (not (repo/retryable-error? err))))))

(t/deftest retryable-error-no-ex-data
  (t/testing "plain error without ex-data is NOT retryable"
    (let [err (js/Error. "plain")]
      (t/is (not (repo/retryable-error? err))))))

;; ---------------------------------------------------------------------------
;; with-retry tests (async, using zero-delay config for speed)
;; ---------------------------------------------------------------------------

(def ^:private fast-config
  "Retry config with zero delay for fast tests."
  {:max-retries 3 :base-delay-ms 0})

(t/deftest with-retry-succeeds-immediately
  (t/testing "returns value when observable succeeds on first try"
    (t/async done
      (let [call-count (atom 0)
            obs-fn     (fn []
                         (swap! call-count inc)
                         (rx/of :ok))]
        (->> (repo/with-retry obs-fn fast-config)
             (rx/subs!
              (fn [val]
                (t/is (= :ok val))
                (t/is (= 1 @call-count))
                (done))
              (fn [err]
                (t/is false (str "unexpected error: " (ex-message err)))
                (done))))))))

(t/deftest with-retry-retries-on-retryable-error
  (t/testing "retries and eventually succeeds after transient failures"
    (t/async done
      (let [call-count (atom 0)
            obs-fn     (fn []
                         (let [n (swap! call-count inc)]
                           (if (< n 3)
                             ;; First two calls fail with retryable error
                             (rx/throw (ex-info "bad gateway" {:type :bad-gateway}))
                             ;; Third call succeeds
                             (rx/of :recovered))))]
        (->> (repo/with-retry obs-fn fast-config)
             (rx/subs!
              (fn [val]
                (t/is (= :recovered val))
                (t/is (= 3 @call-count))
                (done))
              (fn [err]
                (t/is false (str "unexpected error: " (ex-message err)))
                (done))))))))

(t/deftest with-retry-exhausts-retries
  (t/testing "propagates error after max retries exhausted"
    (t/async done
      (let [call-count (atom 0)
            obs-fn     (fn []
                         (swap! call-count inc)
                         (rx/throw (ex-info "offline" {:type :offline})))]
        (->> (repo/with-retry obs-fn fast-config)
             (rx/subs!
              (fn [_val]
                (t/is false "should not succeed")
                (done))
              (fn [err]
                ;; 1 initial + 3 retries = 4 total calls
                (t/is (= 4 @call-count))
                (t/is (= :offline (:type (ex-data err))))
                (done))))))))

(t/deftest with-retry-no-retry-on-non-retryable
  (t/testing "non-retryable errors propagate immediately without retry"
    (t/async done
      (let [call-count (atom 0)
            obs-fn     (fn []
                         (swap! call-count inc)
                         (rx/throw (ex-info "auth" {:type :authentication})))]
        (->> (repo/with-retry obs-fn fast-config)
             (rx/subs!
              (fn [_val]
                (t/is false "should not succeed")
                (done))
              (fn [err]
                (t/is (= 1 @call-count))
                (t/is (= :authentication (:type (ex-data err))))
                (done))))))))

(t/deftest with-retry-network-error-retried
  (t/testing "network error (js/fetch failure) is retried"
    (t/async done
      (let [call-count (atom 0)
            obs-fn     (fn []
                         (let [n (swap! call-count inc)]
                           (if (= n 1)
                             (rx/throw (ex-info "net" {:type :network}))
                             (rx/of :ok))))]
        (->> (repo/with-retry obs-fn fast-config)
             (rx/subs!
              (fn [val]
                (t/is (= :ok val))
                (t/is (= 2 @call-count))
                (done))
              (fn [err]
                (t/is false (str "unexpected error: " (ex-message err)))
                (done))))))))

(t/deftest with-retry-internal-not-retried
  (t/testing "internal error (genuine bug) is not retried"
    (t/async done
      (let [call-count (atom 0)
            obs-fn     (fn []
                         (swap! call-count inc)
                         (rx/throw (ex-info "bug" {:type :internal
                                                   :code :something})))]
        (->> (repo/with-retry obs-fn fast-config)
             (rx/subs!
              (fn [_val]
                (t/is false "should not succeed")
                (done))
              (fn [err]
                (t/is (= 1 @call-count))
                (t/is (= :internal (:type (ex-data err))))
                (done))))))))

(t/deftest with-retry-respects-max-retries-config
  (t/testing "respects custom max-retries setting"
    (t/async done
      (let [call-count (atom 0)
            config     {:max-retries 1 :base-delay-ms 0}
            obs-fn     (fn []
                         (swap! call-count inc)
                         (rx/throw (ex-info "offline" {:type :offline})))]
        (->> (repo/with-retry obs-fn config)
             (rx/subs!
              (fn [_val]
                (t/is false "should not succeed")
                (done))
              (fn [err]
                ;; 1 initial + 1 retry = 2 total
                (t/is (= 2 @call-count))
                (t/is (= :offline (:type (ex-data err))))
                (done))))))))

(t/deftest with-retry-mixed-errors
  (t/testing "retries retryable errors, then stops on non-retryable"
    (t/async done
      (let [call-count (atom 0)
            obs-fn     (fn []
                         (let [n (swap! call-count inc)]
                           (case n
                             1 (rx/throw (ex-info "gw" {:type :bad-gateway}))
                             2 (rx/throw (ex-info "auth" {:type :authentication}))
                             (rx/of :should-not-reach))))]
        (->> (repo/with-retry obs-fn fast-config)
             (rx/subs!
              (fn [_val]
                (t/is false "should not succeed")
                (done))
              (fn [err]
                (t/is (= 2 @call-count))
                (t/is (= :authentication (:type (ex-data err))))
                (done))))))))

;; ---------------------------------------------------------------------------
;; handle-response classification tests
;; ---------------------------------------------------------------------------

(defn- capture-error
  "Subscribes to `handle-response` for `response` and calls `k` with the
  `ex-data` of the raised error."
  [response k]
  (->> (repo/handle-response response)
       (rx/subs!
        (fn [_] (t/is false "expected an error response") (k nil))
        (fn [cause] (k (ex-data cause))))))

(def ^:private html-error-page
  "<html><head><title>Web Page Blocked</title></head><body>blocked</body></html>")

(t/deftest handle-response-gateway-statuses
  (t/testing "every edge status that never reached the backend is a gateway error"
    (t/async done
      (let [statuses [504 520 521 522 523 524]
            pending  (atom (count statuses))]
        (doseq [status statuses]
          (capture-error {:status status
                          :body html-error-page
                          :headers {"content-type" "text/html"}
                          :uri "https://design.penpot.app/api/main/methods/get-teams"}
                         (fn [data]
                           (t/is (= :gateway-error (:type data))
                                 (str "status " status " should be a gateway error"))
                           (t/is (= status (:status data))
                                 (str "status " status " should be carried through"))
                           (when (zero? (swap! pending dec))
                             (done)))))))))

(t/deftest handle-response-rate-limit
  (t/testing "a 429 with no interpretable body is a rate-limit error"
    (t/async done
      (capture-error {:status 429
                      :body ""
                      :headers {}
                      :uri "https://design.penpot.app/api/main/methods/get-teams"}
                     (fn [data]
                       (t/is (= :rate-limit (:type data)))
                       (t/is (= 429 (:status data)))
                       (done))))))

(t/deftest handle-response-intercepted-by-proxy
  (t/testing "an error page from a filtering proxy is not an internal error"
    (t/async done
      (capture-error {:status 403
                      :body html-error-page
                      :headers {"content-type" "text/html"
                                "server" "Zscaler/6.2"}
                      :uri "https://design.penpot.app/api/main/methods/update-file"}
                     (fn [data]
                       (t/is (= :unexpected-response (:type data)))
                       (t/is (= 403 (:status data)))
                       (done))))))

(t/deftest handle-response-keeps-body-excerpt
  (t/testing "a body too long to keep is cut short and marked as cut"
    (t/async done
      (capture-error {:status 403
                      :body (apply str (repeat 5000 "x"))
                      :headers {"content-type" "text/html"}
                      :uri "https://design.penpot.app/api/main/methods/update-file"}
                     (fn [data]
                       (t/is (= 503 (count (:data data))) "500 characters and an ellipsis")
                       (t/is (str/ends-with? (:data data) "..."))
                       (done))))))

(t/deftest handle-response-keeps-a-short-body-whole
  (t/testing "a body short enough to read is passed through untouched"
    (t/async done
      (capture-error {:status 403
                      :body html-error-page
                      :headers {"content-type" "text/html"}
                      :uri "https://design.penpot.app/api/main/methods/update-file"}
                     (fn [data]
                       (t/is (= html-error-page (:data data)))
                       (done))))))

(t/deftest handle-response-challenge-still-authorization
  (t/testing "a Cloudflare challenge keeps its own type"
    (t/async done
      (capture-error {:status 403
                      :body html-error-page
                      :headers {"server" "cloudflare"
                                "cf-mitigated" "challenge"}
                      :uri "https://design.penpot.app/api/main/methods/get-teams"}
                     (fn [data]
                       (t/is (= :authorization (:type data)))
                       (t/is (= :challenge-required (:code data)))
                       (done))))))

(t/deftest handle-response-keeps-backend-error
  (t/testing "an error the backend itself reported is passed through untouched"
    (t/async done
      (capture-error {:status 400
                      :body {:type :validation :code :missing-param}
                      :headers {"content-type" "application/transit+json"}
                      :uri "https://design.penpot.app/api/main/methods/update-file"}
                     (fn [data]
                       (t/is (= :validation (:type data)))
                       (t/is (= :missing-param (:code data)))
                       (t/is (= 400 (:status data)))
                       (done))))))

(t/deftest handle-response-undecodable-non-error-is-internal
  (t/testing "a non-error response we cannot interpret is still an internal error"
    (t/async done
      (capture-error {:status 301
                      :body html-error-page
                      :headers {"content-type" "text/html"}
                      :uri "https://design.penpot.app/api/main/methods/get-teams"}
                     (fn [data]
                       (t/is (= :internal (:type data)))
                       (t/is (= :unable-to-process-repository-response (:code data)))
                       (done))))))
