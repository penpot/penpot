;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.components.context-menu
  (:require
   [rumext.alpha :as mf]
   [goog.object :as gobj]
   [app.main.ui.components.dropdown :refer [dropdown']]
   [app.common.uuid :as uuid]
   [app.util.data :refer [classnames]]
   [app.util.dom :as dom]))

(mf/defc context-menu
  {::mf/wrap-props false}
  [props]
  (assert (fn? (gobj/get props "on-close")) "missing `on-close` prop")
  (assert (boolean? (gobj/get props "show")) "missing `show` prop")
  (assert (vector? (gobj/get props "options")) "missing `options` prop")

  (let [open? (gobj/get props "show")
        options (gobj/get props "options")
        is-selectable (gobj/get props "selectable")
        selected (gobj/get props "selected")
        top (gobj/get props "top" 0)
        left (gobj/get props "left" 0)
        fixed? (gobj/get props "fixed?" false)

        offset (mf/use-state 0)

        check-menu-offscreen
        (mf/use-callback
         (mf/deps top @offset)
         (fn [node]
           (when (and node (not fixed?))
             (let [{node-height :height}   (dom/get-bounding-rect node)
                   {window-height :height} (dom/get-window-size)
                   target-offset (if (> (+ top node-height) window-height)
                                   (- node-height)
                                   0)]

               (if (not= target-offset @offset)
                 (reset! offset target-offset))))))]

    (when open?
      [:> dropdown' props
       [:div.context-menu {:class (classnames :is-open open?
                                              :fixed fixed?
                                              :is-selectable is-selectable)
                           :style {:top (+ top @offset)
                                   :left left}}
        [:ul.context-menu-items {:ref check-menu-offscreen}
         (for [[action-name action-handler] options]
           [:li.context-menu-item {:class (classnames :is-selected (and selected (= action-name selected)))
                                   :key action-name}
            [:a.context-menu-action {:on-click action-handler}
             action-name]])]]])))
