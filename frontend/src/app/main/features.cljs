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
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(log/set-level! :trace)

(defn get-enabled-features
  ([]
   (get-enabled-features @st/state))
  ([state]
   (get state :features #{})))

(def features
  (l/derived get-enabled-features st/state =))

(defn active-feature?
  ([feature]
   (active-feature? @st/state feature))
  ([state feature]
   (assert (contains? cfeat/supported-features feature) "Not supported feature")
   (let [features (get-enabled-features state)]
     (contains? features feature))))

(defn toggle-feature
  [feature]
  (ptk/reify ::toggle-feature
    ptk/UpdateEvent
    (update [_ state]
      (assert (contains? cfeat/supported-features feature) "Not supported feature")
      (if (active-feature? state feature)
        (do
          (log/trc :hint "feature disabled" :feature feature)
          (update state :features disj feature))
        (do
          (log/trc :hint "feature enabled" :feature feature)
          (update state :features conj feature))))))

(defn enable-feature
  [feature]
  (ptk/reify ::enable-feature
    ptk/UpdateEvent
    (update [_ state]
      (assert (contains? cfeat/supported-features feature) "Not supported feature")
      (if (active-feature? state feature)
        state
        (do
          (log/trc :hint "feature enabled" :feature feature)
          (update state :features conj feature))))))

(defn use-feature
  [feature]
  (assert (contains? cfeat/supported-features feature) "Not supported feature")
  (let [active-features (mf/deref features)]
    (contains? active-features feature)))

(defn initialize
  ([] (initialize #{}))
  ([features]
   (assert (set? features) "expected a set of features")
   (assert (every? string? features) "expected a set of strings")

   (ptk/reify ::initialize
     ptk/UpdateEvent
     (update [_ state]
       (let [features (into #{}
                            (filter #(contains? cfeat/supported-features %))
                            features)
             features (into features
                            (filter #(not (str/starts-with? "storage/" %)))
                            (cfeat/get-enabled-features cf/flags))
             features (if *assert*
                        (into features
                              (comp
                               (filter #(not= % "components/v2"))
                               (filter #(not= % "styles/v2"))
                               (filter #(not (str/starts-with? % "storage/"))))
                              cfeat/supported-features)
                        features)]
         (assoc state :features features)))

     ptk/EffectEvent
     (effect [_ state _]
       (let [features (str/join "," (:features state))]
         (log/trc :hint "initialized features" :features features))))))
