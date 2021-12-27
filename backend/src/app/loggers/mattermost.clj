;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.loggers.mattermost
  "A mattermost integration for error reporting."
  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.database :as ldb]
   [app.util.async :as aa]
   [app.util.http :as http]
   [app.util.json :as json]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(defonce enabled (atom true))

(defn- send-mattermost-notification!
  [cfg {:keys [host id public-uri] :as event}]
  (try
    (let [uri  (:uri cfg)
          text (str "Exception on (host: " host ", url: " public-uri "/dbg/error-by-id/" id ")\n"
                    (when-let [pid (:profile-id event)]
                      (str "- profile-id: #uuid-" pid "\n")))
          rsp  (http/send! {:uri uri
                            :method :post
                            :headers {"content-type" "application/json"}
                            :body (json/write-str {:text text})})]
      (when (not= (:status rsp) 200)
        (l/error :hint "error on sending data to mattermost"
                 :response (pr-str rsp))))

    (catch Exception e
      (l/error :hint "unexpected exception on error reporter"
               :cause e))))

(defn handle-event
  [{:keys [executor] :as cfg} event]
  (aa/with-thread executor
    (try
      (let [event (ldb/parse-event event)]
        (when @enabled
          (send-mattermost-notification! cfg event)))
      (catch Exception e
        (l/warn :hint "unexpected exception on error reporter" :cause e)))))


(s/def ::uri ::cf/error-report-webhook)

(defmethod ig/pre-init-spec ::reporter [_]
  (s/keys :req-un [::wrk/executor ::db/pool ::receiver]
          :opt-un [::uri]))

(defmethod ig/init-key ::reporter
  [_ {:keys [receiver uri] :as cfg}]
  (when uri
    (l/info :msg "initializing mattermost error reporter" :uri uri)
    (let [output (a/chan (a/sliding-buffer 128)
                         (filter (fn [event]
                                   (= (:logger/level event) "error"))))]
      (receiver :sub output)
      (a/go-loop []
        (let [msg (a/<! output)]
          (if (nil? msg)
            (l/info :msg "stoping error reporting loop")
            (do
              (a/<! (handle-event cfg msg))
              (recur)))))
      output)))

(defmethod ig/halt-key! ::reporter
  [_ output]
  (when output
    (a/close! output)))
