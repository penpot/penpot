;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
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
    (st/emit! (modal/hide))))

(defn- on-pop-state
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event)
  (st/emit! (modal/hide))
  (.forward js/history))

(defn- on-click-outside
  [event wrapper-ref type allow-click-outside]
  (let [wrapper (mf/ref-val wrapper-ref)
        current (dom/get-target event)]

    (when (and wrapper
               (not allow-click-outside)
               (not (.contains wrapper current))
               (not (= type (keyword (dom/get-data current "allow-click-modal"))))
               (= (.-button event) 0))
      (dom/stop-propagation event)
      (dom/prevent-default event)
      (st/emit! (modal/hide)))))

(mf/defc modal-wrapper*
  {::mf/props :obj
   ::mf/wrap [mf/memo]}
  [{:keys [data]}]
  (let [wrapper-ref    (mf/use-ref nil)
        components     (mf/deref modal/components)

        allow-click-outside (:allow-click-outside data)

        handle-click-outside
        (fn [event]
          (on-click-outside event wrapper-ref (:type data) allow-click-outside))

        handle-keydown
        (fn [event]
          (on-esc-clicked event allow-click-outside))]

    (mf/with-effect [allow-click-outside]
      (let [keys [(events/listen js/window   EventType.POPSTATE    on-pop-state)
                  (events/listen js/document EventType.KEYDOWN     handle-keydown)

                  ;; Changing to js/document breaks the color picker
                  (events/listen (dom/get-root) EventType.POINTERDOWN handle-click-outside)

                  (events/listen js/document EventType.CONTEXTMENU handle-click-outside)]]
        (fn []
          (run! events/unlistenByKey keys))))

    (when-let [component (get components (:type data))]
      [:div {:ref wrapper-ref
             :class (stl/css :modal-wrapper)}
       (mf/element component (:props data))])))

(def ^:private ref:modal
  (l/derived ::modal/modal st/state))

(mf/defc modal-container*
  {::mf/props :obj}
  []
  (when-let [modal (mf/deref ref:modal)]
    (mf/portal
     (mf/html [:> modal-wrapper* {:data modal :key (dm/str (:id modal))}])
     (dom/get-body))))
