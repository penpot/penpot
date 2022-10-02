;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.mattermost
  "A mattermost integration for error reporting."
  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [app.loggers.database :as ldb]
   [app.util.json :as json]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]))

(defonce enabled (atom true))

(defn- send-mattermost-notification!
  [{:keys [http-client] :as cfg} {:keys [host id public-uri] :as event}]
  (let [uri  (:uri cfg)
        text (str "Exception on (host: " host ", url: " public-uri "/dbg/error/" id ")\n"
                  (when-let [pid (:profile-id event)]
                    (str "- profile-id: #uuid-" pid "\n")))]
    (p/then
     (http-client {:uri uri
                   :method :post
                   :headers {"content-type" "application/json"}
                   :body (json/write-str {:text text})})
     (fn [{:keys [status] :as rsp}]
       (when (not= status 200)
         (l/warn :hint "error on sending data to mattermost"
                 :response (pr-str rsp)))))))

(defn handle-event
  [cfg event]
  (let [ch (a/chan)]
    (-> (p/let [event (ldb/parse-event event)]
          (send-mattermost-notification! cfg event))
        (p/finally (fn [_ cause]
                     (when cause
                       (l/warn :hint "unexpected exception on error reporter" :cause cause))
                     (a/close! ch))))
    ch))

(s/def ::http-client fn?)
(s/def ::uri ::cf/error-report-webhook)

(defmethod ig/pre-init-spec ::reporter [_]
  (s/keys :req-un [::http-client ::receiver]
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
            (l/info :msg "stopping error reporting loop")
            (do
              (a/<! (handle-event cfg msg))
              (recur)))))
      output)))

(defmethod ig/halt-key! ::reporter
  [_ output]
  (when output
    (a/close! output)))
