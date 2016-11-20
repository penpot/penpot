;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.exceptions
  "A helpers for work with exceptions.")

(defn error
  [& {:keys [type code message] :or {type :unexpected} :as payload}]
  {:pre [(keyword? type) (keyword? code)]}
  (let [message (if message
                  (str message " / " (pr-str code) "")
                  (pr-str code))
        payload (assoc payload :type type)]
    (ex-info message payload)))

(defmacro raise
  [& args]
  `(throw (error ~@args)))
