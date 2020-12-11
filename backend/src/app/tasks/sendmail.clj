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
   [app.config :as cfg]
   [app.metrics :as mtx]
   [app.util.emails :as emails]
   [clojure.tools.logging :as log]))

(defn- send-console!
  [config email]
  (let [baos (java.io.ByteArrayOutputStream.)
        mesg (emails/smtp-message config email)]
    (.writeTo mesg baos)
    (let [out (with-out-str
                (println "email console dump:")
                (println "******** start email" (:id email) "**********")
                (println (.toString baos))
                (println "******** end email "(:id email) "**********"))]
      (log/info out))))

(defn handler
  {:app.tasks/name "sendmail"}
  [{:keys [props] :as task}]
  (let [config (cfg/smtp cfg/config)]
    (if (:enabled config)
      (emails/send! config props)
      (send-console! config props))))

(mtx/instrument-with-summary!
 {:var #'handler
  :id "tasks__sendmail"
  :help "Timing of sendmail task."})
