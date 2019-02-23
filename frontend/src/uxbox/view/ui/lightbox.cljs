;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.ui.lightbox
  (:require [lentes.core :as l]
            [uxbox.view.store :as st]
            [uxbox.view.data.lightbox :as udl]
            [rumext.core :as mx :include-macros true]
            [uxbox.view.ui.keyboard :as k]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer [classnames]]
            [goog.events :as events])
  (:import goog.events.EventType))

;; --- Lentes

(def ^:private lightbox-ref
  (-> (l/key :lightbox)
      (l/derive st/state)))

;; --- Lightbox (Component)

(defmulti render-lightbox :name)
(defmethod render-lightbox :default [_] nil)

(defn- on-esc-clicked
  [event]
  (when (k/esc? event)
    (udl/close!)
    (dom/stop-propagation event)))

(defn- on-out-clicked
  [own event]
  (let [parent (mx/ref-node own "parent")
        current (dom/get-target event)]
    (when (dom/equals? parent current)
      (udl/close!))))

(defn- lightbox-will-mount
  [own]
  (let [key (events/listen js/document
                           EventType.KEYDOWN
                           on-esc-clicked)]
    (assoc own ::key key)))

(defn- lightbox-will-umount
  [own]
  (events/unlistenByKey (::key own))
  (dissoc own ::key))

(mx/defcs lightbox
  {:mixins [mx/reactive]
   :will-mount lightbox-will-mount
   :will-unmount lightbox-will-umount}
  [own]
  (let [data (mx/react lightbox-ref)
        classes (classnames
                 :hide (nil? data)
                 :transparent (:transparent? data))]
    [:div.lightbox
     {:class classes
      :ref "parent"
      :on-click (partial on-out-clicked own)}
     (render-lightbox data)]))
