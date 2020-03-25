(ns uxbox.main.ui.components.context-menu
  (:require
   [rumext.alpha :as mf]
   [goog.object :as gobj]
   [uxbox.main.ui.components.dropdown :refer [dropdown-container]]
   [uxbox.util.uuid :as uuid]))

(mf/defc context-menu
  {::mf/wrap-props false}
  [props]
  (assert (fn? (gobj/get props "on-close")) "missing `on-close` prop")
  (assert (boolean? (gobj/get props "show")) "missing `show` prop")
  (assert (vector? (gobj/get props "options")) "missing `options` prop")

  (let [open? (gobj/get props "show")
        options (gobj/get props "options")]
    (when open?
      [:> dropdown-container props
       [:div.context-menu {:class (when open? "is-open")}
        [:ul.context-menu-items
         (for [[action-name action-handler] options]
           [:li.context-menu-item {:key action-name}
            [:a.context-menu-action {:on-click action-handler}
             action-name]])]]])))
