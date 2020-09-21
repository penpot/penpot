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
   [app.util.dom :as dom]
   [app.main.refs :as refs]
   [potok.core :as ptk]
   [app.main.data.modal :as mdm])
  (:import goog.events.EventType))

(defonce components (atom {}))

(defn show!
  [type props]
  (let [id    (random-uuid)]
    (st/emit! (mdm/show-modal id type props))))

(defn allow-click-outside! []
  (st/emit! (mdm/update-modal {:allow-click-outside true})))

(defn disallow-click-outside! []
  (st/emit! (mdm/update-modal {:allow-click-outside false})))

(defn hide!
  []
  (st/emit! (mdm/hide-modal)))

(def hide (mdm/hide-modal))

(defn- on-esc-clicked
  [event]
  (when (k/esc? event)
    (hide!)
    (dom/stop-propagation event)))

(defn- on-pop-state
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event)
  (hide!)
  (.forward js/history))

(defn- on-parent-clicked
  [event parent-ref]
  (let [parent (mf/ref-val parent-ref)
        current (dom/get-target event)]
    (when (and (dom/equals? (.-firstElementChild ^js parent) current)
               (= (.-className ^js current) "modal-overlay"))
      (dom/stop-propagation event)
      (dom/prevent-default event)
      (hide!))))

(defn- on-click-outside
  [event wrapper-ref allow-click-outside]
  (let [wrapper (mf/ref-val wrapper-ref)
        current (dom/get-target event)]

    (when (and wrapper (not allow-click-outside) (not (.contains wrapper current)))
      (dom/stop-propagation event)
      (dom/prevent-default event)
      (hide!))))

(mf/defc modal-wrapper
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [data        (unchecked-get props "data")
        wrapper-ref (mf/use-ref nil)
        handle-click-outside
        (fn [event]
          (on-click-outside event wrapper-ref (:allow-click-outside data)))]

    (mf/use-layout-effect
     (fn []
       (let [keys [(events/listen js/document EventType.KEYDOWN  on-esc-clicked)
                   (events/listen js/window   EventType.POPSTATE on-pop-state)
                   (events/listen js/document EventType.CLICK    handle-click-outside)]]
         #(for [key keys]
            (events/unlistenByKey key)))))
    [:div.modal-wrapper {:ref wrapper-ref}
     (mf/element
      (get @components (:type data))
      (:props data))]))


(def modal-ref
  (l/derived ::mdm/modal st/state))

(mf/defc modal
  []
  (let [modal (mf/deref modal-ref)]
    (when modal [:& modal-wrapper {:data modal
                                   :key (:id modal)}])))
