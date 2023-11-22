(ns app.main.ui.components.buttons.primary-button
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.context :as ctx]
   [rumext.v2 :as mf]))

(mf/defc primary-button
  {::mf/wrap-props false}
  [{:keys [on-click children disabled?]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    [:button
      {:class (dm/str (stl/css new-css-system :primary-button))
       :on-click on-click
       :disabled disabled?}
      children]))
