;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.components.context-menu
  (:require
   [rumext.alpha :as mf]
   [goog.object :as gobj]
   [uxbox.main.ui.components.dropdown :refer [dropdown']]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.data :refer [classnames]]))

(mf/defc context-menu
  {::mf/wrap-props false}
  [props]
  (assert (fn? (gobj/get props "on-close")) "missing `on-close` prop")
  (assert (boolean? (gobj/get props "show")) "missing `show` prop")
  (assert (vector? (gobj/get props "options")) "missing `options` prop")

  (let [open? (gobj/get props "show")
        options (gobj/get props "options")
        is-selectable (gobj/get props "selectable")
        selected (gobj/get props "selected")]
    (when open?
      [:> dropdown' props
       [:div.context-menu {:class (classnames :is-open open?
                                              :is-selectable is-selectable)}
        [:ul.context-menu-items
         (for [[action-name action-handler] options]
           [:li.context-menu-item {:class (classnames :is-selected (and selected (= action-name selected)))
                                   :key action-name}
            [:a.context-menu-action {:on-click action-handler}
             action-name]])]]])))
