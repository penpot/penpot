
(ns app.main.ui.workspace.sidebar.options.menus.variants-modal
  (:require-macros [app.main.style :as stl])
  (:require
    [app.main.ui.ds.buttons.button :refer [button*]]
    [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
    [app.main.ui.ds.modal :as ds-modal]
    [app.util.i18n :refer [tr]]))

(mf/defc ariants-modal
  {::mf/register modal/components
   ::mf/register-as :plugin-management}
  [params]
  [:> ds-modal/modal
    {:class (stl/css :variants-modal)
    :header
      [:<>
        [:div {:class (stl/css :title)}
          (tr "variants.modal.title")]
        [:div {:class (stl/css :icon-close)}
          [:> icon-button*
            {:icon "close"
            :on-click (:on-close params)}]]]
    :body
      [:div {:class (stl/css :content)}
        [:p (tr "variants.modal.description")]
        [:img {:src "/images/variants-modal.png"}]]
    :footer
      [:> button*
        {:class (stl/css :ok-button)
        :on-click (:on-close params)}
        (tr "variants.modal.ok")]]})
