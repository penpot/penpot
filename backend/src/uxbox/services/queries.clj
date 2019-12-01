;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries
  (:require
   [uxbox.util.dispatcher :as uds]))

(uds/defservice handle
  {:dispatch-by ::type
   :interceptors [uds/spec-interceptor
                  #_logging-interceptor
                  #_context-interceptor]})

(defmacro defquery
  [key & rest]
  `(uds/defmethod handle ~key ~@rest))
