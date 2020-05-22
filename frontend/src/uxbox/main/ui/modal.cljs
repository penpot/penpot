(ns uxbox.main.ui.modal
  (:require
   [goog.events :as events]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as k]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom])
  (:import goog.events.EventType))

(defonce state (atom nil))

(defn show!
  [component props]
  (reset! state {:component component :props props}))

(defn hide!
  []
  (reset! state nil))

(defn- on-esc-clicked
  [event]
  (when (k/esc? event)
    (reset! state nil)
    (dom/stop-propagation event)))

(defn- on-parent-clicked
  [event parent-ref]
  (let [parent (mf/ref-val parent-ref)
        current (dom/get-target event)]
    (when (dom/equals? parent current)
      (dom/stop-propagation event)
      (dom/prevent-default event)
      (reset! state nil))))

(mf/defc modal-wrapper
  [{:keys [component props]}]
  (mf/use-effect
   (fn []
     (let [key (events/listen js/document EventType.KEYDOWN on-esc-clicked)]
       #(events/unlistenByKey %))))

  (let [classes (classnames :transparent (:transparent? props))
        parent-ref (mf/use-ref nil)]
    [:div.lightbox {:class classes
                    :ref parent-ref
                    :on-click #(on-parent-clicked % parent-ref)
                    }
     (mf/element component props)]))

(mf/defc modal
  [_]
  (when-let [{:keys [component props]} (mf/deref state)]
    [:& modal-wrapper {:component component
                       :props props
                       :key (random-uuid)}]))



