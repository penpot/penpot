;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.impl
  "Dispatcher for messages received from the main thread."
  (:require
   [app.common.logging :as log]
   [app.config :as cf]))

(log/set-level! :info)

(enable-console-print!)

;; --- Handler

(defmulti handler :cmd)

(defmethod handler :default
  [message]
  (log/warn :hint "unexpected message" :message message))

(defmethod handler :echo
  [message]
  message)

(defmethod handler :configure
  [{:keys [config]}]
  (log/info :hint "configure worker" :keys (keys config))

  (when-let [public-uri (get config :public-uri)]
    (set! cf/public-uri public-uri))

  (when-let [version (get config :version)]
    (set! cf/version version))

  (when-let [build-date (get config :build-data)]
    (set! cf/build-date build-date))

  nil)
