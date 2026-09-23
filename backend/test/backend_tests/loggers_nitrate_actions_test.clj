;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.loggers-nitrate-actions-test
  (:require
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.http.client :as http]
   [app.loggers.audit :as audit]
   [app.loggers.nitrate-actions :as nitrate-actions]
   [app.nitrate :as nitrate]
   [app.setup :as-alias setup]
   [app.worker :as wrk]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]
   [integrant.core :as ig]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- sample-event
  [profile-id & {:keys [name] :or {name "create-organization"}}]
  {:id (uuid/next)
   :type "action"
   :name name
   :profile-id profile-id
   :props {:organization-id (uuid/next)}
   :context {:version "2.0.0" :initiator "app"}
   :tracked-at (ct/now)
   :created-at (ct/now)
   :source "backend"
   :ip-addr "192.168.1.10"})

(defn- expected-task-params
  [event]
  (select-keys event [:name :type :profile-id :props
                      :created-at :tracked-at :source :ip-addr :context]))

(defn- with-admin-console-uri
  "Redef `cf/get` so `:admin-console-uri` is a string (production shape)."
  [uri-str f]
  (let [orig-get cf/get]
    (with-redefs [cf/get (fn [k & args]
                           (if (= k :admin-console-uri)
                             uri-str
                             (apply orig-get k args)))]
      (f))))

(t/deftest handle-event-submits-all-interesting-names
  (with-redefs [cf/flags (conj cf/flags :admin-console)]
    (doseq [event-name nitrate-actions/event-names-to-keep]
      (with-mocks [submit-mock {:target 'app.worker/submit! :return nil}]
        (let [event (sample-event (uuid/next) :name event-name)
              opts  (do (nitrate-actions/handle-event! {} event)
                        (first (:call-args @submit-mock)))]
          (t/is (= 1 (:call-count @submit-mock)) event-name)
          (t/is (= event-name (get-in opts [::wrk/params :name]))))))))

(t/deftest handle-event-submits-create-organization
  (with-redefs [cf/flags (conj cf/flags :admin-console)]
    (with-mocks [submit-mock {:target 'app.worker/submit! :return nil}]
      (let [event  (sample-event (uuid/next))
            params (expected-task-params event)]
        (nitrate-actions/handle-event! {} event)
        (t/is (= 1 (:call-count @submit-mock)))
        (let [opts (first (:call-args @submit-mock))]
          (t/is (= :process-nitrate-action (::wrk/task opts)))
          (t/is (= :admin-console (::wrk/queue opts)))
          (t/is (= 0 (::wrk/max-retries opts)))
          (t/is (= params (::wrk/params opts))))))))

(t/deftest handle-event-ignores-other-names
  (with-redefs [cf/flags (conj cf/flags :admin-console)]
    (with-mocks [submit-mock {:target 'app.worker/submit! :return nil}]
      (let [event (sample-event (uuid/next) :name "update-profile")]
        (nitrate-actions/handle-event! {} event)
        (t/is (= 0 (:call-count @submit-mock)))))))

(t/deftest allowlist-excludes-frontend-only-names
  ;; push-audit-events never calls process-event / handle-event!; keep
  ;; these names out of the allowlist so we do not claim AC coverage.
  (doseq [name #{"add-font" "delete-files" "restore-files"
                 "delete-organization-member"}]
    (t/is (not (contains? nitrate-actions/event-names-to-keep name))
          name)))

(t/deftest handle-event-requires-admin-console-flag
  (with-redefs [cf/flags #{}]
    (with-mocks [submit-mock {:target 'app.worker/submit! :return nil}]
      (nitrate-actions/handle-event! {} (sample-event (uuid/next)))
      (t/is (= 0 (:call-count @submit-mock))))))

(t/deftest process-event-enqueues-create-organization
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags (conj cf/flags :admin-console)]
      (with-mocks [submit-mock {:target 'app.worker/submit! :return nil}]
        (let [event (sample-event (:id prof))]
          (audit/submit* th/*system* event)
          (t/is (= 1 (:call-count @submit-mock)))
          (let [opts   (first (:call-args @submit-mock))
                params (::wrk/params opts)]
            (t/is (= :process-nitrate-action (::wrk/task opts)))
            (t/is (= "create-organization" (:name params)))
            (t/is (= "backend" (:source params)))
            (t/is (= "192.168.1.10" (:ip-addr params)))
            (t/is (= (:context event) (:context params)))))))))

(t/deftest process-event-skips-non-interesting-names
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags (conj cf/flags :admin-console)]
      (with-mocks [submit-mock {:target 'app.worker/submit! :return nil}]
        (let [event (sample-event (:id prof) :name "update-profile")]
          (audit/submit* th/*system* event)
          (t/is (= 0 (:call-count @submit-mock))))))))

(t/deftest process-nitrate-action-handler-ingests
  (let [called (atom nil)
        event  (sample-event (uuid/next))]
    (with-redefs [nitrate/call (fn [_cfg method params]
                                 (reset! called {:method method :params params})
                                 nil)]
      (th/run-task! :process-nitrate-action event)
      (t/is (= :ingest-audit-log (:method @called)))
      (t/is (= (:name event) (get-in @called [:params :name])))
      (t/is (= (:profile-id event) (get-in @called [:params :profile-id]))))))

(t/deftest process-nitrate-action-handler-propagates-errors
  (with-redefs [nitrate/call (fn [& _]
                               (ex/raise :type :nitrate-http-error
                                         :hint "boom"))]
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (th/run-task! :process-nitrate-action
                                 (sample-event (uuid/next)))))))

(t/deftest ingest-audit-log-posts-json-body
  (with-redefs [cf/flags (conj cf/flags :admin-console)]
    (with-admin-console-uri
      "http://ac.example/admin-console/"
      (fn []
        (with-mocks [http-mock {:target 'app.http.client/req
                                :return {:status 204 :body nil}}]
          (let [profile-id (uuid/next)
                client     (ig/init-key :app.nitrate/client
                                        {::http/client (Object.)
                                         ::setup/shared-keys {:admin-console "test-shared-key"}})
                cfg        {:app.nitrate/client client}
                event      {:name "create-organization"
                            :type "action"
                            :profile-id profile-id
                            :props {:organization-id (uuid/next)}
                            :context {:initiator "app"}
                            :source "backend"
                            :ip-addr "127.0.0.1"
                            :created-at (ct/inst "2026-09-23T14:13:19.368Z")
                            :tracked-at (ct/inst "2026-09-23T14:13:19.368Z")}
                result     (nitrate/call cfg :ingest-audit-log event)]
            (t/is (nil? result))
            (t/is (= 1 (:call-count @http-mock)))
            (let [[_req-cfg req] (:call-args @http-mock)]
              (t/is (= :post (:method req)))
              (t/is (= "test-shared-key" (get-in req [:headers "x-shared-key"])))
              (t/is (str/ends-with? (str (:uri req)) "api/audit-log"))
              (let [body (json/decode (:body req) :key-fn json/read-kebab-key)]
                (t/is (= "create-organization" (:name body)))
                (t/is (= "action" (:type body)))
                (t/is (= (str profile-id) (str (:profile-id body))))
                (t/is (= "backend" (:source body)))
                (t/is (= "127.0.0.1" (:ip-addr body)))))))))))

(t/deftest ingest-audit-log-throws-on-4xx
  (with-redefs [cf/flags (conj cf/flags :admin-console)]
    (with-admin-console-uri
      "http://ac.example/admin-console/"
      (fn []
        (with-mocks [http-mock {:target 'app.http.client/req
                                :return {:status 400 :body "{\"error\":\"bad\"}"}}]
          (let [client (ig/init-key :app.nitrate/client
                                    {::http/client (Object.)
                                     ::setup/shared-keys {:admin-console "test-shared-key"}})
                cfg    {:app.nitrate/client client}]
            (t/is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #"nitrate HTTP 400"
                   (nitrate/call cfg :ingest-audit-log
                                 {:name "create-organization"
                                  :type "action"
                                  :profile-id (uuid/next)})))))))))
