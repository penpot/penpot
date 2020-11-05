;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.modal
  (:require
   [okulary.core :as l]
   [goog.events :as events]
   [rumext.alpha :as mf]
   [app.main.store :as st]
   [app.main.ui.keyboard :as k]
   [app.main.data.modal :as dm]
   [app.util.dom :as dom]
   [app.main.refs :as refs])
  (:import goog.events.EventType))

(defn- on-esc-clicked
  [event allow-click-outside]
  (when (and (k/esc? event) (not allow-click-outside))
    (do (dom/stop-propagation event)
        (st/emit! (dm/hide)))))

(defn- on-pop-state
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event)
  (st/emit! (dm/hide))
  (.forward js/history))

(defn- on-parent-clicked
  [event parent-ref]
  (let [parent  (mf/ref-val parent-ref)
        current (dom/get-target event)]
    (when (and (dom/equals? (.-firstElementChild ^js parent) current)
               (= (.-className ^js current) "modal-overlay"))
      (dom/stop-propagation event)
      (dom/prevent-default event)
      (st/emit! (dm/hide)))))

(defn- on-click-outside
  [event wrapper-ref type allow-click-outside]
  (let [wrapper (mf/ref-val wrapper-ref)
        current (dom/get-target event)]

    (when (and wrapper
               (not allow-click-outside)
               (not (.contains wrapper current))
               (not (= type (keyword (.getAttribute current "data-allow-click-modal")))))
      (dom/stop-propagation event)
      (dom/prevent-default event)
      (st/emit! (dm/hide)))))

(mf/defc modal-wrapper
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [data        (unchecked-get props "data")
        wrapper-ref (mf/use-ref nil)

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
       (let [keys [(events/listen js/window      EventType.POPSTATE on-pop-state)
                   (events/listen (dom/get-root) EventType.KEYDOWN  handle-keydown)
                   (events/listen (dom/get-root) EventType.CLICK    handle-click-outside)]]
         #(doseq [key keys]
            (events/unlistenByKey key)))))

    [:div.modal-wrapper {:ref wrapper-ref}
     (mf/element
      (get @dm/components (:type data))
      (:props data))]))


(def modal-ref
  (l/derived ::dm/modal st/state))

(mf/defc modal
  []
  (let [modal (mf/deref modal-ref)]
    (when modal [:& modal-wrapper {:data modal
                                   :key (:id modal)}])))
