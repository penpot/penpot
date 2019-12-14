;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.exceptions
  "A helpers for work with exceptions."
  (:require [clojure.spec.alpha :as s]))

(s/def ::type keyword?)
(s/def ::code keyword?)
(s/def ::mesage string?)
(s/def ::hint string?)

(s/def ::error
  (s/keys :req-un [::type]
          :opt-un [::code
                   ::hint
                   ::mesage]))

(defn error
  [& {:keys [type code message hint cause] :as params}]
  (s/assert ::error params)
  (let [message (or message hint "")
        payload (dissoc params :cause :message)]
    (ex-info message payload cause)))

#?(:clj
   (defmacro raise
     [& args]
     `(throw (error ~@args))))
