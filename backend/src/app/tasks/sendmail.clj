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
   [clojure.tools.logging :as log]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.util.emails :as emails]
   [app.config :as cfg]
   [app.metrics :as mtx]))

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
  {:app.tasks/name "sendmail"}
  [{:keys [props] :as task}]
  (if (:smtp-enable cfg/config)
    (-> (cfg/smtp cfg/config)
        (emails/send! props))
    (send-console! props)))

(mtx/instrument-with-summary!
 {:var #'handler
  :id "tasks__sendmail"
  :help "Timing of sendmail task."})
