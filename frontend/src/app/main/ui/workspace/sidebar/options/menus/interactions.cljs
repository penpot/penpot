;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.interactions
  (:require
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.common.types.interactions :as cti]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(defn- event-type-names
  []
  {:click (tr "workspace.options.interaction-on-click")
   :mouse-over (tr "workspace.options.interaction-while-hovering")})

(defn- event-type-name
  [interaction]
  (get (event-type-names) (:event-type interaction) "--"))

(defn- action-type-names
  []
  {:navigate (tr "workspace.options.interaction-navigate-to")
   :open-overlay (tr "workspace.options.interaction-open-overlay")
   :close-overlay (tr "workspace.options.interaction-close-overlay")})

(defn- action-summary
  [interaction destination]
  (case (:action-type interaction)
    :navigate (tr "workspace.options.interaction-navigate-to-dest"
                  (get destination :name (tr "workspace.options.interaction-none")))
    :open-overlay (tr "workspace.options.interaction-open-overlay-dest"
                      (get destination :name (tr "workspace.options.interaction-none")))
    :close-overlay (tr "workspace.options.interaction-close-overlay-dest"
                       (get destination :name (tr "workspace.options.interaction-self")))
    "--"))

(mf/defc interaction-entry
  [{:keys [index shape interaction update-interaction remove-interaction]}]
  (let [objects     (deref refs/workspace-page-objects)
        destination (get objects (:destination interaction))
        frames      (mf/use-memo (mf/deps objects)
                                 #(cp/select-frames objects))

        extended-open? (mf/use-state false)

        change-event-type
        (fn [event]
          (let [value (-> event dom/get-target dom/get-value d/read-string)]
            (update-interaction index #(cti/set-event-type % value))))

        change-action-type
        (fn [event]
          (let [value (-> event dom/get-target dom/get-value d/read-string)]
            (update-interaction index #(cti/set-action-type % value))))

        change-destination
        (fn [event]
          (let [value (-> event dom/get-target dom/get-value)
                value (when (not= value "") (uuid/uuid value))]
            (update-interaction index #(cti/set-destination % value shape objects))))]

    [:*
     [:div.element-set-options-group
      [:div.element-set-actions-button {:on-click #(swap! extended-open? not)}
       i/actions]
      [:div.interactions-summary
       [:div.trigger-name (event-type-name interaction)]
       [:div.action-summary (action-summary interaction destination)]]
      [:div.elemen-set-actions {:on-click #(remove-interaction index)}
       [:div.element-set-actions-button i/minus]]]
     (when @extended-open?
       [:div.element-set
        [:div.element-set-content
         [:div.interactions-element
            [:span.element-set-subtitle.wide (tr "workspace.options.interaction-trigger")]
            [:select.input-select
             {:value (str (:event-type interaction))
              :on-change change-event-type}
             (for [[value name] (event-type-names)]
               [:option {:value (str value)} name])]]
         [:div.interactions-element
            [:span.element-set-subtitle.wide (tr "workspace.options.interaction-action")]
            [:select.input-select
             {:value (str (:action-type interaction))
              :on-change change-action-type}
             (for [[value name] (action-type-names)]
               [:option {:value (str value)} name])]]
         [:div.interactions-element
            [:span.element-set-subtitle.wide (tr "workspace.options.interaction-destination")]
            [:select.input-select
             {:value (str (:destination interaction))
              :on-change change-destination}
             [:option {:value ""} (tr "workspace.options.interaction-none")]
             (for [frame frames]
               (when (and (not= (:id frame) (:id shape)) ; A frame cannot navigate to itself
                          (not= (:id frame) (:frame-id shape))) ; nor a shape to its container frame
                 [:option {:value (str (:id frame))} (:name frame)]))]]]])]))

(mf/defc interactions-menu
  [{:keys [shape] :as props}]
  (let [interactions (get shape :interactions [])

        add-interaction
        (fn [_]
          (let [new-interactions (conj interactions cti/default-interaction)]
            (st/emit! (dw/update-shape (:id shape) {:interactions new-interactions}))))

        remove-interaction
        (fn [index]
          (let [new-interactions
                (into (subvec interactions 0 index)
                      (subvec interactions (inc index)))]
            (st/emit! (dw/update-shape (:id shape) {:interactions new-interactions}))))

        update-interaction
        (fn [index update-fn]
          (let [new-interactions (update interactions index update-fn)]
            (st/emit! (dw/update-shape (:id shape) {:interactions new-interactions})))) ]

    [:div.element-set
     (when shape
       [:div.element-set-title
        [:span (tr "workspace.options.interactions")]
        [:div.add-page {:on-click add-interaction}
         i/plus]])

     [:div.element-set-content
      (when (= (count interactions) 0)
        [:*
         (when shape
           [:*
            [:div.interactions-help-icon i/plus]
            [:div.interactions-help (tr "workspace.options.add-interaction")]])
         [:div.interactions-help-icon i/interaction]
         [:div.interactions-help (tr "workspace.options.select-a-shape")]
         [:div.interactions-help-icon i/play]
         [:div.interactions-help (tr "workspace.options.use-play-button")]])]
     (for [[index interaction] (d/enumerate interactions)]
       [:& interaction-entry {:index index
                              :shape shape
                              :interaction interaction
                              :update-interaction update-interaction
                              :remove-interaction remove-interaction}])]))

