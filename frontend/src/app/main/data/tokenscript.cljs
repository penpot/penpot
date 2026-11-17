;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.tokenscript
  "Resolves the workspace tokens with the tokenscript interpreter
  (`app.common.logic.tokenscript`), with the messages of the UI in their
  errors. The rem base is `app.common.logic.tokenscript/*base-font-size*`."
  (:require
   [app.common.logic.tokenscript :as cts]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.data.workspace.tokens.warnings :as wtw]))

(defn with-messages
  "`token` with the messages of the UI in its errors and warnings."
  [token]
  (cond-> token
    (seq (:errors token))
    (update :errors (fn [errors] (mapv #(merge (wte/get-error-code (:error/code %)) %) errors)))

    (seq (:warnings token))
    (update :warnings (fn [warnings] (mapv #(merge (wtw/get-warning-code (:warning/code %)) %) warnings)))))

;; Each resolution with messages, by the resolution it comes from
(defonce ^:private resolutions-with-messages (js/WeakMap.))

(defn- tokens-with-messages
  "`tokens`, a resolution, with the messages of the UI in their errors. The
  same object for the same resolution."
  [tokens]
  (or (.get resolutions-with-messages tokens)
      (let [result (update-vals tokens with-messages)]
        (.set resolutions-with-messages tokens result)
        result)))

(defn resolve-sets
  "Resolves the ordered token `sets`, `[[set-id tokens] ..]`, later sets
  taking precedence. Returns a map of token name to token with
  `:resolved-value` and `:resolved-symbol`, or `:errors`, added. See
  `app.common.logic.tokenscript/resolve-sets`."
  [sets]
  (tokens-with-messages (cts/resolve-sets sets)))

(defn resolve-tokens
  "Resolves `tokens` (a map of token name to token), as one set. Returns
  the tokens with `:resolved-value` or `:errors` added."
  [tokens]
  (tokens-with-messages (cts/resolve-tokens tokens)))

(defn resolve-draft
  "Resolves `draft`, a token being edited in a form, on top of `sets` (see
  `cfo/get-token-sets-for-edit`). Returns the resolved draft."
  [sets draft]
  (with-messages (cts/resolve-draft sets draft)))

(defn plugin-value
  "Resolved value of a token as exposed by the plugin API: like
  `:resolved-value` but with typography line heights left as resolved."
  [token]
  (cts/plugin-value token))
