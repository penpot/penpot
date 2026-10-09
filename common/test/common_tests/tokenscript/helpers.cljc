;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.tokenscript.helpers
  "Shared helpers for the tokenscript tests."
  (:require
   [app.common.tokenscript :as tsc]
   [app.common.tokenscript.config :as config]
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.interpreter :as interp]
   [app.common.tokenscript.parser :as parser]
   [app.common.tokenscript.symbols :as sym]))

(def ^:private core-config*
  (delay (config/make-config)))

(defn core-config
  "Config with the core types only (hex colors and default units)."
  []
  @core-config*)

(defn full-config
  "Config with the bundled Penpot schemas."
  []
  (tsc/default-config))

(defn evaluate
  "Evaluates `text` in script mode, returning the result symbol."
  ([text] (evaluate text nil))
  ([text references] (evaluate text references nil))
  ([text references {:keys [config mode] :or {mode :script}}]
   (interp/evaluate text {:references (or references {})
                          :config (or config (core-config))
                          :mode mode})))

(defn ev
  "Evaluates `text` and renders the result as a string."
  ([text] (ev text nil))
  ([text references] (ev text references nil))
  ([text references opts]
   (sym/->string-result (evaluate text references opts) (or (:config opts) (core-config)))))

(defn ev-inline
  "Evaluates a token `$value` expression (inline mode) as a string."
  ([text] (ev-inline text nil))
  ([text references] (ev text references {:mode :inline})))

(defn ev-type
  "Full type name of the evaluation result."
  ([text] (ev-type text nil))
  ([text references] (sym/result-type-name (evaluate text references))))

(defn error-code
  "Error code raised by `thunk`, or nil when it does not raise."
  [thunk]
  (try
    (thunk)
    nil
    (catch #?(:clj Throwable :cljs :default) e
      (if (err/language-error? e)
        (err/code e)
        (throw e)))))

(defn ev-error
  "Error code raised by evaluating `text`."
  ([text] (ev-error text nil))
  ([text references] (error-code #(evaluate text references))))

(defn parse-error
  [text opts]
  (error-code #(parser/parse text opts)))

(defn variables
  "Final variable table of a script, keyed by lower case name."
  [text]
  (:table (interp/interpret-with-table (:ast (parser/parse text)) {:config (core-config)})))
