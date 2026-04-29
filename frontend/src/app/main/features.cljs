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
        renderer     (when (contains? cf/flags :render-switch)
                       (-> state :profile :props :renderer))
        enable-wasm  (or (= "true" wasm) (and (= renderer :wasm) (not= "false" wasm)))
        disable-wasm (or (= "false" wasm) (and (= renderer :svg) (not= "true" wasm)))
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

(defn enabled-by-flags?
  [{:keys [features-runtime features]} feature]
  (or (contains? features-runtime feature)
      (contains? features feature)))

(defn enabled-without-migration?
  [{:keys [features-runtime features]} feature]
  (or (contains? features-runtime feature)
      (contains? global-enabled-features feature)
      (contains? features feature)))

(defn wasm-url-override
  [state]
  (case (get (rt/get-params state) :wasm)
    "true"  true
    "false" false
    nil))

(def wasm-url-override-ref
  (l/derived wasm-url-override st/state))

(defn- wasm-enabled?
  [state]
  (let [override (wasm-url-override state)
        renderer (when (contains? cf/flags :render-switch)
                   (-> state :profile :props :renderer))]
    (cond
      (some? override)
      override

      (contains? cf/flags :render-switch)
      (case renderer
        :wasm true
        :svg false
        ;; SVG renderer as default until profile data arrives OR if render-switch
        ;; flag is disabled.
        false)

      (contains? cfeat/no-migration-features "render-wasm/v1")
      (enabled-without-migration? state "render-wasm/v1")

      :else
      (enabled-by-flags? state "render-wasm/v1"))))

(defn active-feature?
  "Given a state and feature, check if feature is enabled."
  [state feature]
  (assert (contains? cfeat/supported-features feature)
          "feature not supported")

  (cond
    (= feature "render-wasm/v1")
    (wasm-enabled? state)

    (contains? cfeat/no-migration-features feature)
    (enabled-without-migration? state feature)

    :else
    (enabled-by-flags? state feature)))

(defn active-features?
  "Given a state and a set of features, check if the features are all enabled."
  ([state a]
   (js/console.warn "Please, use active-feature? instead")
   (active-feature? state a))
  ([state a b]
   (and ^boolean (active-feature? state a)
        ^boolean (active-feature? state b)))
  ([state a b c]
   (and ^boolean (active-feature? state a)
        ^boolean (active-feature? state b)
        ^boolean (active-feature? state c)))
  ([state a b c & others]
   (and ^boolean (active-feature? state a)
        ^boolean (active-feature? state b)
        ^boolean (active-feature? state c)
        ^boolean (every? #(active-feature? state %) others))))

(def ^:private features-ref
  (l/derived (l/key :features) st/state))

(defn use-feature
  "A react hook that checks if feature is currently enabled"
  [feature]
  (let [enabled-features (mf/deref features-ref)
        wasm-override (mf/deref wasm-url-override-ref)
        renderer      (mf/deref (l/derived #(-> % :profile :props :renderer) st/state))
        wasm-enabled  (cond
                        (some? wasm-override)
                        wasm-override

                        (contains? cf/flags :render-switch)
                        (= renderer :wasm)

                        :else
                        (contains? enabled-features "render-wasm/v1"))]
    (cond
      (= feature "render-wasm/v1")
      wasm-enabled

      :else
      (contains? enabled-features feature))))

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
        (if (active-feature? state "render-wasm/v1")
          (wasm/initialize true)
          (wasm/initialize false))

        (log/inf :hint "initialized"
                 :enabled (str/join " " features))))))

(defn recompute-features
  []
  (ptk/reify ::recompute-features
    ptk/UpdateEvent
    (update [_ state]
      (let [previous (or (get state :features) #{})
            features (setup-wasm-features previous state)]
        (if (= previous features)
          state
          (assoc state :features features))))

    ptk/EffectEvent
    (effect [_ state _]
      (let [features (get state :features)]
        (if (active-feature? state "render-wasm/v1")
          (wasm/initialize true)
          (wasm/initialize false))

        (log/inf :hint "recomputed features"
                 :enabled (str/join " " features))))))
