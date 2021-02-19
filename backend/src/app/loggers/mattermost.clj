;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.loggers.mattermost
  "A mattermost integration for error reporting."
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.util.async :as aa]
   [app.util.http :as http]
   [app.util.json :as json]
   [app.util.template :as tmpl]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Error Listener
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare handle-event)

(defonce enabled-mattermost (atom true))

(s/def ::uri ::us/string)

(defmethod ig/pre-init-spec ::reporter [_]
  (s/keys :req-un [::wrk/executor ::db/pool ::receiver]
          :opt-un [::uri]))

(defmethod ig/init-key ::reporter
  [_ {:keys [receiver] :as cfg}]
  (log/info "Intializing mattermost error reporter.")
  (let [output (a/chan (a/sliding-buffer 128)
                       (filter #(= (:level %) "error")))]
    (receiver :sub output)
    (a/go-loop []
      (let [msg (a/<! output)]
        (if (nil? msg)
          (log/info "Stoping error reporting loop.")
          (do
            (a/<! (handle-event cfg msg))
            (recur)))))
    output))

(defmethod ig/halt-key! ::reporter
  [_ output]
  (a/close! output))

(defn- send-mattermost-notification!
  [cfg {:keys [host version id error] :as cdata}]
  (try
    (let [uri  (:uri cfg)
          text (str "Unhandled exception (@channel):\n"
                    "- detail: " (:public-uri cfg/config) "/dbg/error-by-id/" id "\n"
                    "- host: `" host "`\n"
                    "- version: `" version "`\n"
                    (when error
                      (str "```\n" (:trace error)  "\n```")))
          rsp    (http/send! {:uri uri
                              :method :post
                              :headers {"content-type" "application/json"}
                              :body (json/encode-str {:text text})})]
      (when (not= (:status rsp) 200)
        (log/errorf "Error on sending data to mattermost\n%s" (pr-str rsp))))

    (catch Exception e
      (log/error e "Unexpected exception on error reporter."))))

(defn- persist-on-database!
  [{:keys [pool] :as cfg} {:keys [id] :as cdata}]
  (db/with-atomic [conn pool]
    (db/insert! conn :server-error-report
                {:id id :content (db/tjson cdata)})))

(defn- parse-context
  [event]
  (reduce-kv
   (fn [acc k v]
     (cond
       (= k :id)         (assoc acc k (uuid/uuid v))
       (= k :profile-id) (assoc acc k (uuid/uuid v))
       (str/blank? v)    acc
       :else             (assoc acc k v)))
   {:id (uuid/next)}
   (:context event)))

(defn- parse-event
  [event]
  (-> (parse-context event)
      (merge (dissoc event :context))
      (assoc :tenant (cfg/get :tenant))
      (assoc :host (cfg/get :host))
      (assoc :public-uri (cfg/get :public-uri))
      (assoc :version (:full cfg/version))))

(defn handle-event
  [{:keys [executor] :as cfg} event]
  (aa/with-thread executor
    (try
      (let [cdata (parse-event event)]
        (when (and (:uri cfg) @enabled-mattermost)
          (send-mattermost-notification! cfg cdata))
        (persist-on-database! cfg cdata))
      (catch Exception e
        (log/error e "Unexpected exception on error reporter.")))))

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
