(ns app.main.ui.components.buttons.simple-button
  (:require-macros [app.main.style :as stl])
  (:require
    [app.common.data.macros :as dm]
    [rumext.v2 :as mf]))

(mf/defc simple-button
  {::mf/wrap-props false}
  [{:keys [on-click children]}]
    [:button {:on-click on-click :class (dm/str (stl/css :button))} children])

