;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.modal
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.main.data.modal :as dm]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.util.dom :as dom]
   [app.util.keyboard :as k]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(defn- on-esc-clicked
  [event allow-click-outside]
  (when (and (k/esc? event) (not allow-click-outside))
    (dom/stop-propagation event)
    (st/emit! (dm/hide))))

(defn- on-pop-state
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event)
  (st/emit! (dm/hide))
  (.forward js/history))

(defn- on-click-outside
  [event wrapper-ref type allow-click-outside]
  (let [wrapper (mf/ref-val wrapper-ref)
        current (dom/get-target event)]

    (when (and wrapper
               (not allow-click-outside)
               (not (.contains wrapper current))
               (not (= type (keyword (dom/get-data current "allow-click-modal")))))
      (dom/stop-propagation event)
      (dom/prevent-default event)
      (st/emit! (dm/hide)))))

(mf/defc modal-wrapper
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [data           (unchecked-get props "data")
        wrapper-ref    (mf/use-ref nil)
        components     (mf/deref dm/components)
        new-css-system (mf/use-ctx ctx/new-css-system)

        allow-click-outside (:allow-click-outside data)

        handle-click-outside
        (fn [event]
          (on-click-outside event wrapper-ref (:type data) allow-click-outside))

        handle-keydown
        (fn [event]
          (on-esc-clicked event allow-click-outside))]

    (mf/use-layout-effect
     (mf/deps allow-click-outside)
     (fn []
       (let [keys [(events/listen js/window   EventType.POPSTATE    on-pop-state)
                   (events/listen js/document EventType.KEYDOWN     handle-keydown)

                   ;; Changing to js/document breaks the color picker
                   (events/listen (dom/get-root) EventType.POINTERDOWN handle-click-outside)

                   (events/listen js/document EventType.CONTEXTMENU handle-click-outside)]]
         #(doseq [key keys]
            (events/unlistenByKey key)))))

    (when-let [component (get components (:type data))]
      [:div {:ref wrapper-ref
             :class (dom/classnames (css :modal-wrapper) new-css-system
                                    :modal-wrapper (not new-css-system))}
       (mf/element component (:props data))])))

(def modal-ref
  (l/derived ::dm/modal st/state))

(mf/defc modal
  {::mf/wrap-props false}
  []
  (let [modal (mf/deref modal-ref)
        new-css-system (features/use-feature "styles/v2")]
    (when modal
      [:& (mf/provider ctx/new-css-system) {:value new-css-system}
       [:& modal-wrapper {:data modal
                          :key (:id modal)}]])))
