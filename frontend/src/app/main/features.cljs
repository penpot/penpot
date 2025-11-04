;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.features
  "A thin, frontend centric abstraction layer and collection of
  helpers for `app.common.features` namespace."
  (:require
   [app.common.data.macros :as dm]
   [app.common.features :as cfeat]
   [app.common.logging :as log]
   [app.config :as cf]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.render-wasm :as wasm]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(log/set-level! :trace)

(def global-enabled-features
  (cfeat/get-enabled-features cf/flags))

(defn setup-wasm-features
  [features state]
  (let [params       (rt/get-params state)
        wasm         (get params :wasm)
        enable-wasm  (= "true" wasm)
        disable-wasm (= "false" wasm)
        features     (cond-> features
                       enable-wasm  (conj "render-wasm/v1")
                       disable-wasm (disj "render-wasm/v1"))]
    ;; If wasm render is enabled text-editor/v2 must be used
    (cond-> features
      (contains? features "render-wasm/v1")
      (conj "text-editor/v2"))))

(defn get-enabled-features
  "An explicit lookup of enabled features for the current team"
  [state team-id]
  (let [team (dm/get-in state [:teams team-id])]
    (-> global-enabled-features
        (set/union (get state :features-runtime #{}))
        (set/intersection cfeat/no-migration-features)
        (set/union (get team :features))
        (setup-wasm-features state))))

(defn active-feature?
  "Given a state and feature, check if feature is enabled."
  [state feature]
  (assert (contains? cfeat/supported-features feature) "feature not supported")
  (let [runtime-features (get state :features-runtime)
        enabled-features (get state :features)]
    (or (contains? runtime-features feature)
        (if (contains? cfeat/no-migration-features feature)
          (or (contains? global-enabled-features feature)
              (contains? enabled-features feature))
          (contains? enabled-features feature)))))

(def ^:private features-ref
  (l/derived (l/key :features) st/state))

(defn use-feature
  "A react hook that checks if feature is currently enabled"
  [feature]
  (let [enabled-features (mf/deref features-ref)]
    (contains? enabled-features feature)))

(defn toggle-feature
  "An event constructor for runtime feature toggle.

  Warning: if a feature is active globally or by team, it can't be
  disabled."
  [feature]
  (ptk/reify ::toggle-feature
    ptk/UpdateEvent
    (update [_ state]
      (assert (contains? cfeat/supported-features feature) "not supported feature")
      (-> state
          (update :features-runtime (fn [features]
                                      (if (contains? features feature)
                                        (do
                                          (log/trc :hint "feature disabled" :feature feature)
                                          (disj features feature))
                                        (do
                                          (log/trc :hint "feature enabled" :feature feature)
                                          (conj features feature)))))
          (update :features-runtime set/intersection cfeat/no-migration-features)))))

(defn enable-feature
  [feature]
  (ptk/reify ::enable-feature
    ptk/UpdateEvent
    (update [_ state]
      (assert (contains? cfeat/supported-features feature) "not supported feature")
      (if (active-feature? state feature)
        state
        (do
          (log/trc :hint "feature enabled" :feature feature)
          (-> state
              (update :features-runtime (fnil conj #{}) feature)
              (update :features-runtime set/intersection cfeat/no-migration-features)))))))

(defn initialize
  [features]
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (let [features (-> global-enabled-features
                         (set/union (get state :features-runtime #{}))
                         (set/union features)
                         (setup-wasm-features state))]
        (assoc state :features features)))

    ptk/EffectEvent
    (effect [_ state _]
      (let [features (get state :features)]
        (if (contains? features "render-wasm/v1")
          (wasm/initialize true)
          (wasm/initialize false))

        (log/inf :hint "initialized"
                 :enabled (str/join " " features))))))
