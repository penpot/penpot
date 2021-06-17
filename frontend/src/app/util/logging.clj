;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.logging)

(defn- log-expr [_form level keyvals]
  (let [keyvals-map (apply array-map keyvals)
        ;;formatter (::formatter keyvals-map 'identity)
        ]
    `(log ~(::logger keyvals-map (str *ns*))
          ~level
          ~(-> keyvals-map
               (dissoc ::logger)
               #_(assoc :line (:line (meta form))))
          ~(:err keyvals-map))))

(defmacro set-level!
  ([level]
   `(set-level* ~(str *ns*) ~level))
  ([n level]
   `(set-level* ~n ~level)))

(defmacro error [& keyvals]
  (log-expr &form :error keyvals))

(defmacro warn [& keyvals]
  (log-expr &form :warn keyvals))

(defmacro info [& keyvals]
  (log-expr &form :info keyvals))

(defmacro debug [& keyvals]
  (log-expr &form :debug keyvals))

(defmacro trace [& keyvals]
  (log-expr &form :trace keyvals))

(defmacro spy [form]
  (let [res (gensym)]
    `(let [~res ~form]
       ~(log-expr &form :debug [:spy `'~form
                                :=> res])
       ~res)))
