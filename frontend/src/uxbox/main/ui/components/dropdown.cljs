(ns uxbox.main.ui.components.dropdown
  (:require
   [rumext.alpha :as mf]
   [uxbox.util.uuid :as uuid]
   [goog.events :as events]
   [goog.object :as gobj])
  (:import goog.events.EventType
           goog.events.KeyCodes))

(mf/defrc dropdown-container
  [props]
  (let [children (gobj/get props "children")
        on-close (gobj/get props "on-close")

        on-click
        (fn [event]
          (on-close))

        on-keyup
        (fn [event]
          (when (= (.-keyCode event) 27) ; ESC
            (on-close)))

        on-mount
        (fn []
          (let [lkey1 (events/listen js/document EventType.CLICK on-click)
                lkey2 (events/listen js/document EventType.KEYUP on-keyup)]
            #(do
               (events/unlistenByKey lkey1)
               (events/unlistenByKey lkey2))))]

    (mf/use-effect {:fn on-mount})
    children))

(mf/defrc dropdown
  [props]
  (assert (fn? (gobj/get props "on-close")) "missing `on-close` prop")
  (assert (boolean? (gobj/get props "show")) "missing `show` prop")

  (when (gobj/get props "show")
    [:div.dropdown
     (mf/element dropdown-container props)]))
