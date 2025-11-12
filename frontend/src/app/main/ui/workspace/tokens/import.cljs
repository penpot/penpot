(ns app.main.ui.workspace.tokens.import
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.tokens.import.modal :refer [import-modal-body*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc import-modal*
  {::mf/register modal/components
   ::mf/register-as :tokens/import}
  []
  [:div {:class (stl/css :modal-overlay)}
   [:div {:class (stl/css :modal-dialog)}
    [:> icon-button* {:class (stl/css :close-btn)
                      :on-click modal/hide!
                      :aria-label (tr "labels.close")
                      :variant "ghost"
                      :icon i/close}]
    [:> import-modal-body*]]])
