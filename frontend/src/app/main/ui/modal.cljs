(ns app.main.ui.modal
  (:require
   [cuerdas.core :as str]
   [goog.events :as events]
   [rumext.alpha :as mf]
   [app.main.store :as st]
   [app.main.ui.keyboard :as k]
   [app.util.data :refer [classnames]]
   [app.util.dom :as dom])
  (:import goog.events.EventType))

(defonce state (atom nil))
(defonce can-click-outside (atom false))

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

(defn- on-click
  [event wrapper-ref]
  (let [wrapper (mf/ref-val wrapper-ref)
        current (dom/get-target event)]

    (when (and (not @can-click-outside) (not (.contains wrapper current)))
      (dom/stop-propagation event)
      (dom/prevent-default event)
      (reset! state nil))))

(mf/defc modal-wrapper
  [{:keys [component props]}]

  (let [wrapper-ref (mf/use-ref nil)]
    (mf/use-effect
     (fn []
       (let [key (events/listen js/document EventType.KEYDOWN on-esc-clicked)]
         #(events/unlistenByKey key))))

    (mf/use-effect
     (fn []
       (let [key (events/listen js/document EventType.CLICK #(on-click % wrapper-ref))]
         #(events/unlistenByKey key))))

    [:div.modal-wrapper
     {:ref wrapper-ref}
     [:& component props]]))

(mf/defc modal
  []
  (when-let [{:keys [component props]} (mf/deref state)]
    [:& modal-wrapper {:component component
                       :props props
                       :key (random-uuid)}]))


(defn allow-click-outside! []
  (reset! can-click-outside true))

(defn disallow-click-outside! []
  (reset! can-click-outside false))
