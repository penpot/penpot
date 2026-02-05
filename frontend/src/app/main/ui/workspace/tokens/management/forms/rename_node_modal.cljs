(ns app.main.ui.workspace.tokens.management.forms.rename-node-modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc rename-node-modal*
  {::mf/register modal/components
   ::mf/register-as :tokens/rename-node}
  [{:keys [node type]}]

  (let [on-close
        (mf/use-fn
         (mf/deps [])
         (fn []
           (prn "Close rename node modal")))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:> icon-button* {:class (stl/css :close-btn)
                        :on-click on-close
                        :aria-label (tr "labels.close")
                        :variant "ghost"
                        :icon i/close}]
      "Rename node modal for node:"
      [:pre (str node)]
      [:pre (str type)]]]))
