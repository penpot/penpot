(ns uxbox.main.ui.lightbox
  (:require [lentes.core :as l]
            [uxbox.main.store :as st]
            [uxbox.main.data.lightbox :as udl]
            [rumext.core :as mx :include-macros true]
            [uxbox.main.ui.keyboard :as k]
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

(defn- lightbox-init
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
   :init lightbox-init
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
