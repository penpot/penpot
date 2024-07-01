(ns app.main.ui.ds.buttons.simple-button
  (:require-macros [app.main.style :as stl])
  (:require
   [rumext.v2 :as mf]))

(mf/defc simple-button
  {::mf/wrap-props false}
  [{:keys [on-click children]}]
  [:button {:on-click on-click :class (stl/css :button)} children])

