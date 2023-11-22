(ns app.main.ui.components.buttons.primary-button-stories
  (:require
    [rumext.v2 :as mf]
    [app.main.ui.components.buttons.primary-button :as c]))

(def ^:export default
  #js { :component  c/primary-button})

(defn ^:export Normal []
  #js { :render (mf/element c/primary-button #js {:children "Simple"}) })
