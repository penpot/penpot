;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.undo
  (:require [cuerdas.core :as str]
            [promesa.core :as p]
            [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.util.rstore :as rs]
            [uxbox.util.router :as r]
            [uxbox.main.repo :as rp]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.schema :as sc]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.state :as st]
            [uxbox.util.datetime :as dt]
            [uxbox.util.data :refer (without-keys
                                     replace-by-id
                                     index-by)]))

(defrecord SaveUndoEntry [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [page (udp/pack-page state id)]
      (update-in state [:undo id :stack] (fnil conj []) page)))

  rs/EffectEvent
  (-apply-effect [_ state]
    (let [undo (get-in state [:undo id])]
      (println (pr-str undo)))))

(defn watch-page-changes
  "A function that starts watching for `IPageUpdate`
  events emited to the global event stream and just
  reacts on them emiting an other event that just
  persists the state of the page in an undo stack."
  [id]
  (letfn [(on-value []
            (rs/emit! (->SaveUndoEntry id)))]
    (as-> rs/stream $
      (rx/filter #(satisfies? udp/IPageUpdate %) $)
      (rx/debounce 500 $)
      (rx/on-next $ on-value))))

