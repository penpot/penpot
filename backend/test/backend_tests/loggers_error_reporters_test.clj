;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.loggers-error-reporters-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.loggers.audit :as audit]
   [app.loggers.database :as logdb]
   [app.loggers.mattermost :as logmm]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [integrant.core :as ig]
   [promesa.exec.csp :as sp]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- with-webhook-url
  [url f]
  (let [real-get @#'cf/get]
    (with-redefs [cf/get (fn ([k] (if (= k :error-report-webhook) url (real-get k)))
                           ([k d] (if (= k :error-report-webhook) url (real-get k d))))]
      (f))))

(t/deftest database-reporter-does-not-start-without-flag
  (with-redefs [cf/flags #{}]
    (t/is (nil? (ig/init-key :app.loggers.database/reporter
                             {::db/pool th/*pool*})))))

(t/deftest database-reporter-starts-with-flag
  (with-redefs [cf/flags #{:error-reporting}]
    (let [reporter (ig/init-key :app.loggers.database/reporter
                                {::db/pool th/*pool*})]
      (try
        (t/is (some? (:app.loggers.database/input reporter)))
        (t/is (some? (:app.loggers.database/thread reporter)))
        (finally
          (ig/halt-key! :app.loggers.database/reporter reporter))))))

(t/deftest database-reporter-halt-without-start-is-safe
  (t/is (nil? (ig/halt-key! :app.loggers.database/reporter nil))))

(t/deftest database-reporter-runtime-switch-controls-persistence
  (with-redefs [cf/flags #{:error-reporting}
                logdb/enabled (atom true)]
    (let [input          (sp/chan)
          reporter       (with-redefs [sp/chan (constantly input)]
                           (ig/init-key :app.loggers.database/reporter
                                        {::db/pool th/*pool*}))
          ^Thread thread (::logdb/thread reporter)
          event          (with-meta {:name "unhandled-exception"
                                     :type "action"
                                     :context {:version "2.19.0"}
                                     :props {:hint "runtime switch test"}}
                           {::audit/event true})]
      (try
        (doseq [[label enabled?] [["enabled" true]
                                  ["disabled" false]
                                  ["re-enabled without restarting" true]]]
          (t/testing label
            (reset! logdb/enabled enabled?)
            (let [id (uuid/next)]
              (t/is (true? (sp/put! input (assoc event :id id) 5000 ::timeout)))
              ;; On an unbuffered channel, the second put completes only
              ;; after the reporter finishes processing the first report.
              (t/is (true? (sp/put! input (assoc event :id (uuid/next)) 5000 ::timeout)))
              (t/is (= (if enabled? [{:id id :source 4}] [])
                       (th/db-exec! ["select id, source from server_error_report where id = ?" id]))))))
        (finally
          (sp/close input)
          (.join thread 5000)
          (ig/halt-key! :app.loggers.database/reporter reporter)
          (t/is (not (.isAlive thread))))))))

(t/deftest mattermost-reporter-does-not-start-without-flag
  (with-redefs [cf/flags #{}
                http/client? (constantly true)]
    (with-webhook-url "https://example.invalid/hook"
      #(t/is (nil? (ig/init-key :app.loggers.mattermost/reporter
                                {::http/client :dummy}))))))

(t/deftest mattermost-reporter-does-not-start-without-url
  (with-redefs [cf/flags #{:error-reporting}
                http/client? (constantly true)]
    (with-webhook-url nil
      #(t/is (nil? (ig/init-key :app.loggers.mattermost/reporter
                                {::http/client :dummy}))))))

(t/deftest mattermost-reporter-starts-with-flag-and-url
  (with-redefs [cf/flags #{:error-reporting}
                http/client? (constantly true)]
    (with-webhook-url "https://example.invalid/hook"
      #(let [reporter (ig/init-key :app.loggers.mattermost/reporter
                                   {::http/client :dummy})]
         (try
           (t/is (some? (:app.loggers.mattermost/input reporter)))
           (t/is (some? (:app.loggers.mattermost/thread reporter)))
           (finally
             (ig/halt-key! :app.loggers.mattermost/reporter reporter)))))))

(t/deftest emit-without-reporter-is-noop
  (t/is (nil? (logdb/emit {} {:type "action" :name "navigate"})))
  (t/is (nil? (logmm/emit {} {:type "action" :name "navigate"}))))
