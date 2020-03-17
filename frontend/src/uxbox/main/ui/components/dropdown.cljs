(ns uxbox.main.ui.components.dropdown
  (:require
   [rumext.alpha :as mf]
   [uxbox.util.uuid :as uuid]
   [goog.events :as events]
   [goog.object :as gobj])
  (:import goog.events.EventType
           goog.events.KeyCodes))

(mf/defrc dropdown'
  [props]
  (let [children (gobj/get props "children")
        on-close (gobj/get props "on-close")

        on-document-clicked
        (fn [event]
          (on-close))

        on-document-keyup
        (fn [event]
          (when (= (.-keyCode event) 27) ; ESC
            (on-close)))

        on-mount
        (fn []
          (let [lkey1 (events/listen js/document EventType.CLICK on-document-clicked)
                lkey2 (events/listen js/document EventType.KEYUP on-document-keyup)]
            #(do
               (events/unlistenByKey lkey1)
               (events/unlistenByKey lkey2))))]

    (mf/use-effect {:fn on-mount})
    [:div.dropdown
     children]))

(mf/defrc dropdown
  [props]
  (when (gobj/get props "show")
    (mf/element dropdown' props)))
