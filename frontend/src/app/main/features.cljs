;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.features
  "A thin, frontend centric abstraction layer and collection of
  helpers for `app.common.features` namespace."
  (:require
   [app.common.features :as cfeat]
   [app.common.logging :as log]
   [app.config :as cf]
   [app.main.store :as st]
   [beicon.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(log/set-level! :trace)

(def global-enabled-features
  (cfeat/get-enabled-features cf/flags))

(defn get-enabled-features
  [state]
  (-> (get state :features/runtime #{})
      (set/intersection cfeat/no-migration-features)
      (set/union global-enabled-features)))

(defn get-team-enabled-features
  [state]
  (let [runtime-features (set/intersection (:features/runtime state #{})
                                           cfeat/no-migration-features)]
    (-> global-enabled-features
        (set/union runtime-features)
        (set/union (:features/team state #{})))))

(def features-ref
  (l/derived get-team-enabled-features st/state =))

(defn active-feature?
  "Given a state and feature, check if feature is enabled"
  [state feature]
  (assert (contains? cfeat/supported-features feature) "not supported feature")
  (or (contains? (get state :features/runtime) feature)
      (if (contains? cfeat/no-migration-features feature)
        (or (contains? global-enabled-features feature)
            (contains? (get state :features/team) feature))
        (contains? (get state :features/team state) feature))))

(defn use-feature
  "A react hook that checks if feature is currently enabled"
  [feature]
  (assert (contains? cfeat/supported-features feature) "Not supported feature")
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
      (update state :features/runtime (fn [features]
                                        (if (contains? features feature)
                                          (do
                                            (log/trc :hint "feature disabled" :feature feature)
                                            (disj features feature))
                                          (do
                                            (log/trc :hint "feature enabled" :feature feature)
                                            (conj features feature))))))))

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
          (update state :features/runtime (fnil conj #{}) feature))))))

(defn initialize
  ([] (initialize #{}))
  ([team-features]
   (assert (set? team-features) "expected a set of features")
   (assert (every? string? team-features) "expected a set of strings")

   (ptk/reify ::initialize
     ptk/UpdateEvent
     (update [_ state]
       (let [runtime-features (get state :features/runtime #{})
             team-features    (into cfeat/default-enabled-features
                                    cfeat/xf-supported-features
                                    team-features)]
         (-> state
             (assoc :features/runtime runtime-features)
             (assoc :features/team team-features))))

     ptk/WatchEvent
     (watch [_ _ _]
       (when *assert*
         (->> (rx/from cfeat/no-migration-features)
              (rx/filter #(not (contains? cfeat/backend-only-features %)))
              (rx/observe-on :async)
              (rx/map enable-feature))))

     ptk/EffectEvent
     (effect [_ state _]
       (log/trc :hint "initialized features"
                :team (str/join "," (:features/team state))
                :runtime (str/join "," (:features/runtime state)))))))

