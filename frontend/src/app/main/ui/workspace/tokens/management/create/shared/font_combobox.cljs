(ns app.main.ui.workspace.tokens.management.create.shared.font-combobox
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.token :as cto]
   [app.main.fonts :as fonts]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [font-selector*]]
   [app.main.ui.workspace.tokens.management.create.input-tokens-value :refer [input-token*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc font-selector-wrapper*
  {::mf/private true}
  [{:keys [font input-ref on-select-font on-close-font-selector]}]
  (let [current-font* (mf/use-state (or font
                                        (some-> (mf/ref-val input-ref)
                                                (dom/get-value)
                                                (cto/split-font-family)
                                                (first)
                                                (fonts/find-font-family))))
        current-font (deref current-font*)]
    [:div {:class (stl/css :font-select-wrapper)}
     [:> font-selector* {:current-font current-font
                         :on-select on-select-font
                         :on-close on-close-font-selector
                         :full-size true}]]))

(mf/defc font-picker-combobox*
  [{:keys [default-value label aria-label input-ref on-blur on-update-value on-external-update-value token-resolve-result placeholder]}]
  (let [font* (mf/use-state (fonts/find-font-family default-value))
        font (deref font*)
        set-font (mf/use-fn
                  (mf/deps font)
                  #(reset! font* %))

        font-selector-open* (mf/use-state false)
        font-selector-open? (deref font-selector-open*)

        on-close-font-selector
        (mf/use-fn
         (fn []
           (reset! font-selector-open* false)))

        on-click-dropdown-button
        (mf/use-fn
         (mf/deps font-selector-open?)
         (fn [e]
           (dom/prevent-default e)
           (reset! font-selector-open* (not font-selector-open?))))

        on-select-font
        (mf/use-fn
         (mf/deps on-external-update-value set-font font)
         (fn [{:keys [family] :as font}]
           (when font
             (set-font font)
             (on-external-update-value family))))

        on-update-value'
        (mf/use-fn
         (mf/deps on-update-value set-font)
         (fn [value]
           (set-font nil)
           (on-update-value value)))

        font-selector-button
        (mf/html
         [:> icon-button*
          {:on-click on-click-dropdown-button
           :aria-label (tr "workspace.tokens.token-font-family-select")
           :icon i/arrow-down
           :variant "action"
           :type "button"}])]
    [:*
     [:> input-token*
      {:placeholder (or placeholder (tr "workspace.tokens.token-font-family-value-enter"))
       :label label
       :aria-label aria-label
       :default-value (or (:name font) default-value)
       :ref input-ref
       :on-blur on-blur
       :on-change on-update-value'
       :icon i/text-font-family
       :slot-end font-selector-button
       :token-resolve-result token-resolve-result}]
     (when font-selector-open?
       [:> font-selector-wrapper* {:font font
                                   :input-ref input-ref
                                   :on-select-font on-select-font
                                   :on-close-font-selector on-close-font-selector}])]))