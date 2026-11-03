;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.cli.console
  "Console settings carried by resolved benchmark requests."
  (:require
   [benches.render-wasm.node.cli.shared :as shared]))

(def schema:console
  [:map {:closed true}
   [:level {:default :info} [:enum :info :warn :debug]]
   [:color {:default :auto} [:enum :auto :always :never]]])

(def cli-options
  [["-q" "--quiet" "Only warnings, errors and results"]
   ["-v" "--verbose" "Show debug details and raw build output"]
   [nil "--[no-]color" "Colorize terminal output"]])

(defn console-level
  "Returns the console level selected by quiet or verbose options."
  [opts]
  (cond (:quiet opts) :warn (:verbose opts) :debug :else :info))

(defn color-mode
  "Returns automatic, forced or disabled console coloring."
  [opts]
  (case (:color opts) true :always false :never :auto))

(defn resolve-options
  "Resolves supplied console flags into console settings."
  [options]
  (when (and (:quiet options) (:verbose options))
    (throw (ex-info "--quiet and --verbose are mutually exclusive"
                    {:type ::shared/invalid-args})))
  (cond-> {}
    (or (:quiet options) (:verbose options)) (assoc :level (console-level options))
    (contains? options :color) (assoc :color (color-mode options))))
