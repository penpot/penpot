;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.style-dictionary
  (:require
   [app.common.logging :as l]
   [app.common.logic.tokens-resolution :as ctr]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.main.data.tokenscript :as ts]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.data.workspace.tokens.warnings :as wtw]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(l/set-level! :debug)

;; === Resolution

(defn- with-messages
  "`token` with the messages of the UI in its errors and warnings."
  [token]
  (cond-> token
    (seq (:errors token))
    (update :errors (fn [errors] (mapv #(merge (wte/get-error-code (:error/code %)) %) errors)))

    (seq (:warnings token))
    (update :warnings (fn [warnings] (mapv #(merge (wtw/get-warning-code (:warning/code %)) %) warnings)))))

(defn- resolution
  "Observable of the tokens resolved by the promise `resolved`, with the
  messages of the UI."
  [resolved]
  (->> (rx/from resolved)
       (rx/map #(update-vals % with-messages))))

(defn resolve-tokens
  [tokens]
  (resolution (ctr/resolve-tokens tokens)))

(defn resolve-tokens-interactive
  "Interactive check of resolving tokens: see
  `app.common.logic.tokens-resolution/resolve-tokens-interactive`."
  [tokens]
  (resolution (ctr/resolve-tokens-interactive tokens)))

(defn resolve-tokens-with-verbose-errors [tokens]
  (resolution (ctr/resolve-tokens-with-verbose-errors tokens)))

;; === Hooks

(defonce !tokens-cache (atom nil))

(defn use-resolved-tokens
  "The StyleDictionary process function is async, so we can't use
  resolved values directly.

  This hook will return the unresolved tokens as state until they are
  processed, then the state will be updated with the resolved tokens."
  [tokens & {:keys [cache-atom interactive?]
             :or {cache-atom !tokens-cache}
             :as config}]
  (let [tokens-state (mf/use-state (get @cache-atom tokens))]

    ;; FIXME: this with effect with trigger all the time because
    ;; `config` will be always a different instance
    (mf/with-effect [tokens config]
      (when-not (contains? cf/flags :tokenscript)
        (let [cached (get @cache-atom tokens)]
          (cond
            (nil? tokens) nil
            ;; The tokens are already processing somewhere
            (rx/observable? cached) (rx/sub! cached #(reset! tokens-state %))
            ;; Get the cached entry
            (some? cached) (reset! tokens-state cached)
            ;; No cached entry, start processing
            :else (let [resolved-tokens-s (if interactive?
                                            (resolve-tokens-interactive tokens)
                                            (resolve-tokens tokens))]
                    (swap! cache-atom assoc tokens resolved-tokens-s)
                    (rx/sub! resolved-tokens-s (fn [resolved-tokens]
                                                 (swap! cache-atom assoc tokens resolved-tokens)
                                                 (reset! tokens-state resolved-tokens))))))))
    (if (contains? cf/flags :tokenscript)
      (ts/resolve-tokens tokens)
      @tokens-state)))

(defn use-resolved-tokens*
  "This hook will return the unresolved tokens as state until they are
  processed, then the state will be updated with the resolved tokens.

  This is a cache-less, simplified version of use-resolved-tokens
  hook."
  [tokens & {:keys [interactive?]}]
  (let [state* (mf/use-state tokens)]
    (mf/with-effect [tokens interactive?]
      (when-not (contains? cf/flags :tokenscript)
        (if (seq tokens)
          (let [tpoint  (ct/tpoint-ms)
                tokens-s  (if interactive?
                            (resolve-tokens-interactive tokens)
                            (resolve-tokens tokens))]

            (-> tokens-s
                (rx/sub! (fn [resolved-tokens]
                           (let [elapsed (tpoint)]
                             (l/dbg :hint "use-resolved-tokens*" :elapsed elapsed)
                             (reset! state* resolved-tokens))))))
          (reset! state* tokens))))
    (if (contains? cf/flags :tokenscript)
      (ts/resolve-tokens tokens)
      @state*)))
