;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.mattermost
  "A mattermost integration for error reporting."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.http.client :as http]
   [app.loggers.database :as ldb]
   [app.util.json :as json]
   [integrant.core :as ig]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]))

(defonce enabled (atom true))

(defn- send-mattermost-notification!
  [cfg {:keys [id public-uri] :as report}]


  (let [text (str "Exception: " public-uri "/dbg/error/" id " "
                  (when-let [pid (:profile-id report)]
                    (str "(pid: #uuid-" pid ")"))
                  "\n"
                  "- host: #" (:host report) "\n"
                  "- tenant: #" (:tenant report) "\n"
                  "- logger: #" (:logger report) "\n"
                  "- request-path: `" (:request-path report) "`\n"
                  "- frontend-version: `" (:frontend-version report) "`\n"
                  "- backend-version: `" (:backend-version report) "`\n"
                  "\n"
                  "```\n"
                  "Trace:\n"
                  (:trace report)
                  "```")

        resp (http/req! cfg
                        {:uri (cf/get :error-report-webhook)
                         :method :post
                         :headers {"content-type" "application/json"}
                         :body (json/encode-str {:text text})}
                        {:sync? true})]

    (when (not= 200 (:status resp))
      (l/warn :hint "error on sending data"
              :response (pr-str resp)))))

(defn record->report
  [{:keys [::l/context ::l/id ::l/cause] :as record}]
  (assert (l/valid-record? record) "expectd valid log record")
  {:id               id
   :tenant           (cf/get :tenant)
   :host             (cf/get :host)
   :public-uri       (cf/get :public-uri)
   :backend-version  (or (:version/backend context) (:full cf/version))
   :frontend-version (:version/frontend context)
   :profile-id       (:request/profile-id context)
   :request-path     (:request/path context)
   :logger           (::l/logger record)
   :trace            (ex/format-throwable cause :detail? false :header? false)})

(defn handle-event
  [cfg record]
  (when @enabled
    (try
      (let [report (record->report record)]
        (send-mattermost-notification! cfg report))
      (catch Throwable cause
        (l/warn :hint "unhandled error" :cause cause)))))

(defmethod ig/assert-key ::reporter
  [_ params]
  (assert (http/client? (::http/client params)) "expect valid http client"))

(defmethod ig/init-key ::reporter
  [_ cfg]
  (when-let [uri (cf/get :error-report-webhook)]
    (px/thread
      {:name "penpot/mattermost-reporter"
       :virtual true}
      (l/info :hint "initializing error reporter" :uri uri)
      (let [input (sp/chan :buf (sp/sliding-buffer 128)
                           :xf (filter ldb/error-record?))]
        (add-watch l/log-record ::reporter #(sp/put! input %4))
        (try
          (loop []
            (when-let [msg (sp/take! input)]
              (handle-event cfg msg)
              (recur)))
          (catch InterruptedException _
            (l/debug :hint "reporter interrupted"))
          (catch Throwable cause
            (l/error :hint "unexpected error" :cause cause))
          (finally
            (sp/close! input)
            (remove-watch l/log-record ::reporter)
            (l/info :hint "reporter terminated")))))))

(defmethod ig/halt-key! ::reporter
  [_ thread]
  (some-> thread px/interrupt!))
