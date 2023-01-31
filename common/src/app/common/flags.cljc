;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.flags
  "Flags parsing algorithm."
  (:require
   [cuerdas.core :as str]))

(def default
  "A common flags that affects both: backend and frontend."
  [:enable-registration
   :enable-login-with-password])

(defn parse
  [& flags]
  (loop [flags  (apply concat flags)
         result #{}]
    (let [item (first flags)]
      (if (nil? item)
        result
        (let [sname (name item)]
          (cond
            (str/starts-with? sname "enable-")
            (recur (rest flags)
                   (conj result (keyword (subs sname 7))))

            (str/starts-with? sname "disable-")
            (recur (rest flags)
                   (disj result (keyword (subs sname 8))))

            :else
            (recur (rest flags) result)))))))


