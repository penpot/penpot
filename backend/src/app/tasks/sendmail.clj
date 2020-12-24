;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tasks.sendmail
  (:require
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.metrics :as mtx]
   [app.util.emails :as emails]
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare handler)

(s/def ::username ::cfg/smtp-username)
(s/def ::password ::cfg/smtp-password)
(s/def ::tls ::cfg/smtp-tls)
(s/def ::ssl ::cfg/smtp-ssl)
(s/def ::host ::cfg/smtp-host)
(s/def ::port ::cfg/smtp-port)
(s/def ::default-reply-to ::cfg/smtp-default-reply-to)
(s/def ::default-from ::cfg/smtp-default-from)
(s/def ::enabled ::cfg/smtp-enabled)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::enabled ::mtx/metrics]
          :opt-un [::username
                   ::password
                   ::tls
                   ::ssl
                   ::host
                   ::port
                   ::default-from
                   ::default-reply-to]))

(defmethod ig/init-key ::handler
  [_ {:keys [metrics] :as cfg}]
  (let [handler #(handler cfg %)]
    (->> {:registry (:registry metrics)
          :type :summary
          :name "task_sendmail_timing"
          :help "sendmail task timing"}
         (mtx/instrument handler))))

(defn- send-console!
  [cfg email]
  (let [baos (java.io.ByteArrayOutputStream.)
        mesg (emails/smtp-message cfg email)]
    (.writeTo mesg baos)
    (let [out (with-out-str
                (println "email console dump:")
                (println "******** start email" (:id email) "**********")
                (println (.toString baos))
                (println "******** end email "(:id email) "**********"))]
      (log/info out))))

(defn handler
  [cfg {:keys [props] :as task}]
  (if (:enabled cfg)
    (emails/send! cfg props)
    (send-console! cfg props)))
