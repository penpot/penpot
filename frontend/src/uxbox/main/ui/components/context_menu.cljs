(ns uxbox.main.ui.components.context-menu
  (:require
   [rumext.alpha :as mf]
   [goog.object :as gobj]
   [uxbox.main.ui.components.dropdown :refer [dropdown-container]]
   [uxbox.util.uuid :as uuid]
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
    (println "selected" selected)
    (when open?
      [:> dropdown-container props
       [:div.context-menu {:class (classnames :is-open open?
                                              :is-selectable is-selectable)}
        [:ul.context-menu-items
         (for [[action-name action-handler] options]
           [:li.context-menu-item {:class (classnames :is-selected (and selected (= action-name selected)))
                                   :key action-name}
            [:a.context-menu-action {:on-click action-handler}
             action-name]])]]])))
