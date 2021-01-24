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
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.tasks :as tasks]
   [app.worker :as wrk]
   [app.util.json :as json]
   [app.util.http :as http]
   [app.util.template :as tmpl]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [clojure.java.io :as io]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.exec :as px])
  (:import
   org.apache.logging.log4j.core.LogEvent
   org.apache.logging.log4j.util.ReadOnlyStringMap))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Error Listener
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare handle-event)

(defonce queue (a/chan (a/sliding-buffer 64)))
(defonce queue-fn (fn [event] (a/>!! queue event)))

(s/def ::uri ::us/string)

(defmethod ig/pre-init-spec ::reporter [_]
  (s/keys :req-un [::wrk/executor ::db/pool]
          :opt-un [::uri]))

(defmethod ig/init-key ::reporter
  [_ {:keys [executor uri] :as cfg}]
  (log/info "Intializing error reporter.")
  (let [close-ch (a/chan 1)]
    (a/go-loop []
      (let [[val port] (a/alts! [close-ch queue])]
        (cond
          (= port close-ch)
          (log/info "Stoping error reporting loop.")

          (nil? val)
          (log/info "Stoping error reporting loop.")

          :else
          (do
            (px/run! executor #(handle-event cfg val))
            (recur)))))
    close-ch))

(defmethod ig/halt-key! ::reporter
  [_ close-ch]
  (a/close! close-ch))

(defn- get-context-data
  [event]
  (let [^LogEvent levent (deref event)
        ^ReadOnlyStringMap rosm (.getContextData levent)]
    (into {:message (str event)
           :id      (uuid/next)} ; set default uuid for cases when it not comes.
          (comp
           (map (fn [[key val]]
                  (cond
                    (= "id" key)         [:id (uuid/uuid val)]
                    (= "profile-id" key) [:profile-id (uuid/uuid val)]
                    (str/blank? val)     nil
                    (string? key)        [(keyword key) val]
                    :else                [key val])))
           (filter some?))

          (.toMap rosm))))

(defn- send-mattermost-notification!
  [cfg {:keys [message host version id] :as cdata}]
  (try
    (let [uri    (:uri cfg)
          prefix (str/<< "Unhandled exception (@channel):\n"
                         "- detail: ~(:public-uri cfg/config)/dbg/error-by-id/~{id}\n"
                         "- host: `~{host}`\n"
                         "- version: `~{version}`\n")
          text   (str prefix "```\n" message "\n```")
          rsp    (http/send! {:uri uri
                              :method :post
                              :headers {"content-type" "application/json"}
                              :body (json/encode-str {:text text})})]
      (when (not= (:status rsp) 200)
        (log/warnf "Error reporting webhook replying with unexpected status: %s\n%s"
                   (:status rsp)
                   (pr-str rsp))))

    (catch Exception e
      (log/warnf e "Unexpected exception on error reporter."))))

(defn- persist-on-database!
  [{:keys [pool] :as cfg} {:keys [id] :as cdata}]
  (db/with-atomic [conn pool]
    (db/insert! conn :server-error-report
                {:id id :content (db/tjson cdata)})))

(defn handle-event
  [cfg event]
  (try
    (let [cdata (get-context-data event)]
      (when (:uri cfg)
        (send-mattermost-notification! cfg cdata))
      (persist-on-database! cfg cdata))
    (catch Exception e
      (log/warnf e "Unexpected exception on error reporter."))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Http Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (letfn [(parse-id [request]
            (let [id (get-in request [:path-params :id])
                  id (us/uuid-conformer id)]
              (when (uuid? id)
                id)))
          (retrieve-report [id]
            (ex/ignoring
             (when-let [{:keys [content] :as row} (db/get-by-id pool :server-error-report id)]
               (assoc row :content (db/decode-transit-pgobject content)))))

          (render-template [{:keys [content] :as report}]
            (some-> (io/resource "error-report.tmpl")
                    (tmpl/render content)))]


    (fn [request]
      (let [result (some-> (parse-id request)
                           (retrieve-report)
                           (render-template))]
        (if result
          {:status 200
           :headers {"content-type" "text/html; charset=utf-8"}
           :body result}
          {:status 404
           :body "not found"})))))
