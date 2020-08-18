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
    ;; (js/console.log current (.-className ^js current))
    (when (and (dom/equals? (.-firstElementChild ^js parent) current)
               (str/includes? (.-className ^js current) "modal-overlay"))
      (dom/stop-propagation event)
      (dom/prevent-default event)
      (reset! state nil))))

(mf/defc modal-wrapper
  [{:keys [component props]}]

  (mf/use-effect
   (fn []
     (let [key (events/listen js/document EventType.KEYDOWN on-esc-clicked)]
       #(events/unlistenByKey %))))

  (let [ref (mf/use-ref nil)]
    [:div.modal-wrapper
     {:ref ref
      :on-click #(on-parent-clicked % ref)}
     [:& component props]]))

(mf/defc modal
  []
  (when-let [{:keys [component props]} (mf/deref state)]
    [:& modal-wrapper {:component component
                       :props props
                       :key (random-uuid)}]))



