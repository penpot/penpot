;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.error-reporter
  "A mattermost integration for error reporting."
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.db :as db]
   [app.tasks :as tasks]
   [app.util.async :as aa]
   [app.worker :as wrk]
   [app.util.http :as http]
   [clojure.core.async :as a]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [mount.core :as mount :refer [defstate]]
   [promesa.exec :as px]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce enqueue identity)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- send-to-mattermost!
  [log-event]
  (try
    (let [text  (str/fmt "Unhandled exception: `host='%s'`, `version=%s`.\n@channel ⇊\n```%s\n```"
                         (:host cfg/config)
                         (:full @cfg/version)
                         (str log-event))
          rsp   (http/send! {:uri (:error-reporter-webhook cfg/config)
                             :method :post
                             :headers {"content-type" "application/json"}
                             :body (json/write-str {:text text})})]
      (when (not= (:status rsp) 200)
        (log/warnf "Error reporting webhook replying with unexpected status: %s\n%s"
                   (:status rsp)
                   (pr-str rsp))))
    (catch Exception e
      (log/warnf e "Unexpected exception on error reporter."))))

(defn- send!
  [val]
  (aa/thread-call wrk/executor (partial send-to-mattermost! val)))

(defn- start
  []
  (let [qch (a/chan (a/sliding-buffer 128))]
    (log/info "Starting error reporter loop.")

    ;; Only enable when a valid URL is provided.
    (when (:error-reporter-webhook cfg/config)
      (alter-var-root #'enqueue (constantly #(a/>!! qch %)))
      (a/go-loop []
        (let [val (a/<! qch)]
          (if (nil? val)
            (do
              (log/info "Closing error reporting loop.")
              (alter-var-root #'enqueue (constantly identity)))
            (do
              (a/<! (send! val))
              (recur))))))

    qch))

(defstate reporter
  :start (start)
  :stop  (a/close! reporter))
