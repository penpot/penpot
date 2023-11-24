(ns app.main.ui.components.design-system
  (:require
   [app.main.ui.components.buttons.simple-button :as sb]
   [app.main.ui.icons :as icons]
   [rumext.v2 :as mf]))

(mf/defc story-wrapper
  {::mf/wrap-props false}
  [{:keys [children]}]
    [:.default children])

(def ^export default #js {
  :icons #js {
    :IconAddRefactor icons/add-refactor
  }
  :StoryWrapper story-wrapper
  :SimpleButton sb/simple-button})
