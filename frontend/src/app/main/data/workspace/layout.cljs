;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.layout
  "Workspace layout management events and helpers."
  (:require
   [app.common.data.macros :as dm]
   [app.util.storage :refer [storage]]
   [clojure.set :as set]
   [potok.core :as ptk]))

(def valid-flags
  #{:sitemap
    :layers
    :comments
    :assets
    :document-history
    :colorpalette
    :element-options
    :rules
    :display-grid
    :snap-grid
    :scale-text
    :dynamic-alignment
    :display-artboard-names
    :snap-guides
    :show-pixel-grid
    :snap-pixel-grid})

(def presets
  {:assets
   {:del #{:sitemap :layers :document-history}
    :add #{:assets}}

   :document-history
   {:del #{:assets :layers :sitemap}
    :add #{:document-history}}

   :layers
   {:del #{:document-history :assets}
    :add #{:sitemap :layers}}})

(def valid-options-mode
  #{:design :prototype :inspect})

(def default-layout
  #{:sitemap
    :layers
    :element-options
    :rules
    :display-grid
    :snap-grid
    :dynamic-alignment
    :display-artboard-names
    :snap-guides
    :show-pixel-grid
    :snap-pixel-grid})

(def default-global
  {:options-mode :design})

(defn ensure-layout
  [name]
  (ptk/reify ::ensure-layout
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-layout
              (fn [stored]
                (let [todel (get-in presets [name :del] #{})
                      toadd (get-in presets [name :add] #{})]
                  (-> stored
                      (set/difference todel)
                      (set/union toadd))))))))

(declare persist-layout-flags!)

(defn toggle-layout-flag
  [flag & {:keys [force?] :as opts}]
  (ptk/reify ::toggle-layout-flag
    IDeref
    (-deref [_] {:name flag})

    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-layout
              (fn [flags]
                (if force?
                  (conj flags flag)
                  (if (contains? flags flag)
                    (disj flags flag)
                    (conj flags flag))))))

    ptk/EffectEvent
    (effect [_ state _]
      (let [flags (:workspace-layout state)]
        (persist-layout-flags! flags)))))

(defn remove-layout-flag
  [flag]
  (ptk/reify ::remove-layout-flag
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-layout
              (fn [flags]
                (disj flags flag))))

    ptk/EffectEvent
    (effect [_ state _]
      (let [flags (:workspace-layout state)]
        (persist-layout-flags! flags)))))

(defn set-options-mode
  [mode]
  (dm/assert! (contains? valid-options-mode mode))
  (ptk/reify ::set-options-mode
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-global :options-mode] mode))))

(def layout-flags-persistence-mapping
  "A map of layout flags that should be persisted in local storage; the
  value corresponds to the key that will be used for save the data in
  storage object. It should be namespace qualified."
  {:colorpalette :app.main.data.workspace/show-colorpalette?
   :textpalette :app.main.data.workspace/show-textpalette?})

(defn load-layout-flags
  "Given the current layout flags, and updates them with the data
  stored in Storage."
  [layout]
  (reduce (fn [layout [flag key]]
            (condp = (get @storage key ::none)
              ::none layout
              false  (disj layout flag)
              true   (conj layout flag)))
          layout
          layout-flags-persistence-mapping))

(defn persist-layout-flags!
  "Given a set of layout flags, and persist a subset of them to the Storage."
  [layout]
  (doseq [[flag key] layout-flags-persistence-mapping]
    (swap! storage assoc key (contains? layout flag))))

(def layout-state-persistence-mapping
  "A mapping of keys that need to be persisted from `:workspace-global` into Storage."
  {:selected-palette :app.main.data.workspace/selected-palette
   :selected-palette-colorpicker :app.main.data.workspace/selected-palette-colorpicker})

(defn load-layout-state
  "Given state (the :workspace-global) and update it with layout related
  props that are previously persisted in the Storage."
  [state]
  (reduce (fn [state [key skey]]
            (let [val (get @storage skey ::none)]
              (if (= val ::none)
                state
                (assoc state key val))))
          state
          layout-state-persistence-mapping))

(defn persist-layout-state!
  "Given state (the :workspace-global) and persists a subset of layout
  related props to the Storage."
  [state]
  (doseq [[key skey] layout-state-persistence-mapping]
    (let [val (get state key ::does-not-exist)]
      (if (= val ::does-not-exist)
        (swap! storage dissoc skey)
        (swap! storage assoc skey val)))))


