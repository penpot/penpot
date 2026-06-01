;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

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
  (let [wasm-override (-> (rt/get-params state)
                          (get :wasm))

        ;; When the :render-switch feature flag is active, read the user's
        ;; preferred renderer from their profile settings (default :svg).
        renderer      (when (contains? cf/flags :render-switch)
                        (dm/get-in state [:profile :props :renderer] :svg))

        features      (cond
                        ;; Priority 1 — URL query param (?wasm=true|false)
                        ;; overrides everything: user profile and team flags.
                        (= "true" wasm-override)
                        (conj features "render-wasm/v1")

                        (= "false" wasm-override)
                        (disj features "render-wasm/v1")

                        ;; Priority 2 — User profile preference.
                        ;; If renderer is non-nil, the :render-switch flag is
                        ;; active and profile data has loaded. Respect the
                        ;; user's saved choice (:wasm or :svg).
                        (some? renderer)
                        (if (= :wasm renderer)
                          (conj features "render-wasm/v1")
                          (disj features "render-wasm/v1"))

                        ;; Priority 3 — Fall back to the team-level
                        ;; feature set unchanged (no override).
                        :else
                        features)]

    ;; The WASM renderer requires the v2 text editor (hard dependency).
    ;; Ensure it's always enabled whenever render-wasm/v1 is active.
    (if (contains? features "render-wasm/v1")
      (conj features "text-editor/v2")
      (disj features "text-editor/v2"))))

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
  "Given a state and feature, check if feature is enabled.
  Relies on the pre-computed :features set in state, which already
  incorporates URL overrides, user profile preferences, team flags,
  and runtime toggles via setup-wasm-features."
  [state feature]
  (assert (contains? cfeat/supported-features feature)
          "feature not supported")
  (contains? (:features state) feature))

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
  "A react hook that checks if feature is currently enabled.
  Uses the pre-computed :features set from state — the same set that
  setup-wasm-features has already resolved."
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
        (if (contains? features "render-wasm/v1")
          (wasm/initialize true)
          (wasm/initialize false))

        (log/inf :hint "recomputed features"
                 :enabled (str/join " " features))))))
