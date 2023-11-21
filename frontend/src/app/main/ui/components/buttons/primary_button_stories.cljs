(ns app.main.ui.components.buttons.primary-button-stories
  (:require
    [app.main.ui.components.buttons.primary-button :as c]))

(def ^:export default
  #js {:title "Primary Button"
    :component  c/primary-button})

;; (defn ^:export primary-button []
;;   [:& c/primary-button {} "Primary button" ])
