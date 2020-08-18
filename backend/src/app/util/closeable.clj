;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns app.util.closeable
  "A closeable abstraction. A drop in replacement for
  clojure builtin `with-open` syntax abstraction."
  (:refer-clojure :exclude [with-open]))

(defprotocol ICloseable
  (-close [_] "Close the resource."))

(defmacro with-open
  [bindings & body]
  {:pre [(vector? bindings)
         (even? (count bindings))
         (pos? (count bindings))]}
  (reduce (fn [acc bindings]
            `(let ~(vec bindings)
               (try
                 ~acc
                 (finally
                   (-close ~(first bindings))))))
          `(do ~@body)
          (reverse (partition 2 bindings))))

(extend-protocol ICloseable
  java.lang.AutoCloseable
  (-close [this] (.close this)))
