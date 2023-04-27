;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.title-bar
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc title-bar
    {::mf/wrap-props false}
  [props]
  (let [collapsable? (unchecked-get props "collapsable?")
        collapsed?   (unchecked-get props "collapsed?")
        on-collapsed (unchecked-get props "on-collapsed")
        title        (unchecked-get props "title")
        children     (unchecked-get props "children")
        on-btn-click (unchecked-get props "on-btn-click")
        btn-children (unchecked-get props "btn-children")
        klass        (unchecked-get props "klass")]
 
    [:div {:class (dom/classnames (css :title-bar) true
                                   klass true)}
     (if collapsable?
       [:button {:class (dom/classnames (css :toggle-btn) true)
                 :on-click on-collapsed}
        [:span {:class (dom/classnames (css :collased-icon) true
                                       (css :rotated) collapsed?)}
         i/arrow-refactor]
        [:div {:class (dom/classnames (css :title) true)}
         title]]
       [:div {:class (dom/classnames (css :title-only) true)}
        title])
     children
     (when (some? on-btn-click)
       [:button {:class (dom/classnames (css :title-button) true)
                 :on-click on-btn-click}
        btn-children])]))