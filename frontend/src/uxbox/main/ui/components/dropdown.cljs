(ns uxbox.main.ui.components.dropdown
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.dom :as dom]
   [goog.events :as events]
   [goog.object :as gobj])
  (:import goog.events.EventType
           goog.events.KeyCodes))

(mf/defc dropdown'
  {::mf/wrap-props false}
  [props]
  (let [children (gobj/get props "children")
        on-close (gobj/get props "on-close")
        ref (gobj/get props "container")

        on-click
        (fn [event]
          (if ref
            (let [target (dom/get-target event)
                  parent (mf/ref-val ref)]
              (when-not (.contains parent target)
                (on-close)))
            (on-close)))

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

    (mf/use-effect on-mount)
    children))

(mf/defc dropdown
  {::mf/wrap-props false}
  [props]
  (assert (fn? (gobj/get props "on-close")) "missing `on-close` prop")
  (assert (boolean? (gobj/get props "show")) "missing `show` prop")

  (when (gobj/get props "show")
    (mf/element dropdown' props)))
