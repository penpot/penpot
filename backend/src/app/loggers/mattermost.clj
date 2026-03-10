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
   [app.common.pprint :as pp]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.http.client :as http]
   [app.loggers.audit :as audit]
   [app.rpc.rlimit :as-alias rlimit]
   [app.util.json :as json]
   [integrant.core :as ig]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]))

(defonce enabled (atom true))

(defn- send-mattermost-notification!
  [cfg {:keys [id] :as report}]
  (let [type (get report :type)
        text (str "#" type " | " (get report :hint) "\n"
                  (when id
                    (str (u/join (cf/get :public-uri) "/dbg/error/" id) " "))

                  (when-let [pid (:profile-id report)]
                    (if (uuid? pid)
                      (str "(pid: #uuid-" pid ")")
                      (str "(pid: #ip-" pid ")")))
                  "\n"
                  "- host: #" (:host report) "\n"
                  "- tenant: #" (:tenant report) "\n"
                  "- origin: #" (:origin report) "\n"
                  (when-let [href (get report :href)]
                    (str "- href: `" href "`\n"))
                  (when-let [version (get report :frontend-version)]
                    (str "- frontend-version: `" version "`\n"))
                  (when-let [version (get report :backend-version)]
                    (str "- backend-version: `" version "`\n"))
                  "\n"
                  (when-let [info (:info report)]
                    (str "```\n" info "```"))
                  (when-let [trace (:trace report)]
                    (str "```\n"
                         "Trace:\n"
                         trace
                         "```")))

        resp (http/req! cfg
                        {:uri (cf/get :error-report-webhook)
                         :method :post
                         :headers {"content-type" "application/json"}
                         :body (json/encode-str {:text text})}
                        {:sync? true})]

    (when (not= 200 (:status resp))
      (l/warn :hint "error on sending data"
              :response (pr-str resp)))))

(defn- log-record->report
  [{:keys [::l/context ::l/id ::l/cause ::l/message] :as record}]
  (assert (l/valid-record? record) "expectd valid log record")

  (let [public-uri (cf/get :public-uri)]
    {:id               id
     :type             "exception"
     :origin           "logging"
     :hint             (or (some-> cause ex-message) @message)
     :tenant           (cf/get :tenant)
     :host             (cf/get :host)
     :backend-version  (:full cf/version)
     :frontend-version (:frontend/version context)
     :profile-id       (:request/profile-id context)
     :href             (-> public-uri
                           (assoc :path (:request/path context))
                           (str))
     :trace            (ex/format-throwable cause :detail? false :header? false)}))

(defn- audit-event->report
  [{:keys [::audit/context ::audit/props ::audit/id] :as event}]
  {:id               id
   :type             "exception"
   :origin           "audit-log"
   :hint             (get props :hint)
   :tenant           (cf/get :tenant)
   :host             (cf/get :host)
   :backend-version  (:full cf/version)
   :frontend-version (:version context)
   :profile-id       (:audit/profile-id event)
   :href             (get props :href)})

(defn- rlimit-event->report
  [event]
  {:id               (::rlimit/id event)
   :type             "notification"
   :origin           "rlimit"
   :hint             (str "rlimit reject of "
                          (::rlimit/method event)
                          " for "
                          (::rlimit/uid event))
   :tenant           (cf/get :tenant)
   :host             (cf/get :host)
   :backend-version  (:full cf/version)
   :profile-id       (::rlimit/profile-id event)
   :info             (with-out-str
                       (println "Rejected by:")
                       (println "------------")
                       (println "Method:        " (::rlimit/method event))
                       (println "Limit Name:    " (::rlimit/name event))
                       (println "Limit Strategy:" (::rlimit/strategy event))
                       (println)
                       (println "Results & Config:")
                       (println "-----------------")
                       (doseq [result (::rlimit/results event)]
                         (pp/pprint (into (sorted-map) result))))})

(defn- handle-event
  [cfg event event->report]
  (try
    (let [report (event->report event)]
      (send-mattermost-notification! cfg report))
    (catch Throwable cause
      (l/warn :hint "unhandled error" :cause cause))))

(defmethod ig/assert-key ::reporter
  [_ params]
  (assert (http/client? (::http/client params)) "expect valid http client"))

(defmethod ig/init-key ::reporter
  [_ cfg]
  (when-let [uri (cf/get :error-report-webhook)]
    (let [input  (sp/chan :buf (sp/sliding-buffer 256))
          thread (px/thread
                   {:name "penpot/reporter/mattermost"}
                   (l/info :hint "initializing error reporter" :uri uri)

                   (try
                     (loop []
                       (when-let [item (sp/take! input)]
                         (when @enabled
                           (cond
                             (::l/id item)
                             (handle-event cfg item log-record->report)

                             (::audit/id item)
                             (handle-event cfg item audit-event->report)

                             (::rlimit/id item)
                             (handle-event cfg item rlimit-event->report)

                             :else
                             (l/warn :hint "received unexpected item" :item item)))

                         (recur)))
                     (catch InterruptedException _
                       (l/debug :hint "reporter interrupted"))
                     (catch Throwable cause
                       (l/error :hint "unexpected error" :cause cause))
                     (finally
                       (l/info :hint "reporter terminated"))))]

      (add-watch l/log-record ::reporter
                 (fn [_ _ _ record]
                   (when (= :error (::l/level record))
                     (sp/put! input record))))

      {::input input
       ::thread thread})))

(defmethod ig/halt-key! ::reporter
  [_ {:keys [::input ::thread]}]
  (remove-watch l/log-record ::reporter)
  (some-> input sp/close!)
  (some-> thread px/interrupt!))

(defn emit
  "Emit an event/report into the mattermost reporter"
  [cfg event]
  (when-let [{:keys [::input]} (get cfg ::reporter)]
    (sp/put! input event)))
