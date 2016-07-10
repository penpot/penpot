(ns uxbox.main.ui.lightbox
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.main.state :as st]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.util.mixins :as mx]
            [uxbox.main.ui.keyboard :as k]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (classnames)]
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

(defn- lightbox-render
  [own]
  (let [data (mx/react lightbox-ref)
        classes (classnames
                 :hide (nil? data)
                 :transparent (:transparent? data))]
    (html
     [:div.lightbox
      {:class classes
       :ref "parent"
       :on-click (partial on-out-clicked own)}
      (render-lightbox data)])))

(def lightbox
  (mx/component
   {:name "lightbox"
    :render lightbox-render
    :will-mount lightbox-will-mount
    :will-unmount lightbox-will-umount
    :mixins [mx/reactive mx/static]}))
